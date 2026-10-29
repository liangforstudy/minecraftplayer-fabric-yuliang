package io.github.liangforstudy.zymbot.core.task;

import io.github.liangforstudy.zymbot.core.api.BlockPos;
import io.github.liangforstudy.zymbot.core.api.EntityView;
import io.github.liangforstudy.zymbot.core.api.Hands;
import io.github.liangforstudy.zymbot.core.api.PathProvider;
import io.github.liangforstudy.zymbot.core.api.Vec3;
import io.github.liangforstudy.zymbot.core.api.WorldView;
import io.github.liangforstudy.zymbot.core.body.Combat;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Break line of sight with whatever shoots at us: walk to the nearest spot within {@link #RADIUS} where
 * a block stands between every shooter's eyes and ours, and wait there. Running doesn't shake a
 * skeleton or a ghast — they shot Bluetails all the way, and two ghasts killed it in 10 s out in the
 * open (Nether test, 2026-10-29). Done when no shooter is left in range of us; failed
 * when there's no cover in reach (the reflex then backs off instead). A shooter that walks round the
 * cover into reach is hit: skeletons came up to 5 blocks round a wall (Nether, 2026-10-29).
 */
public final class CoverTask implements Task {
    static final int RADIUS = 10;
    static final int UP_DOWN = 3;
    /** Look again this often, as shooters move (a ghast drifts round the corner). */
    static final int RECHECK_TICKS = 20;
    static final int GIVE_UP_TICKS = 90 * 20;
    static final double EYES = 1.62;
    /**
     * In sight this long in all (seconds of walking from spot to spot as the shooter moves): give up
     * on cover and dig in instead. A drifting ghast kept Bluetails walking for 30 s, 20 → 7 (2026-10-29).
     */
    static final int EXPOSED_TICKS = 8 * 20;
    private int exposed;

    private final Hands hands;
    private final PathProvider paths;
    private final Function<WorldView, List<EntityView>> shooters;
    private boolean armed;
    private final Consumer<String> report;
    private WalkTask walk;
    private BlockPos spot;
    private int ticks;
    private String failure = "";

    /** @param shooters what's shooting at us now (Combat.shooters) */
    public CoverTask(Hands hands, Function<WorldView, List<EntityView>> shooters, Consumer<String> report) {
        this.hands = hands;
        this.paths = hands.paths();
        this.shooters = shooters;
        this.report = report;
    }

    @Override
    public Status tick(WorldView world) {
        List<EntityView> now = shooters.apply(world);
        if (now.isEmpty()) {
            cancel();
            return Status.DONE;
        }
        if (++ticks > GIVE_UP_TICKS) {
            cancel();
            return Status.DONE;                              // the reflex looks again at the next hit
        }
        Optional<EntityView> close = now.stream().filter(e -> inReach(world.position(), e.pos())).findFirst();
        if (close.isPresent()) {                             // it walked round the cover: up close it's just weak
            cancel();
            if (!armed) {
                armed = true;
                Combat.bestWeapon(world.inventory()).ifPresent(hands::selectSlot);
            }
            Vec3 p = close.get().pos();
            hands.lookAt(new Vec3(p.x(), p.y() + 0.9, p.z()));
            if (world.attackReady()) hands.attack(close.get().id());
            return Status.RUNNING;
        }
        boolean seen = !hidden(world, eyes(world.position()), now);
        if (seen && ++exposed > EXPOSED_TICKS) {
            cancel();
            failure = "in sight for " + EXPOSED_TICKS / 20 + " s, cover keeps failing";
            return Status.FAILED;
        }
        if (walk == null || ticks % RECHECK_TICKS == 0) {
            if (!seen) {                                     // already out of sight: stay put
                cancel();
                return Status.RUNNING;
            }
            Optional<BlockPos> best = find(world, now);
            if (best.isEmpty()) {
                cancel();
                failure = "no cover within " + RADIUS + " blocks";
                return Status.FAILED;
            }
            if (walk == null || !best.get().equals(spot)) {
                cancel();
                spot = best.get();
                walk = new WalkTask(paths, spot, false, 0, report).sprinting().dryOnly();   // never through lava or a lake
            }
        }
        if (walk == null) return Status.RUNNING;
        Status s = walk.tick(world);
        if (s == Status.FAILED) {
            failure = "can't get to cover at " + spot + ": " + walk.failure();
            walk = null;
            return Status.FAILED;
        }
        if (s == Status.DONE) walk = null;                   // there: wait, and look again
        return Status.RUNNING;
    }

    /** The nearest standing spot no shooter can see, by walking distance as the crow flies. */
    Optional<BlockPos> find(WorldView world, List<EntityView> now) {
        Vec3 me = world.position();
        int mx = (int) Math.floor(me.x()), my = (int) Math.floor(me.y()), mz = (int) Math.floor(me.z());
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                double flat = Math.hypot(dx, dz);
                if (flat > RADIUS || flat >= bestD) continue;
                for (int dy = -UP_DOWN; dy <= UP_DOWN; dy++) {
                    BlockPos feet = new BlockPos(mx + dx, my + dy, mz + dz);
                    if (!standable(world, feet)) continue;
                    double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (d >= bestD) continue;
                    if (!hidden(world, new Vec3(feet.x() + 0.5, feet.y() + EYES, feet.z() + 0.5), now)) continue;
                    best = feet;
                    bestD = d;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean standable(WorldView world, BlockPos feet) {
        return world.sturdy(new BlockPos(feet.x(), feet.y() - 1, feet.z()))
                && world.replaceable(feet) && world.replaceable(new BlockPos(feet.x(), feet.y() + 1, feet.z()));
    }

    private static boolean hidden(WorldView world, Vec3 eyes, List<EntityView> shooters) {
        return shooters.stream().noneMatch(s -> world.clearShot(shooterEyes(s), eyes));
    }

    private static Vec3 shooterEyes(EntityView s) {
        double up = s.type() != null && s.type().endsWith("ghast") ? 2.6 : EYES;   // a ghast shoots from its middle
        return new Vec3(s.pos().x(), s.pos().y() + up, s.pos().z());
    }

    private static boolean inReach(Vec3 me, Vec3 mob) {
        return mob.horizontalDistance(me) <= FightTask.REACH && Math.abs(mob.y() - me.y()) <= FightTask.REACH_UP_DOWN;
    }

    private static Vec3 eyes(Vec3 feet) { return new Vec3(feet.x(), feet.y() + EYES, feet.z()); }

    @Override public void cancel() { if (walk != null) walk.cancel(); walk = null; }
    @Override public String failure() { return failure; }
    @Override public String describe() {
        return spot == null ? "looking for cover" : "taking cover at " + spot.x() + " " + spot.y() + " " + spot.z();
    }
}
