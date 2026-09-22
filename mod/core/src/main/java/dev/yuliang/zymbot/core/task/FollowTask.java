package dev.yuliang.zymbot.core.task;

import dev.yuliang.zymbot.core.api.PathProvider;
import dev.yuliang.zymbot.core.api.EntityView;
import dev.yuliang.zymbot.core.api.WorldView;
import java.util.function.Consumer;

/**
 * Stay near a player until cancelled, keeping out of water. If they're across water (visible, far,
 * and the gap isn't closing for {@link #STALLED_TICKS}), swim after them and report it; back to
 * dry once close again. Fails if they're out of sight for {@link #LOST_TICKS}.
 */
public final class FollowTask implements Task {
    public static final int LOST_TICKS = 30 * 20;
    public static final int STALLED_TICKS = 10 * 20;
    /** Further than this and not getting closer: stalled. */
    static final double FAR = 8;
    /** Back on dry-only once this close. */
    static final double CLOSE = 4;

    private final PathProvider paths;
    private final String name;
    private final Consumer<String> report;
    private final Consumer<String> dryAgain;
    private boolean started;
    private boolean swimming;
    private int unseen;
    private int stalled;
    private double best = Double.MAX_VALUE;
    private String failure = "";

    public FollowTask(PathProvider paths, String name) {
        this(paths, name, why -> {}, why -> {});
    }

    /** @param report told why it starts swimming; {@code dryAgain}, why it stops */
    public FollowTask(PathProvider paths, String name, Consumer<String> report, Consumer<String> dryAgain) {
        this.paths = paths;
        this.name = name;
        this.report = report;
        this.dryAgain = dryAgain;
    }

    @Override
    public Status tick(WorldView world) {
        if (!paths.available()) {
            failure = "no pathfinder: " + paths.name();
            return Status.FAILED;
        }
        if (!started) {
            started = true;
            paths.follow(name, false);
        }
        var target = world.player(name);
        if (target.isPresent()) {
            unseen = 0;
            watchGap(world, target.get());
        } else if (++unseen > LOST_TICKS) {
            paths.stop();
            failure = "lost sight of " + name + " for " + LOST_TICKS / 20 + "s";
            return Status.FAILED;
        }
        return Status.RUNNING;
    }

    private void watchGap(WorldView world, EntityView target) {
        double d = target.pos().horizontalDistance(world.position());
        if (swimming) {
            if (d <= CLOSE && !world.inWater()) {         // across: dry again
                swimming = false;
                paths.follow(name, false);
                dryAgain.accept("out of the water, " + Math.round(d) + " blocks from " + name);
            }
            return;
        }
        if (d <= FAR || d < best - 1) {
            best = d;
            stalled = 0;
        } else if (++stalled > STALLED_TICKS) {
            swimming = true;
            best = Double.MAX_VALUE;
            stalled = 0;
            report.accept("no dry way to " + name + " (" + Math.round(d) + " blocks, not getting closer)");
            paths.follow(name, true);
        }
    }

    @Override public void cancel() { if (started) paths.stop(); }
    @Override public String failure() { return failure; }
    @Override public String describe() { return "following " + name + (swimming ? " (swimming if needed)" : ""); }
}
