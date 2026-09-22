package dev.yuliang.zymbot.core.task;

import dev.yuliang.zymbot.core.api.BlockPos;
import dev.yuliang.zymbot.core.api.Hands;
import dev.yuliang.zymbot.core.api.Vec3;
import dev.yuliang.zymbot.core.api.WorldView;

/**
 * Get out of the water, now: stop the pathfinder, hold jump (a player who presses nothing sinks),
 * and swim straight for the nearest dry land. With no land in reach, tread water until the air is
 * full. Never fails — a drowning reflex that gives up is worse than one that tries again — it
 * just ends, and the interrupt fires again if the air is still low.
 */
public final class SurfaceTask implements Task {
    public static final int LAND_RADIUS = 24;
    static final int TIMEOUT_TICKS = 20 * 20;
    /** Close enough to swim straight at the shore; within this, the mouse is left alone. */
    static final double STEER_TOLERANCE_DEGREES = 30;

    private final Hands hands;
    private final BlockPos land;
    private boolean started;
    private int ticks;

    /** @param land where to swim, from {@link WorldView#nearestDryLand}; null to tread water */
    public SurfaceTask(Hands hands, BlockPos land) {
        this.hands = hands;
        this.land = land;
    }

    @Override
    public Status tick(WorldView world) {
        if (!started) {
            started = true;
            hands.paths().stop();
        }
        if (!world.inWater() && world.onGround()) return finish();
        if (++ticks > TIMEOUT_TICKS) return finish();
        if (land == null) {
            hands.holdKeys(false, true);                   // tread water
            return world.air() >= world.maxAir() ? finish() : Status.RUNNING;
        }
        hands.steerToward(new Vec3(land.x() + 0.5, land.y() + 2, land.z() + 0.5), STEER_TOLERANCE_DEGREES);
        hands.holdKeys(true, true);
        return Status.RUNNING;
    }

    private Status finish() {
        hands.holdKeys(false, false);
        return Status.DONE;
    }

    @Override public void cancel() { hands.holdKeys(false, false); }

    @Override
    public String describe() {
        return land == null ? "treading water" : "swimming to dry land at " + land.x() + " " + land.z();
    }
}
