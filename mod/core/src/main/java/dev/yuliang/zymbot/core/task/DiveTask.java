package dev.yuliang.zymbot.core.task;

import dev.yuliang.zymbot.core.api.BlockPos;
import dev.yuliang.zymbot.core.api.Hands;
import dev.yuliang.zymbot.core.api.Vec3;
import dev.yuliang.zymbot.core.api.WorldView;

/**
 * Down to a block under water until it's within reach: swim over it, then sneak (sink) straight
 * down. Neither Zymbot's route planner nor Baritone goes under water; a grave 6 blocks down at
 * 79 56 -48 was "no path, even swimming" (owner, 2026-09-30). Gives up before the drowning reflex
 * would fire, so that reflex takes over cleanly and brings it up.
 */
public final class DiveTask implements Task {
    /** Close enough to click the block (survival reach is 4.5). */
    public static final double REACH = 3.5;
    /** Directly above: stop swimming forward and just sink. */
    static final double OVER = 0.8;
    /** Give up with this much air left; DrowningInterrupt acts at 2/3. */
    static final double AIR_FLOOR = 0.7;
    static final int TIMEOUT_TICKS = 20 * 15;

    private final Hands hands;
    private final BlockPos target;
    private int ticks;
    private String failure = "";

    public DiveTask(Hands hands, BlockPos target) {
        this.hands = hands;
        this.target = target;
    }

    /** Within {@link #REACH} of the block's centre. */
    public static boolean inReach(WorldView world, BlockPos block) {
        Vec3 me = world.position();
        return Math.sqrt(Math.pow(me.x() - (block.x() + 0.5), 2) + Math.pow(me.y() - (block.y() + 0.5), 2)
                + Math.pow(me.z() - (block.z() + 0.5), 2)) <= REACH;
    }

    @Override
    public Status tick(WorldView world) {
        if (ticks == 0) hands.paths().stop();
        if (inReach(world, target)) return release(Status.DONE);
        if (++ticks > TIMEOUT_TICKS) return fail("didn't get down to " + where() + " in " + TIMEOUT_TICKS / 20 + " s");
        if (world.air() < world.maxAir() * AIR_FLOOR) return fail("running out of air above " + where());
        Vec3 center = new Vec3(target.x() + 0.5, target.y() + 0.5, target.z() + 0.5);
        boolean over = center.horizontalDistance(world.position()) < OVER;
        hands.steerToward(center, 10);
        hands.holdKeys(!over, false);
        hands.holdSneak(true);                                  // sneak in water = sink faster
        return Status.RUNNING;
    }

    private Status release(Status s) {
        hands.holdKeys(false, false);
        hands.holdSneak(false);
        return s;
    }

    private Status fail(String why) {
        failure = why;
        return release(Status.FAILED);
    }

    private String where() { return target.x() + " " + target.y() + " " + target.z(); }

    @Override public void cancel() { release(Status.DONE); }
    @Override public String failure() { return failure; }
    @Override public String describe() { return "diving to " + where(); }
}
