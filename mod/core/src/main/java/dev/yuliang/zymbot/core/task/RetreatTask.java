package dev.yuliang.zymbot.core.task;

import dev.yuliang.zymbot.core.api.BlockPos;
import dev.yuliang.zymbot.core.api.PathProvider;
import dev.yuliang.zymbot.core.api.Vec3;
import dev.yuliang.zymbot.core.api.WorldView;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Break contact: run from what hurt us — bent toward the nearest human when there is one, so help
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

    private final PathProvider paths;
    private final Supplier<Vec3> threat;
    private Vec3 from;          // the attacker now
    private Vec3 plannedFrom;   // ... when the current walk was planned
    private final Optional<Vec3> toward;
    private final Consumer<String> report;
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
            walk = new WalkTask(paths, destination(me), true, 2, report).sprinting();   // a walker can't outpace a zombie
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
    BlockPos destination(Vec3 me) {
        double ax = me.x() - from.x(), az = me.z() - from.z();
        double len = Math.hypot(ax, az);
        if (len < 0.01) { ax = 1; az = 0; len = 1; }
        ax /= len;
        az /= len;
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

    @Override public void cancel() { if (walk != null) walk.cancel(); }
    @Override public String failure() { return failure; }
    @Override public String describe() {
        return "retreating from " + Math.round(from.x()) + ", " + Math.round(from.z())
                + (walk == null ? "" : " — " + walk.describe());
    }
}
