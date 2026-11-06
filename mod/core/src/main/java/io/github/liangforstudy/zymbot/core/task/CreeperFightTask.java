package io.github.liangforstudy.zymbot.core.task;

import io.github.liangforstudy.zymbot.core.api.BlockPos;
import io.github.liangforstudy.zymbot.core.api.EntityView;
import io.github.liangforstudy.zymbot.core.api.Fuse;
import io.github.liangforstudy.zymbot.core.api.Hands;
import io.github.liangforstudy.zymbot.core.api.Vec3;
import io.github.liangforstudy.zymbot.core.api.WorldView;
import io.github.liangforstudy.zymbot.core.body.Combat;
import io.github.liangforstudy.zymbot.core.body.HazardInterrupt;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Kill a creeper by hitting it and stepping back (owner, 2026-11-03; doc/9b9t/roadmap.md, "Creepers").
 * A creeper lights its fuse when we're within 3 blocks and stops only once we're more than 7 off or out
 * of its sight; it explodes 30 server ticks later. So: wait with the weapon charged, hit it as it walks
 * into reach (the knockback puts it back out of 3), step back while the weapon recharges, again — 2
 * hits with Sharpness V, 3 with a plain diamond sword. If it hisses anyway, run clear of 7 blocks
 * ({@link RetreatTask}) until the fuse dies down, then go back in.
 */
public final class CreeperFightTask implements Task {
    /** Hit it this close (3D, feet to feet): the game's reach is 3 to its hitbox, ~3.3 to its middle. */
    static final double HIT_REACH = 3.2;
    /** While the weapon recharges, keep it at least this far off: it lights at 3. */
    static final double KEEP_OFF = 3.6;
    /** A hissing creeper stops at more than 7: clear of this, and once its fuse is out, go back in. */
    static final double CLEAR = 7.5;
    /**
     * Past this, stop running even if it still looks lit, and watch it. In a server stall the client's
     * fuse runs on while the server's waits: the bot ran 19 blocks (9 TPS with stalls, 2026-11-06).
     */
    static final double FAR_ENOUGH = 10;
    static final int GIVE_UP_TICKS = 40 * 20;
    /**
     * Charged, we wait for it to walk into reach; only if it hasn't come closer for this long (stuck
     * behind something) do we step in. Stepping in to meet it closed us both inside 3 and lit it on
     * every hit (2026-11-06).
     */
    static final int STALLED_TICKS = 40;
    /**
     * Just lit, charged and in reach: hit it, then run. The server lights it inside 3 blocks, and we
     * see it a tick or two late, already hissing at 3.1 (2026-11-06): without this it was never hit.
     */
    static final float HIT_THEN_RUN = 0.5f;
    /**
     * Gone this soon after our hit, it died of it, though it was hissing: a hit before half its fuse
     * leaves 15 server ticks, 15 or more of ours. Three kills logged "went off" without this (2026-11-06).
     */
    static final int KILLED_WITHIN_TICKS = 10;
    /** How often the fight asks again whether it's still worth it (another mob turned up). */
    static final int RECHECK_TICKS = 20;

    private final Hands hands;
    private final UUID target;
    private final Consumer<String> report;
    private final BiConsumer<String, String> note;
    private final Predicate<WorldView> stillWorthIt;
    private final Tally tally;
    private RetreatTask escape;
    private Vec3 lastSeen;
    private Fuse lastFuse = Fuse.CALM;
    private boolean armed;
    private int ticks, stalled, lastHitTick = -1000;
    private double closest = Double.MAX_VALUE;
    private String failure = "";

    /**
     * Hits and run-clears on one creeper. Kept by the reflex, not the task: running clear takes us out
     * of the reflex's range, so each go back in is a new task (the first live kill logged "1 hits" for 4).
     */
    public static final class Tally {
        final UUID creeper;
        int hits, escapes;
        public Tally(UUID creeper) { this.creeper = creeper; }
        public boolean of(UUID id) { return creeper.equals(id); }
    }

    public CreeperFightTask(Hands hands, EntityView creeper, Tally tally, Consumer<String> report,
                            BiConsumer<String, String> note, Predicate<WorldView> stillWorthIt) {
        this.hands = hands;
        this.target = creeper.uuid();
        this.tally = tally;
        this.lastSeen = creeper.pos();
        this.report = report;
        this.note = note;
        this.stillWorthIt = stillWorthIt;
    }

