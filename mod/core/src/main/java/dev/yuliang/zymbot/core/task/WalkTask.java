package dev.yuliang.zymbot.core.task;

import dev.yuliang.zymbot.core.api.BlockPos;
import dev.yuliang.zymbot.core.api.PathProvider;
import dev.yuliang.zymbot.core.api.WorldView;
import java.util.function.Consumer;

/**
 * Walk to a block (or a column, with {@code ignoreY}). Stays out of water first; if there's no dry
 * path it tries again allowing water, and reports that it's swimming (PHASE1.md → water). Done on
 * arrival; fails with a reason if there's no pathfinder, no path even swimming, or no progress
 * for {@link #STUCK_TICKS}.
 */
public final class WalkTask implements Task {
    /** Baritone needs a moment to plan before "not busy" means "gave up". */
    static final int PLANNING_GRACE_TICKS = 40;
    public static final int STUCK_TICKS = 30 * 20;

    private final BlockPos target;
    private final boolean ignoreY;
    private final int within;
    private final PathProvider paths;
    private final Consumer<String> report;
    private boolean started;
    private boolean swimming;
    private int ticks;
    private double best = Double.MAX_VALUE;
    private int sinceProgress;
    private String failure = "";

    public WalkTask(PathProvider paths, BlockPos target, boolean ignoreY, int within) {
        this(paths, target, ignoreY, within, why -> {});
    }

    /** @param report told why, whenever the walk falls back to swimming */
    public WalkTask(PathProvider paths, BlockPos target, boolean ignoreY, int within, Consumer<String> report) {
        this.paths = paths;
        this.report = report;
        this.target = target;
        this.ignoreY = ignoreY;
        this.within = Math.max(0, within);
    }

    @Override
    public Status tick(WorldView world) {
        if (!paths.available()) return fail("no pathfinder: " + paths.name());
        double d = distance(world);
        if (d <= within) {
            paths.stop();
            return Status.DONE;
        }
        if (!started) {
            started = true;
            if (world.inWater()) {                       // can't plan a dry path out of the water
                swimming = true;
                report.accept("already in the water");
            }
            paths.goTo(target, ignoreY, within, swimming);
            return Status.RUNNING;
        }
        ticks++;
        if (d < best - 1) {
            best = d;
            sinceProgress = 0;
        } else if (++sinceProgress > STUCK_TICKS) {
            paths.stop();
            return fail("stuck — no progress for " + STUCK_TICKS / 20 + "s, " + Math.round(d) + " blocks short");
        }
        if (ticks > PLANNING_GRACE_TICKS && !paths.busy()) {
            if (!swimming) {                              // last resort: allow water, and say so
                swimming = true;
                report.accept("no dry path to " + where() + " (" + Math.round(d) + " blocks left)");
                ticks = 0;
                paths.goTo(target, ignoreY, within, true);
                return Status.RUNNING;
            }
            return fail("no path, even swimming — the pathfinder gave up " + Math.round(d) + " blocks short");
        }
        return Status.RUNNING;
    }

    /** Block distance, the way the pathfinder's goal counts it. */
    private double distance(WorldView world) {
        BlockPos at = BlockPos.of(world.position());
        double dx = at.x() - target.x(), dz = at.z() - target.z(), dy = ignoreY ? 0 : at.y() - target.y();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private Status fail(String why) {
        failure = why;
        return Status.FAILED;
    }

    @Override public void cancel() { if (started) paths.stop(); }
    @Override public String failure() { return failure; }

    /** Allowed to swim now (fell back, or started in the water). */
    public boolean swimming() { return swimming; }

    private String where() {
        return target.x() + (ignoreY ? "" : " " + target.y()) + " " + target.z();
    }

    @Override
    public String describe() {
        return (swimming ? "walking (swimming if needed) to " : "walking to ") + where();
    }
}
