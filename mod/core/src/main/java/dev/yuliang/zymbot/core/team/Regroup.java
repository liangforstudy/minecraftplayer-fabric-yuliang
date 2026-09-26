package dev.yuliang.zymbot.core.team;

import dev.yuliang.zymbot.core.api.BlockPos;
import dev.yuliang.zymbot.core.api.EntityView;
import dev.yuliang.zymbot.core.api.Vec3;
import dev.yuliang.zymbot.core.api.WorldView;
import dev.yuliang.zymbot.core.body.Body;
import dev.yuliang.zymbot.core.brain.Objective;
import dev.yuliang.zymbot.core.brain.Planner;
import dev.yuliang.zymbot.core.config.ZymbotConfig;
import dev.yuliang.zymbot.core.store.BotMemory;
import dev.yuliang.zymbot.core.task.RegroupTask;
import dev.yuliang.zymbot.core.task.Task;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The planner's first objective of its own (PHASE2.md §1): after a start or a respawn, if no Zymbot
 * teammate is within {@code regroup_within} blocks, walk to the nearest one — seen, or announced on
 * the local bus by someone in our tab list. Done within {@code within} of them; then idle (P2-3).
 * Armed again by the next start, respawn or {@code /zbot regroup}.
 */
public final class Regroup implements Planner {
    /** A failed regroup waits this long before trying again. */
    public static final long RETRY_MILLIS = 60_000;
    /** Close enough to the world spawn to count as waiting there. */
    static final double AT_SPAWN_BLOCKS = 8;
    /** While nobody is found, ask the team where they are this often. */
    static final long ASK_EVERY_MILLIS = 30_000;

    private final ZymbotConfig config;
    private final Body body;
    private final Supplier<Map<String, BotMemory.RosterEntry>> roster;
    private final LongSupplier clock;
    private final Function<BlockPos, Task> route;
    private final Runnable askWhere;
    private final int within;
    private boolean armed;
    private boolean active;
    private long retryAt;
    private long armedAt;
    private long nextAsk;
    private boolean saidWaiting;

    public Regroup(ZymbotConfig config, Body body, Supplier<Map<String, BotMemory.RosterEntry>> roster, LongSupplier clock,
                   Function<BlockPos, Task> route, Runnable askWhere, int within) {
        this.config = config;
        this.body = body;
        this.roster = roster;
        this.clock = clock;
        this.route = route;
        this.askWhere = askWhere;
        this.within = within;
    }

    /** Look for the team on the next idle tick (start, respawn, /zbot regroup). */
    public void arm() {
        armed = true;
        retryAt = 0;
        armedAt = clock.getAsLong();
        nextAsk = 0;                                            // ask the team at once
        saidWaiting = false;
    }

    /** Walking back to the team now — the leash leaves this alone (it is the walk back). */
    public boolean active() { return active; }

    @Override
    public Optional<Objective> best(WorldView world) {
        active = false;
        if (!armed || clock.getAsLong() < retryAt) return Optional.empty();
        Optional<EntityView> seen = body.nearestTeammate(world);
        if (seen.isPresent() && seen.get().pos().horizontalDistance(world.position()) <= config.regroupWithin) {
            armed = false;                                      // with the team already
            return Optional.empty();
        }
        Optional<Found> first = find(world);
        if (first.isEmpty()) return toSpawn(world);
        active = true;
        Found f = first.get();
        String why = "nobody from the team within " + config.regroupWithin + " blocks; " + f.mate().name() + " is at "
                + Math.round(f.mate().pos().x()) + ", " + Math.round(f.mate().pos().z())
                + (f.mate().live() ? " (in sight)" : " (bus, " + Math.max(0, f.ageMillis() / 1000) + " s ago)");
        String who = f.mate().name();
        return Optional.of(Objective.of("regroup with " + who, why, (w, h) -> {
            RegroupTask walk = new RegroupTask(who, () -> find(w, who).map(Found::mate), route, askWhere, within);
            return new Task() {                                 // tell the planner how it ended
                public Status tick(WorldView world) {
                    Status s = walk.tick(world);
                    if (s != Status.RUNNING) finished(s == Status.DONE);
                    return s;
                }
                public void cancel() { walk.cancel(); active = false; }
                public String failure() { return walk.failure(); }
                public String describe() { return walk.describe(); }
            };
        }));
    }

