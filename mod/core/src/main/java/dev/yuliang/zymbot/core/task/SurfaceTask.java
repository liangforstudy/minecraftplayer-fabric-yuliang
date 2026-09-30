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

    /** Close enough to the water block beside the shore to turn and climb straight out. */
    static final double LAUNCH_REACHED = 0.6;

    private final Hands hands;
    private final BlockPos land;
    private boolean started;
    private int ticks;
    private BlockPos launch;                                    // the water block straight beside the land
    private boolean launched;

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
        if (world.onGround() && !world.headInWater()) return finish();   // ashore, or standing in the shallows
        if (++ticks > TIMEOUT_TICKS) return finish();
        if (land == null) {
            hands.holdKeys(false, true);                   // tread water
            return world.air() >= world.maxAir() ? finish() : Status.RUNNING;
        }
        // climb out face-on: first to the water block straight beside the land, then square at it. Aimed
        // at the land directly, Bot1 came in over beach corners (land only diagonal to its water), caught
        // its hitbox on the two side blocks and spun for 10 s: 15327 degrees, 1.4 blocks (2026-09-29)
        if (launch == null) launch = launchFor(world, land, world.position());
        if (launch != null && !launched) {
            Vec3 at = new Vec3(launch.x() + 0.5, launch.y() + 1, launch.z() + 0.5);
            if (at.horizontalDistance(world.position()) < LAUNCH_REACHED) launched = true;
            else {
                hands.steerToward(at, STEER_TOLERANCE_DEGREES);
                hands.holdKeys(true, true);
                return Status.RUNNING;
            }
        }
        hands.steerToward(new Vec3(land.x() + 0.5, land.y() + 2, land.z() + 0.5), launch != null ? 5 : STEER_TOLERANCE_DEGREES);
        hands.holdKeys(true, true);
        return Status.RUNNING;
    }

    private static final int[][] FACES = { {1, 0}, {-1, 0}, {0, 1}, {0, -1} };

    /**
     * The water block straight beside {@code land} (N/S/E/W, at its level or one up) nearest to {@code from},
     * where a swimmer faces the land squarely; null if the land only touches water at a corner.
     */
    public static BlockPos launchFor(WorldView world, BlockPos land, Vec3 from) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int[] f : FACES) {
            for (int dy = 0; dy <= 1; dy++) {
                BlockPos n = new BlockPos(land.x() + f[0], land.y() + dy, land.z() + f[1]);
                if (!world.blockAt(n).contains("water")) continue;
                double d = new Vec3(n.x() + 0.5, from.y(), n.z() + 0.5).horizontalDistance(from);
                if (d < bestD) { bestD = d; best = n; }
                break;
            }
        }
        return best;
    }

    /**
     * Land a swimmer can climb onto face-on: water straight beside it, not only at a corner, and dry on top.
     * A gravel shelf under one block of water counted as "dry land", so Bot1 swam at it and spun there for
     * 10 s, twice at 88 -24 (2026-09-30).
     */
    public static java.util.function.Predicate<BlockPos> faceOnShore(WorldView world) {
        return land -> !world.blockAt(new BlockPos(land.x(), land.y() + 1, land.z())).contains("water")
                && launchFor(world, land, world.position()) != null;
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
