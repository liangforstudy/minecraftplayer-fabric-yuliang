package dev.yuliang.zymbot.core.task;

import dev.yuliang.zymbot.core.api.BlockPos;
import dev.yuliang.zymbot.core.api.Hands;
import dev.yuliang.zymbot.core.api.WorldView;
import dev.yuliang.zymbot.core.craft.Recipe;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * Punch {@code count} logs out of one trunk, lowest first, and only then pick up what fell — one
 * sweep around the tree (owner, 2026-10-14). Walking over each log's drop straight after breaking it
 * got the bot nowhere: the drops of a tree land all around it (and the pack fells the rest of the
 * trunk on its own sometimes), so a 5 s chase per log came back with "picked up nothing" each time.
 * A log that can't be reached ends the punching early; whatever was broken is still swept up.
 */
public final class ChopTask implements Task {
    /** How far up the trunk from its base the next log is looked for. */
    public static final int TRUNK_LOOK = 6;
    /** Drops this far (flat) from the trunk are the tree's. */
    static final double SWEEP_RADIUS = 10;

    private final Hands hands;
    private final BlockPos base;
    private final int count;
    private final String why;
    private final BiConsumer<String, String> log;
    private Map<String, Integer> before;
    private BreakTask breaking;
    private SweepTask sweep;
    private int broken;
    private String treeId;
    private String failure = "";

    public ChopTask(Hands hands, BlockPos base, int count, String why, BiConsumer<String, String> log) {
        this.hands = hands;
        this.base = base;
        this.count = Math.max(1, count);
        this.why = why;
        this.log = log;
    }

    @Override
    public Status tick(WorldView world) {
        if (before == null) before = SweepTask.counts(world);
        if (sweep != null) return sweep.tick(world);
        if (breaking == null) {
            Optional<BlockPos> next = broken >= count ? Optional.empty() : lowestLog(world, base);
            if (next.isEmpty()) {
                if (broken == 0) return fail("no log left in the trunk at " + base.x() + " " + base.z());
                return startSweep(world);
            }
            if (treeId == null) treeId = Recipe.shortName(world.blockAt(next.get()));
            breaking = new BreakTask(hands, next.get(), why, log).withoutCollect();
        }
        Status s = breaking.tick(world);
        if (s == Status.DONE) {
            broken++;
            breaking = null;
        } else if (s == Status.FAILED) {
            String why = breaking.failure();
            breaking = null;
            if (broken == 0) return fail(why);
            log.accept("stopped punching after " + broken + " log" + (broken == 1 ? "" : "s"), why);
            return startSweep(world);
        }
        return Status.RUNNING;
    }

    private Status startSweep(WorldView world) {
        sweep = new SweepTask(hands, base, SWEEP_RADIUS, before, "the " + treeId + " tree at " + base.x() + " " + base.z(), log);
        return sweep.tick(world);
    }

    /** The lowest log still standing in the trunk's column, from its base up. */
    public static Optional<BlockPos> lowestLog(WorldView world, BlockPos base) {
        for (int dy = 0; dy <= TRUNK_LOOK; dy++) {
            BlockPos p = new BlockPos(base.x(), base.y() + dy, base.z());
            if (Recipe.LOG.test(world.blockAt(p))) return Optional.of(p);
        }
        return Optional.empty();
    }

    private Status fail(String why) {
        failure = why;
        return Status.FAILED;
    }

    @Override
    public void cancel() {
        if (breaking != null) breaking.cancel();
        if (sweep != null) sweep.cancel();
    }

    @Override public String failure() { return failure; }

    @Override
    public String describe() {
        if (sweep != null) return sweep.describe();
        return breaking != null ? breaking.describe() + " (log " + (broken + 1) + " of " + count + ")"
                : "chopping the tree at " + base.x() + " " + base.z();
    }
}