    @Override
    public Status tick(WorldView world) {
        Optional<EntityView> t = world.nearby().stream().filter(e -> target.equals(e.uuid())).findFirst();
        if (t.isEmpty()) {
            cancel();                                                // and the run-clear's path: it kept running after a kill (2026-11-06)
            String count = tally.hits + (tally.hits == 1 ? " hit" : " hits") + (tally.escapes > 0 ? ", ran clear " + tally.escapes + "x" : "");
            boolean hitJustNow = ticks - lastHitTick <= KILLED_WITHIN_TICKS;   // a hit-then-run kills it while it hisses
            if (lastFuse.lit() && !hitJustNow) note.accept("the creeper went off", "gone while hissing, after " + count);
            else if (tally.hits > 0) note.accept("killed the creeper", count);
            return Status.DONE;
        }
        if (++ticks > GIVE_UP_TICKS) {
            cancel();
            failure = "still at the creeper after " + GIVE_UP_TICKS / 20 + " s (" + tally.hits + " hits)";
            return Status.FAILED;
        }
        EntityView creeper = t.get();
        lastSeen = creeper.pos();
        Vec3 me = world.position();
        double d = Combat.distance(creeper.pos(), me);
        Fuse fuse = world.fuse(creeper.id()).orElse(Fuse.CALM);
        lastFuse = fuse;

        if (fuse.lit() && d >= FAR_ENOUGH) {                         // "lit" this far: a stalled server's fuse, seen from here
            if (escape != null) escape.cancel();
            escape = null;
            release();
            return Status.RUNNING;                                   // watch it until it fizzles or comes
        }
        if (escape != null || fuse.lit()) {
            if (escape == null) {
                release();
                if (fuse.progress() < HIT_THEN_RUN && world.attackReady() && d <= HIT_REACH) {
                    hands.attack(creeper.id());                      // aimed last tick; the knockback buys time too
                    tally.hits++;
                    lastHitTick = ticks;
                }
                tally.escapes++;
                note.accept("running clear of the creeper", "it's hissing, " + Math.round(d * 10) / 10.0 + " blocks off");
                escape = new RetreatTask(hands.paths(), () -> lastSeen, Optional.empty(), report);
            }
            if (d >= CLEAR && !fuse.lit()) {                         // out: go back in, weapon charged
                escape.cancel();
                escape = null;
            } else {
                Status s = escape.tick(world);
                if (s == Status.FAILED) {
                    failure = "can't get clear of the creeper: " + escape.failure();
                    escape = null;
                    return Status.FAILED;
                }
                if (s == Status.DONE) escape = null;
                return Status.RUNNING;
            }
        }
        if (ticks % RECHECK_TICKS == 0 && !stillWorthIt.test(world)) {
            cancel();
            return Status.DONE;                                      // the reflex decides again: back off
        }
        if (!armed) {
            armed = true;
            Combat.bestWeapon(world.inventory()).ifPresent(hands::selectSlot);
        }
        hands.lookAt(new Vec3(creeper.pos().x(), creeper.pos().y() + 0.9, creeper.pos().z()));   // aimed every tick, so a hit lands
        boolean ready = world.attackReady();
        if (d < closest - 0.1) {
            closest = d;
            stalled = 0;
        } else {
            stalled++;
        }
        if (ready && d <= HIT_REACH) {
            release();
            hands.attack(creeper.id());
            tally.hits++;
            lastHitTick = ticks;
            closest = Double.MAX_VALUE;                              // knocked back: watch it come again
        } else if (ready && stalled >= STALLED_TICKS) {
            hands.holdBack(false);
            hands.holdKeys(true, false);                             // it isn't coming: step in
        } else if (ready) {
            release();                                               // charged: let it walk into reach
        } else if (d < KEEP_OFF && safeBehind(world, creeper.pos())) {
            hands.holdKeys(false, false);
            hands.holdBack(true);                                    // recharging: back off a step
        } else {
            release();
        }
        return Status.RUNNING;
    }

    /** The block a step behind us (away from the creeper) has solid, hazard-free ground. */
    static boolean safeBehind(WorldView world, Vec3 creeper) {
        Vec3 me = world.position();
        double dx = me.x() - creeper.x(), dz = me.z() - creeper.z();
        double len = Math.max(1e-6, Math.sqrt(dx * dx + dz * dz));
        BlockPos floor = new BlockPos((int) Math.floor(me.x() + dx / len), (int) Math.floor(me.y() - 0.01),
                (int) Math.floor(me.z() + dz / len));
        return world.sturdy(floor) && !HazardInterrupt.touchesHazard(world, floor);
    }

    private void release() {
        hands.holdKeys(false, false);
        hands.holdBack(false);
    }

    @Override public void cancel() {
        release();
        if (escape != null) escape.cancel();
        escape = null;
    }
    @Override public String failure() { return failure; }
    @Override public String describe() { return "fighting a creeper" + (tally.hits > 0 ? " (" + tally.hits + " hits)" : ""); }
}
