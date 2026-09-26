package dev.yuliang.zymbot.core.body;

import dev.yuliang.zymbot.core.api.BlockPos;
import dev.yuliang.zymbot.core.api.Hands;
import dev.yuliang.zymbot.core.api.WorldView;
import dev.yuliang.zymbot.core.brain.Interrupt;
import dev.yuliang.zymbot.core.task.Task;
import dev.yuliang.zymbot.core.task.WalkTask;
import java.util.function.BooleanSupplier;

/**
 * Idle, standing in the water — in its depth, so not {@link StrandedInterrupt}'s business: step onto
 * the nearest safe dry land and stop. Bot1 finished a regroup at the shore standing in shallow water
 * and bobbed there for good (2026-09-26). The lowest-priority reflex: only when nothing at all runs
 * (no order, no objective, no other reflex).
 * <ul>
 *   <li>Counts ticks <em>in water</em>, not afloat: bobbing off the bottom of one-block shallows
 *       doesn't reset it — that reset made the old afloat check restart forever.</li>
 *   <li>Out of the water it stops and doesn't look again until it's back in the water for
 *       {@link #WET_TICKS}.</li>
 *   <li>At most {@link #MAX_TRIES} walks per spell in the water; no safe land within
 *       {@link #LAND_RADIUS} (or out of tries) gives up — said once — until it leaves the water.</li>
 * </ul>
 */
public final class WadingInterrupt implements Interrupt {
    /** In the water this long (2.5 s) with nothing to do before it steps out — a splash through a puddle isn't it. */
    public static final int WET_TICKS = 50;
    /** Dry land this close: it's in the shallows, the shore is right there. Farther is a swim (stranded). */
    public static final int LAND_RADIUS = 8;
    /** Walks tried in one spell in the water before it gives up standing there. */
    public static final int MAX_TRIES = 3;

    private final Body body;
    private final BooleanSupplier free;
    private int wet;
    private int tries;
    private boolean gaveUp;

    /** @param free true when no order, objective or other reflex runs (this reflex's own walk counts as free) */
    public WadingInterrupt(Body body, BooleanSupplier free) {
        this.body = body;
        this.free = free;
    }

    @Override public String name() { return "wading"; }

    @Override
    public boolean triggered(WorldView world) {
        if (world.isDead() || !world.inWater()) {                // on dry ground: done, forget this spell
            wet = 0;
            tries = 0;
            gaveUp = false;
            return false;
        }
        wet++;
        return !gaveUp && wet >= WET_TICKS && free.getAsBoolean();
    }

    @Override
    public Task respond(WorldView world, Hands hands) {
        if (++tries > MAX_TRIES) {
            gaveUp = true;
            return Task.failed("step out of the water", "still in the water after " + MAX_TRIES + " tries — staying put");
        }
        var land = world.nearestDryLand(LAND_RADIUS, StrandedInterrupt.safeLand(body, world));
        if (land.isEmpty()) {
            gaveUp = true;                                      // said once; looks again after leaving the water
            return Task.failed("step out of the water", "no safe dry land within " + LAND_RADIUS + " blocks — staying put");
        }
        BlockPos l = land.get();
        Task walk = new WalkTask(hands.paths(), new BlockPos(l.x(), l.y() + 1, l.z()), false, 0);
        return new Task() {
            public Status tick(WorldView w) { return walk.tick(w); }
            public void cancel() { walk.cancel(); }
            public String failure() { return walk.failure(); }
            public String describe() { return "stepping out of the water to " + l.x() + " " + l.z(); }
        };
    }

    @Override public String why(WorldView world) { return "idle, standing in water"; }
}
