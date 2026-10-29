package io.github.liangforstudy.zymbot.core.task;

import io.github.liangforstudy.zymbot.core.api.BlockPos;
import io.github.liangforstudy.zymbot.core.api.EntityView;
import io.github.liangforstudy.zymbot.core.api.PathProvider;
import io.github.liangforstudy.zymbot.core.api.Vec3;
import io.github.liangforstudy.zymbot.core.api.WorldView;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Break contact: run from what hurt us, and from every other hostile near (closer ones count more) — bent toward the nearest human when there is one, so help
 * is closer. Starts when the attacker is within {@link #DISTANCE}, done only at {@link #CLEAR}: with
 * one threshold for both, a zombie chasing at our speed kept us on the line, and the retreat ended
 * and restarted ~60 times in 36 s, replanning each time (2026-09-26, ~2 blocks/s instead of a
 * sprint). The attacker is followed live: when it has moved {@link #REPLAN_BLOCKS} from where we
 * planned, plan again.
 * <p>Dry first, in every direction: straight away, then {@link #TURNS} to either side, and only then
 * swim. A staircase of 1-4 block ledges down a cliff is a "valid" path for Baritone, and twice the
 * zombie chased Bot1 down one into a lake (2026-09-26).
 */
public final class RetreatTask implements Task {
    public static final int DISTANCE = 16;
    /** Done this far from the attacker — twice where the retreat starts. */
    public static final int CLEAR = 32;
    /** Gives up only after this long without getting further from the attacker (swimming is slow). */
    static final int NO_GAIN_TICKS = 20 * 20;
    static final double REPLAN_BLOCKS = 6;
    /** Directions to try, in degrees off "straight away", before swimming. */
    static final int[] TURNS = {0, 45, -45, 90, -90};
    /**
     * Every hostile this close bends the way out too, the nearer the harder: away from the one that
     * hit us alone, it ran into skeletons and a group of zombified piglins (Nether test, 2026-10-29).
     */
    static final double AVOID_BLOCKS = 24;
    /** How much more the attacker counts than any other mob. */
    static final double ATTACKER_WEIGHT = 2;

    private final PathProvider paths;
    private final Supplier<Vec3> threat;
    private Vec3 from;          // the attacker now
    private Vec3 plannedFrom;   // ... when the current walk was planned
    private final Optional<Vec3> toward;
    private final Consumer<String> report;
    private List<Vec3> others = List.of();   // the other hostiles, when the current walk was planned
    private WalkTask walk;
    private int turn;           // index into TURNS; TURNS.length = swim allowed
    private double bestGap;     // furthest we've been from the attacker
    private int sinceGain;
    private String failure = "";

    public RetreatTask(PathProvider paths, Vec3 from, Optional<Vec3> toward, Consumer<String> report) {
        this(paths, () -> from, toward, report);
    }

    /** @param threat where the attacker is now (its last known position once out of sight) */
    public RetreatTask(PathProvider paths, Supplier<Vec3> threat, Optional<Vec3> toward, Consumer<String> report) {
        this.paths = paths;
        this.threat = threat;
        this.from = threat.get();
        this.toward = toward;
        this.report = report;
    }

    @Override
    public Status tick(WorldView world) {
        Vec3 me = world.position();
        Vec3 now = threat.get();
        if (me.horizontalDistance(now) >= CLEAR) {
            if (walk != null) walk.cancel();
            return Status.DONE;
        }
        from = now;
        if (walk != null && now.horizontalDistance(plannedFrom) >= REPLAN_BLOCKS) {   // it followed us: run from where it is
            walk.cancel();
            walk = null;
        }
        if (walk == null) {
            others = world.nearby().stream()
                    .filter(e -> e.kind() == EntityView.Kind.HOSTILE)
                    .map(EntityView::pos)
                    .filter(p -> distance(p, me) <= AVOID_BLOCKS && p.horizontalDistance(now) > 1)   // not the attacker
                    .toList();
            walk = new WalkTask(paths, destination(world), true, 2, report).sprinting();   // a walker can't outpace a zombie
            if (turn < TURNS.length) walk.dryOnly();
            plannedFrom = now;
        }
        double gap = me.horizontalDistance(now);
        if (gap > bestGap + 1) {
            bestGap = gap;
            sinceGain = 0;
        } else if (++sinceGain > NO_GAIN_TICKS) {
            walk.cancel();
            failure = "no further from the attacker for " + NO_GAIN_TICKS / 20 + "s (" + Math.round(gap) + " blocks)";
            return Status.FAILED;
        }
        Status s = walk.tick(world);
        if (s == Status.FAILED && turn < TURNS.length && walk.failure().startsWith(WalkTask.NO_DRY_PATH)) {
            turn++;                                             // that way is wet: try the next direction
            walk = null;
            return Status.RUNNING;
        }
        if (s == Status.FAILED) failure = walk.failure();
        return s;
    }

    /**
     * Away from the attacker; if a human is around, halfway between "away" and "to them". Turned by
     * the current {@link #TURNS} entry when straight away had no dry path.
     */
    BlockPos destination(WorldView world) {
        Vec3 me = world.position();
        double[] d = wayOut(world);
        double ax = d[0], az = d[1];
        if (toward.isPresent()) {
            double hx = toward.get().x() - me.x(), hz = toward.get().z() - me.z();
            double hl = Math.hypot(hx, hz);
            if (hl > 0.01) {
                double bx = ax + hx / hl, bz = az + hz / hl;   // the human is behind the attacker:
                double l = Math.hypot(bx, bz);                  // blending cancels out, so just flee
                if (l > 0.01) { ax = bx / l; az = bz / l; }
            }
        }
        if (turn > 0 && turn < TURNS.length) {
            double r = Math.toRadians(TURNS[turn]), c = Math.cos(r), s = Math.sin(r);
            double rx = ax * c - az * s, rz = ax * s + az * c;
            ax = rx;
            az = rz;
        }
        int reach = CLEAR + 8;
        return new BlockPos((int) Math.floor(me.x() + ax * reach), (int) Math.floor(me.y()), (int) Math.floor(me.z() + az * reach));
    }

    /**
     * The direction out, of {@link #DIRECTIONS} around: heading toward any hostile costs (the nearer and
     * the more head-on, the more; the attacker {@link #ATTACKER_WEIGHT} times as much), heading away
     * from the attacker gains a little. Adding "away" pushes instead ran straight at a mob ahead,
     * whose push only shortened the same line.
     */
    double[] wayOut(WorldView world) {
        Vec3 me = world.position();
        double fx = me.x() - from.x(), fz = me.z() - from.z(), fl = Math.hypot(fx, fz);
        if (fl < 0.01) { fx = 1; fz = 0; fl = 1; }
        fx /= fl;
        fz /= fl;
        double best = Double.MAX_VALUE, bx = fx, bz = fz;
        for (int i = 0; i < DIRECTIONS; i++) {
            double a = 2 * Math.PI * i / DIRECTIONS, dx = Math.cos(a), dz = Math.sin(a);
            double cost = -AWAY_BONUS * (dx * fx + dz * fz) + toward(dx, dz, me, from, ATTACKER_WEIGHT);
            for (Vec3 o : others) cost += toward(dx, dz, me, o, 1);
            cost += terrain(world, me, dx, dz);
            if (cost < best - 1e-9) { best = cost; bx = dx; bz = dz; }
        }
        return new double[]{bx, bz};
    }

    static final int DIRECTIONS = 16;
    /** Where each direction is tried for footing, in blocks out. */
    static final int[] SAMPLES = {3, 6, 9, 12};
    static final int STEP_UP_DOWN = 3;
    /** A direction walled off (a cliff face, a Nether wall) or dropping away at the first sample costs this. */
    static final double BLOCKED = 3;
    static final double LAVA = 6;

    /**
     * What the ground costs that way: nowhere to stand within {@link #STEP_UP_DOWN} up or down at a
     * sample (a wall, a cliff, a drop) costs {@link #BLOCKED}, less the further out it comes; lava
     * underfoot anywhere along it costs {@link #LAVA}. Baritone finds the way round, but only to a
     * goal we chose: in the Nether a straight line runs into walls and over lava lakes.
     */
    private static double terrain(WorldView world, Vec3 me, double dx, double dz) {
        int my = (int) Math.floor(me.y());
        for (int i = 0; i < SAMPLES.length; i++) {
            int x = (int) Math.floor(me.x() + dx * SAMPLES[i]), z = (int) Math.floor(me.z() + dz * SAMPLES[i]);
            boolean footing = false;
            for (int k = 0; k <= 2 * STEP_UP_DOWN && !footing; k++) {
                int y = my + (k % 2 == 0 ? k / 2 : -(k + 1) / 2);   // 0, -1, +1, -2, +2 ...: nearest height first
                BlockPos feet = new BlockPos(x, y, z), below = new BlockPos(x, y - 1, z);
                if (world.blockAt(below).contains("lava") || world.blockAt(feet).contains("lava")) return LAVA;
                footing = world.sturdy(below) && world.replaceable(feet) && world.replaceable(new BlockPos(x, y + 1, z));
            }
            if (!footing) return BLOCKED * (SAMPLES.length - i) / SAMPLES.length;
        }
        return 0;
    }
    static final double AWAY_BONUS = 0.5;

    /** How much heading (dx, dz) runs at the mob at {@code o}: head-on and near costs most. */
    private static double toward(double dx, double dz, Vec3 me, Vec3 o, double weight) {
        double ox = o.x() - me.x(), oz = o.z() - me.z(), ol = Math.hypot(ox, oz);
        if (ol < 0.01 || ol > AVOID_BLOCKS) return 0;
        double cos = (dx * ox + dz * oz) / ol;
        if (cos <= 0) return 0;
        return weight * (1 - ol / AVOID_BLOCKS) * cos * cos * 2;
    }

    private static double distance(Vec3 a, Vec3 b) {
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Override public void cancel() { if (walk != null) walk.cancel(); }
    @Override public String failure() { return failure; }
    @Override public String describe() {
        return "retreating from " + Math.round(from.x()) + ", " + Math.round(from.z())
                + (walk == null ? "" : " — " + walk.describe());
    }
}
