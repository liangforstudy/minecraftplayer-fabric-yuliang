package dev.yuliang.zymbot.core.team;

import dev.yuliang.zymbot.core.Bot;
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
 * {@code regroup_within} only decides whether a regroup starts: once under way it finishes the
 * approach — Bot1, swum back from the ocean and interrupted 21 blocks short, counted itself "with
 * the team" and idled out of reach of its teammate (2026-09-26).
 * Only a current teammate counts ({@link #FRESH_MILLIS}): Bluetails_zym set role none, went silent
 * and walked off; Bot1 "regrouped" with a 144 s old bus position where it already stood and never
 * fell back to spawn (2026-09-26).
 * Armed again by the next start, respawn or {@code /zbot regroup}.
 */
public final class Regroup implements Planner {
    /** A failed regroup waits this long before trying again. */
    public static final long RETRY_MILLIS = 60_000;
    /** Close enough to the world spawn to count as waiting there. */
    static final double AT_SPAWN_BLOCKS = 8;
    /** While nobody is found, ask the team where they are this often. */
    static final long ASK_EVERY_MILLIS = 30_000;
    /**
     * A bus position (and bus-only teammate-ness) older than this is not knowing where - or whether -
     * they are: two HELLO periods, so one lost HELLO doesn't drop a live teammate. A teammate answers a
     * WHERE at once, so a real one is fresh again within a tick or two; a silent one (role none, quit
     * the mod) goes stale and the probe, then spawn, fallback takes over. Not probe_wait_seconds: at
     * 30 s it is shorter than the 60 s HELLO period and would drop live teammates half the time.
     */
    public static final long FRESH_MILLIS = 2 * Bot.HELLO_EVERY_MILLIS;

    private final ZymbotConfig config;
    private final Body body;
    private final Supplier<Map<String, BotMemory.RosterEntry>> roster;
    private final LongSupplier clock;
    private final Function<BlockPos, Task> route;
    private final Runnable askWhere;
    private final int within;
    private boolean armed;
    private boolean active;
    private boolean underWay;                                   // started, not reached yet: regroup_within no longer counts
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
        underWay = false;
    }

    /** Walking back to the team now — the leash leaves this alone (it is the walk back). */
    public boolean active() { return active; }

    /** Looking for the team (started, respawned, /zbot regroup) and not with them yet — the idle follow waits. */
    public boolean armed() { return armed; }

    /** The nearest current teammate in sight ({@link #current}) — what the leash and the idle follow anchor to. */
    public Optional<EntityView> nearestTeammate(WorldView world) { return nearestCurrent(world, null); }

    @Override
    public Optional<Objective> best(WorldView world) {
        active = false;
        if (!armed || clock.getAsLong() < retryAt) return Optional.empty();
        Optional<EntityView> seen = nearestCurrent(world, null);
        if (!underWay && seen.isPresent() && seen.get().pos().horizontalDistance(world.position()) <= config.regroupWithin) {
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
            underWay = true;                                    // now it ends within `within` of them, or fails
            // this tick's world, not the one it started in: the Fabric side builds a fresh WorldView (and
            // its `nearby` snapshot) every tick — reading `w` kept a teammate who came into view "out of
            // sight" for good; Bot1 stood 3.6 blocks from Bluetails_zym until it failed (2026-09-26)
            RegroupTask walk = new RegroupTask(who, now -> find(now, who).map(Found::mate), route, askWhere, within);
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
        if (reached) {
            armed = false;
            underWay = false;
        }
        else retryAt = clock.getAsLong() + RETRY_MILLIS;
    }

    record Found(RegroupTask.Mate mate, long ageMillis) {}

    /** The nearest teammate: in sight, else announced by someone online. */
    Optional<Found> find(WorldView world) { return find(world, null); }

    private Optional<Found> find(WorldView world, String only) {
        Vec3 me = world.position();
        Optional<EntityView> seen = nearestCurrent(world, only);
        if (seen.isPresent()) return Optional.of(new Found(new RegroupTask.Mate(seen.get().name(), seen.get().pos(), true, clock.getAsLong()), 0));
        long now = clock.getAsLong();
        return roster.get().entrySet().stream()
                .filter(e -> "TEAMMATE".equals(e.getValue().phase))
                .filter(e -> fresh(e.getValue(), now))              // an old position is not knowing where they are
                .filter(e -> only == null || e.getValue().name.equalsIgnoreCase(only))
                .filter(e -> online(world, e.getKey()))
                .map(e -> new Found(new RegroupTask.Mate(e.getValue().name, new Vec3(e.getValue().x + 0.5, me.y(), e.getValue().z + 0.5), false,
                                e.getValue().lastSeenMillis),
                        now - e.getValue().lastSeenMillis))
                .min(Comparator.comparingDouble(f -> f.mate().pos().horizontalDistance(me)));
    }

    /** The nearest current teammate in sight (optionally only the one named). */
    private Optional<EntityView> nearestCurrent(WorldView world, String only) {
        Vec3 me = world.position();
        return world.nearby().stream()
                .filter(e -> e.kind() == EntityView.Kind.PLAYER && !e.uuid().equals(world.selfId()))
                .filter(e -> only == null || e.name().equalsIgnoreCase(only))
                .filter(e -> body.isTeammate(e.uuid()) && current(e.uuid()))
                .min(Comparator.comparingDouble(e -> e.pos().horizontalDistance(me)));
    }

    /**
     * Still a teammate, not just remembered as one: listed as a Teammate in our accounts, or heard on the
     * bus within {@link #FRESH_MILLIS}. Someone next to us whose only claim is an old HELLO (role none
     * now: silent) doesn't count.
     */
    private boolean current(UUID id) {
        if (config.roleOf(id) == ZymbotConfig.Role.TEAMMATE) return true;
        BotMemory.RosterEntry r = roster.get().get(id.toString());
        return r != null && fresh(r, clock.getAsLong());
    }

    private static boolean fresh(BotMemory.RosterEntry r, long now) { return now - r.lastSeenMillis <= FRESH_MILLIS; }

    private static boolean online(WorldView world, String uuid) {
        try {
            return world.onlinePlayers().contains(UUID.fromString(uuid));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
