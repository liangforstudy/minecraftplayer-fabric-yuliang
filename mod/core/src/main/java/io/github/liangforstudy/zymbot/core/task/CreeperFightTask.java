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
    /** A creeper this far off with a clear shot is shot at, with a bow and arrows. */
    public static final double BOW_RANGE = 16;
    /** Start a draw only this far off; one under way goes on to {@link #BOW_UNTIL}, then the melee weapon. */
    static final double BOW_FROM = 6.5, BOW_UNTIL = 5.5;
    /** A fully drawn arrow's speed, blocks a tick. */
    static final double ARROW_SPEED = 3;
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
    private boolean armed, bowOut, saidCornered;
    private int ticks, stalled, drawTicks, readyAfter = -1, lastHitTick = -1000;
    private double closest = Double.MAX_VALUE;
    private String failure = "";

    /**
     * Hits and run-clears on one creeper. Kept by the reflex, not the task: running clear takes us out
     * of the reflex's range, so each go back in is a new task (the first live kill logged "1 hits" for 4).
     */
    public static final class Tally {
        final UUID creeper;
        int hits, escapes, arrows;
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
            String count = (tally.arrows > 0 ? tally.arrows + (tally.arrows == 1 ? " arrow, " : " arrows, ") : "")
                    + tally.hits + (tally.hits == 1 ? " hit" : " hits") + (tally.escapes > 0 ? ", ran clear " + tally.escapes + "x" : "");
            boolean hitJustNow = ticks - lastHitTick <= KILLED_WITHIN_TICKS;   // a hit-then-run kills it while it hisses
            if (lastFuse.lit() && !hitJustNow) note.accept("the creeper went off", "gone while hissing, after " + count);
            else if (tally.hits + tally.arrows > 0) note.accept("killed the creeper", count);
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
        boolean cornered = fuse.lit() && escape == null && cornered(world, creeper.pos());
        if (cornered && !saidCornered) {
            saidCornered = true;
            note.accept("cornered by the creeper", "no open ground to run to but past it: hitting it instead");
        }
        if (!cornered && (escape != null || fuse.lit())) {
            if (escape == null) {
                release();
                if (bowOut) {                                        // a drawn bow walks at a crawl
                    hands.holdUse(false);
                    bowOut = false;
                    drawTicks = 0;
                }
                boolean bowInHand = Combat.bow(world.inventory()).stream().anyMatch(s -> s == world.selectedSlot());
                if (!bowInHand && fuse.progress() < HIT_THEN_RUN && ready(world) && d <= HIT_REACH) {
                    hit(creeper);                                    // aimed last tick; the knockback buys time too
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
        if (shoot(world, creeper, d)) return Status.RUNNING;
        if (!armed) {
            armed = true;
            Combat.bestWeapon(world.inventory()).ifPresent(hands::selectSlot);
        }
        hands.lookAt(new Vec3(creeper.pos().x(), creeper.pos().y() + 0.9, creeper.pos().z()));   // aimed every tick, so a hit lands
        boolean ready = ready(world);
        if (d < closest - 0.1) {
            closest = d;
            stalled = 0;
        } else {
            stalled++;
        }
        if (ready && d <= HIT_REACH) {
            release();
            hit(creeper);
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

    /**
     * With a bow, arrows and a clear shot, shoot it while it's at least {@link #BOW_FROM} off (a draw
     * under way goes on to {@link #BOW_UNTIL}): Power V, two full draws kill it before it's near (owner's
     * stock, 2026-11-06). Each draw is held for 20 of the server's ticks, which is longer at 9 TPS: the
     * arrow's power is counted there. Closer in, the bow is put away for the sword or axe.
     * @return true while the bow has this tick
     */
    private boolean shoot(WorldView world, EntityView creeper, double d) {
        java.util.OptionalInt bow = Combat.bow(world.inventory());
        Vec3 me = world.position();
        Vec3 body = new Vec3(creeper.pos().x(), creeper.pos().y() + 0.9, creeper.pos().z());
        boolean drawing = world.usingItem() && bow.isPresent() && world.selectedSlot() == bow.getAsInt();
        boolean shot = bow.isPresent() && Combat.arrows(world.inventory()) && d >= (drawing ? BOW_UNTIL : BOW_FROM)
                && world.clearShot(new Vec3(me.x(), me.y() + 1.62, me.z()), body);
        if (!shot) {
            if (bowOut) {
                hands.holdUse(false);                                // put it away (a weak arrow, if any)
                bowOut = false;
                drawTicks = 0;
                armed = false;                                       // back to the sword or axe
            }
            return false;
        }
        release();
        if (world.selectedSlot() != bow.getAsInt()) {
            hands.selectSlot(bow.getAsInt());
            armed = false;
            return true;
        }
        double flight = d / ARROW_SPEED;                             // ticks; it drops ~0.05 a tick more each tick
        hands.lookAt(new Vec3(body.x(), body.y() + 0.025 * flight * (flight + 1), body.z()));
        bowOut = true;
        if (!world.usingItem()) {
            hands.holdUse(true);                                     // nock
            drawTicks = 0;
        } else if (++drawTicks >= fullDraw(world.serverTps())) {
            hands.holdUse(false);                                    // loose: the next tick nocks again
            drawTicks = 0;
            tally.arrows++;
        } else {
            hands.holdUse(true);
        }
        return true;
    }

    /** Our ticks for the server to count a full draw (20 of its ticks), with 2 to spare. */
    static int fullDraw(double tps) {
        return (int) Math.ceil(20 * 20 / Math.max(5, Math.min(20, tps))) + 2;
    }

    private void hit(EntityView creeper) {
        hands.attack(creeper.id());
        tally.hits++;
        lastHitTick = ticks;
        readyAfter = -1;
    }

    /**
     * The weapon is charged as the server counts it. Our clock says so after {@code readyAfter} of our
     * ticks; the server counts its own, fewer when it lags, so wait that x 20 / its TPS. At 7 TPS a hit
     * on our clock's "ready" does about a third of its damage.
     */
    private boolean ready(WorldView world) {
        boolean ours = world.attackReady();
        if (ours && readyAfter < 0 && lastHitTick >= 0) readyAfter = ticks - lastHitTick;
        if (!ours) return false;
        if (lastHitTick < 0 || readyAfter < 0) return true;
        return ticks - lastHitTick >= readyAfter * 20 / Math.max(5, Math.min(20, world.serverTps()));
    }

    /**
     * No open ground to run to away from it: straight away, 45 and 90 degrees either side (as the
     * run-clear tries), each 2 to 4 blocks out, needs solid floor and room for feet and head. In a dead-end tunnel the run-clear went
     * back past the creeper and it went off beside us, 20 -> 14.2 (2026-11-06).
     */
    public static boolean cornered(WorldView world, Vec3 creeper) {
        Vec3 me = world.position();
        double away = Math.atan2(me.z() - creeper.z(), me.x() - creeper.x());
        int y = (int) Math.floor(me.y() + 0.01);
        for (double turn : new double[]{0, Math.PI / 4, -Math.PI / 4, Math.PI / 2, -Math.PI / 2}) {   // RetreatTask.TURNS
            boolean open = true;
            for (int step = 2; step <= 4 && open; step++) {
                int x = (int) Math.floor(me.x() + Math.cos(away + turn) * step);
                int z = (int) Math.floor(me.z() + Math.sin(away + turn) * step);
                open = world.sturdy(new BlockPos(x, y - 1, z)) && !world.sturdy(new BlockPos(x, y, z))
                        && !world.sturdy(new BlockPos(x, y + 1, z));
            }
            if (open) return false;
        }
        return true;
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
        if (bowOut) hands.holdUse(false);
        bowOut = false;
        if (escape != null) escape.cancel();
        escape = null;
    }
    @Override public String failure() { return failure; }
    @Override public String describe() { return "fighting a creeper" + (tally.hits > 0 ? " (" + tally.hits + " hits)" : ""); }
}
