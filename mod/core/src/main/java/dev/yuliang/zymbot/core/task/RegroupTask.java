package dev.yuliang.zymbot.core.task;

import dev.yuliang.zymbot.core.api.BlockPos;
import dev.yuliang.zymbot.core.api.Vec3;
import dev.yuliang.zymbot.core.api.WorldView;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Walk back to a teammate who may be moving (PHASE2.md §1). Plans a route to their newest known
 * position and plans again whenever that has moved {@link #RETARGET_BLOCKS}; while it only has an
 * announced (not seen) position it asks for a fresh one every {@link #ASK_EVERY_TICKS}. Done within
 * {@code within} blocks of them.
 */
public final class RegroupTask implements Task {
    /** Where a teammate is, and how we know: {@code live} = in sight now, otherwise announced on the bus. */
    public record Mate(String name, Vec3 pos, boolean live) {}

    static final double RETARGET_BLOCKS = 8;
    static final int ASK_EVERY_TICKS = 10 * 20;
    /** At their last announced spot with nothing newer for this long: give up. */
    static final int STALE_TICKS = 60 * 20;

    private final Supplier<Optional<Mate>> mate;
    private final Function<BlockPos, Task> route;
    private final Runnable askWhere;
    private final int within;
    private Task walk;
    private Vec3 plannedTo;
    private boolean atStaleSpot;
    private int sinceAsk = ASK_EVERY_TICKS;   // ask on the first tick
    private int stale;
    private String failure = "";
    private String name;

    /**
     * @param name     who we are going to (for the log, before the first tick)
     * @param mate     the teammate to reach, re-read every tick (newest position)
     * @param route    builds the walk to a block (the route planner, like {@code come})
     * @param askWhere asks the team for fresh positions (a WHERE on the bus)
     */
    public RegroupTask(String name, Supplier<Optional<Mate>> mate, Function<BlockPos, Task> route, Runnable askWhere, int within) {
        this.name = name;
        this.mate = mate;
        this.route = route;
        this.askWhere = askWhere;
        this.within = within;
    }

    @Override
    public Status tick(WorldView world) {
        Optional<Mate> m = mate.get();
        if (m.isEmpty()) return fail("lost track of the team — nobody in sight or announcing");
        Mate t = m.get();
        name = t.name();
        if (world.position().horizontalDistance(t.pos()) <= within) {
            if (walk != null) walk.cancel();
            return Status.DONE;
        }
        if (!t.live() && ++sinceAsk >= ASK_EVERY_TICKS) {       // an announced position ages: ask again
            sinceAsk = 0;
            askWhere.run();
        }
        boolean moved = plannedTo == null || t.pos().horizontalDistance(plannedTo) >= RETARGET_BLOCKS;
        if (moved) {
            if (walk != null) walk.cancel();
            walk = route.apply(new BlockPos((int) Math.floor(t.pos().x()), 0, (int) Math.floor(t.pos().z())));
            plannedTo = t.pos();
            atStaleSpot = false;
            stale = 0;
        }
        if (atStaleSpot) {                                      // reached where they were; wait for news
            if (++stale > STALE_TICKS) return fail("reached where " + name + " was last heard; nothing newer for "
                    + STALE_TICKS / 20 + "s");
            return Status.RUNNING;
        }
        Status s = walk.tick(world);
        if (s == Status.DONE) {                                 // at the spot, but they've moved on
            atStaleSpot = true;
            return Status.RUNNING;
        }
        if (s == Status.FAILED) return fail(walk.failure());
        return Status.RUNNING;
    }

    private Status fail(String why) {
        failure = why;
        return Status.FAILED;
    }

    @Override public void cancel() { if (walk != null) walk.cancel(); }
    @Override public String failure() { return failure; }

    @Override
    public String describe() {
        return "regrouping with " + name + (plannedTo == null ? "" : " at " + Math.round(plannedTo.x()) + " " + Math.round(plannedTo.z()));
    }
}
