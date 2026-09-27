package dev.yuliang.zymbot.core.task;

import dev.yuliang.zymbot.core.api.BlockPos;
import dev.yuliang.zymbot.core.api.Vec3;
import dev.yuliang.zymbot.core.api.WorldView;
import java.util.Optional;
import java.util.function.Function;

/**
 * Walk back to a teammate who may be moving (PHASE2.md §1). Plans a route to their newest known
 * position and plans again whenever that has moved {@link #RETARGET_BLOCKS}; while it only has an
 * announced (not seen) position it asks for a fresh one every {@link #ASK_EVERY_TICKS}. Done within
 * {@code within} blocks of them in sight — reaching an announced spot alone is not being with them
 * (Bot1 "arrived" at a silent teammate's old spot where it already stood, 2026-09-26).
 */
public final class RegroupTask implements Task {
    /**
     * Where a teammate is, and how we know: {@code live} = in sight now, otherwise announced on the bus.
     * {@code heardAt} = when we last heard from them (clock millis): a newer answer is news even when the
     * position is the same — a teammate standing still (2026-09-26).
     */
    public record Mate(String name, Vec3 pos, boolean live, long heardAt) {}

    static final double RETARGET_BLOCKS = 8;
    static final int ASK_EVERY_TICKS = 10 * 20;
    /** At their last announced spot with no word from them at all for this long: give up. */
    static final int STALE_TICKS = 60 * 20;

    private final Function<WorldView, Optional<Mate>> mate;
    private final Function<BlockPos, Task> route;
    private final Runnable askWhere;
    private final int within;
    private Task walk;
    private Vec3 plannedTo;
    private boolean atStaleSpot;
    private int sinceAsk = ASK_EVERY_TICKS;   // ask on the first tick
    private int stale;
    private long lastHeard = Long.MIN_VALUE;
    private String failure = "";
    private String name;
    private boolean inSight;                                    // how we last knew where they are

    /**
     * @param name     who we are going to (for the log, before the first tick)
     * @param mate     the teammate to reach, re-read every tick from that tick's world (newest position)
     * @param route    builds the walk to a block (the route planner, like {@code come})
     * @param askWhere asks the team for fresh positions (a WHERE on the bus)
     */
    public RegroupTask(String name, Function<WorldView, Optional<Mate>> mate, Function<BlockPos, Task> route, Runnable askWhere, int within) {
        this.name = name;
        this.mate = mate;
        this.route = route;
        this.askWhere = askWhere;
        this.within = within;
    }

    @Override
    public Status tick(WorldView world) {
        Optional<Mate> m = mate.apply(world);
        if (m.isEmpty()) return fail("lost track of the team — nobody in sight or announcing");
        Mate t = m.get();
        name = t.name();
        inSight = t.live();
        if (t.live() && world.position().horizontalDistance(t.pos()) <= within) {
            if (walk != null) walk.cancel();
            return Status.DONE;
        }                                                       // at a bus spot only: not with them — wait for news below
        if (t.live() || t.heardAt() > lastHeard) {              // in sight, or any fresh answer (same spot or not): news
            lastHeard = Math.max(lastHeard, t.heardAt());
            stale = 0;
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
        }
        if (atStaleSpot) {                                      // reached where they were; wait for news
            if (++stale > STALE_TICKS) return fail("reached where " + name + " was last heard; no word from them for "
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
        return "regrouping with " + name + (plannedTo == null ? "" : " now at " + Math.round(plannedTo.x()) + " " + Math.round(plannedTo.z())
                + (inSight ? " (in sight)" : " (bus)"));
    }
}