    /**
     * Nobody from the team found: keep asking; after {@code probe_wait_seconds}, walk to the world
     * spawn and wait there, still listening (P2-5) — a teammate heard later wins.
     */
    private Optional<Objective> toSpawn(WorldView world) {
        long now = clock.getAsLong();
        if (now >= nextAsk) {
            nextAsk = now + ASK_EVERY_MILLIS;
            askWhere.run();
        }
        if (now - armedAt < config.probeWaitSeconds * 1000L) return Optional.empty();
        Optional<BlockPos> spawn = world.worldSpawn();
        if (spawn.isEmpty()) return Optional.empty();
        BlockPos s = spawn.get();
        if (world.position().horizontalDistance(s.center()) <= AT_SPAWN_BLOCKS) {
            saidWaiting = true;                                 // there: idle, still armed and listening
            return Optional.empty();
        }
        active = true;
        String why = "no teammate answered in " + config.probeWaitSeconds + " s — waiting for the team at spawn";
        return Optional.of(Objective.of("go to spawn", why, (w, h) -> {
            Task walk = route.apply(new BlockPos(s.x(), 0, s.z()));
            return new Task() {
                public Status tick(WorldView world) {
                    Status st = walk.tick(world);
                    if (st != Status.RUNNING) active = false;
                    if (st == Status.FAILED) retryAt = clock.getAsLong() + RETRY_MILLIS;
                    return st;                                  // done: still armed, waits at spawn
                }
                public void cancel() { walk.cancel(); active = false; }
                public String failure() { return walk.failure(); }
                public String describe() { return "going to spawn at " + s.x() + " " + s.z(); }
            };
        }));
    }

    /** The regroup task finished: done with the team, or it failed (try again later). */
    public void finished(boolean reached) {
        active = false;
        if (reached) armed = false;
        else retryAt = clock.getAsLong() + RETRY_MILLIS;
    }

    record Found(RegroupTask.Mate mate, long ageMillis) {}

    /** The nearest teammate: in sight, else announced by someone online. */
    Optional<Found> find(WorldView world) { return find(world, null); }

    private Optional<Found> find(WorldView world, String only) {
        Vec3 me = world.position();
        Optional<EntityView> seen = body.nearestTeammate(world)
                .filter(e -> only == null || e.name().equalsIgnoreCase(only));
        if (only != null && seen.isEmpty()) {                   // following one person: look for them by name
            seen = world.nearby().stream().filter(e -> e.kind() == EntityView.Kind.PLAYER && e.name().equalsIgnoreCase(only)
                    && body.isTeammate(e.uuid())).findFirst();
        }
        if (seen.isPresent()) return Optional.of(new Found(new RegroupTask.Mate(seen.get().name(), seen.get().pos(), true), 0));
        long now = clock.getAsLong();
        return roster.get().entrySet().stream()
                .filter(e -> "TEAMMATE".equals(e.getValue().phase))
                .filter(e -> only == null || e.getValue().name.equalsIgnoreCase(only))
                .filter(e -> online(world, e.getKey()))
                .map(e -> new Found(new RegroupTask.Mate(e.getValue().name, new Vec3(e.getValue().x + 0.5, me.y(), e.getValue().z + 0.5), false),
                        now - e.getValue().lastSeenMillis))
                .min(Comparator.comparingDouble(f -> f.mate().pos().horizontalDistance(me)));
    }

    private static boolean online(WorldView world, String uuid) {
        try {
            return world.onlinePlayers().contains(UUID.fromString(uuid));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
