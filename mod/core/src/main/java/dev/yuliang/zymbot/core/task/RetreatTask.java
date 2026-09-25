package dev.yuliang.zymbot.core.task;

import dev.yuliang.zymbot.core.api.BlockPos;
import dev.yuliang.zymbot.core.api.PathProvider;
import dev.yuliang.zymbot.core.api.Vec3;
import dev.yuliang.zymbot.core.api.WorldView;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Break contact: walk {@link #DISTANCE} blocks away from what hurt us — bent toward the nearest
 * human when there is one, so help is closer. Done once that far from the attacker. The attacker
 * is followed live: when it has moved {@link #REPLAN_BLOCKS} from where we planned, plan again.
 */
public final class RetreatTask implements Task {
    public static final int DISTANCE = 16;
    static final int TIMEOUT_TICKS = 20 * 20;
    static final double REPLAN_BLOCKS = 6;

    private final PathProvider paths;
    private final Supplier<Vec3> threat;
    private Vec3 from;          // the attacker now
    private Vec3 plannedFrom;   // ... when the current walk was planned
    private final Optional<Vec3> toward;
    private final Consumer<String> report;
    private WalkTask walk;
    private int ticks;
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
        if (me.horizontalDistance(now) >= DISTANCE) {
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
            plannedFrom = now;
        }
        if (++ticks > TIMEOUT_TICKS) {
            walk.cancel();
            failure = "still within " + DISTANCE + " blocks after " + TIMEOUT_TICKS / 20 + "s";
            return Status.FAILED;
        }
        Status s = walk.tick(world);
        if (s == Status.FAILED) failure = walk.failure();
        return s;
    }

    /** Away from the attacker; if a human is around, halfway between "away" and "to them". */
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
        int reach = DISTANCE + 4;
        return new BlockPos((int) Math.floor(me.x() + ax * reach), (int) Math.floor(me.y()), (int) Math.floor(me.z() + az * reach));
    }

    @Override public void cancel() { if (walk != null) walk.cancel(); }
    @Override public String failure() { return failure; }
    @Override public String describe() { return "retreating from " + Math.round(from.x()) + ", " + Math.round(from.z()); }
}
