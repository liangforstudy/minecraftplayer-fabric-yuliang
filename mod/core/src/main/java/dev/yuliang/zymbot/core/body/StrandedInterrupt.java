package dev.yuliang.zymbot.core.body;

import dev.yuliang.zymbot.core.api.BlockPos;
import dev.yuliang.zymbot.core.api.EntityView;
import dev.yuliang.zymbot.core.api.Hands;
import dev.yuliang.zymbot.core.api.WorldView;
import dev.yuliang.zymbot.core.brain.Interrupt;
import dev.yuliang.zymbot.core.task.RetreatTask;
import dev.yuliang.zymbot.core.task.SurfaceTask;
import dev.yuliang.zymbot.core.task.Task;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Out of its depth with nothing to do: swim to the nearest safe place to stand. A retreat that
 * ended in a lake left Bot1 treading water for minutes — the drowning reflex only surfaces
 * (2026-09-26). Never while an order runs: a walk may be crossing on purpose.
 * <ul>
 *   <li>Only out of its depth — the block under the feet is water too — and afloat for
 *       {@link #AFLOAT_TICKS}: in one-block shallows it bobbed off the bottom and kept restarting.</li>
 *   <li>Never to a shore near the last attacker, or with a hostile on it: it swam straight back to
 *       the zombie it had run from.</li>
 * </ul>
 */
public final class StrandedInterrupt implements Interrupt {
    /** How far to look for a shore. Wider than the drowning reflex: there's no air to race. */
    public static final int SHORE_RADIUS = 48;
    /** Afloat this long, out of its depth, before it counts. */
    public static final int AFLOAT_TICKS = 40;
    /** A shore this close to the last attacker — or a hostile this close to the shore — is unsafe. */
    static final double SAFE_FROM_ATTACKER = RetreatTask.DISTANCE;
    static final double SAFE_FROM_HOSTILES = 8;

    private final Body body;
    private final BooleanSupplier free;
    private int afloat;

    /** @param free true when no order is running */
    public StrandedInterrupt(Body body, BooleanSupplier free) {
        this.body = body;
        this.free = free;
    }

    @Override public String name() { return "stranded"; }

    @Override
    public boolean triggered(WorldView world) {
        boolean out = !world.isDead() && world.inWater() && !world.onGround() && outOfDepth(world);
        afloat = out ? afloat + 1 : 0;
        return afloat >= AFLOAT_TICKS && free.getAsBoolean();
    }

    static boolean outOfDepth(WorldView world) {
        BlockPos feet = BlockPos.of(world.position());
        String below = world.blockAt(new BlockPos(feet.x(), feet.y() - 1, feet.z()));
        return below.contains("water") || below.contains("kelp") || below.contains("seagrass") || below.contains("bubble_column");
    }

    @Override
    public Task respond(WorldView world, Hands hands) {
        var attacker = body.recentAttack().map(Body.Attack::from);
        Predicate<BlockPos> safe = land -> {
            if (attacker.isPresent() && attacker.get().horizontalDistance(land.center()) < SAFE_FROM_ATTACKER) return false;
            return world.nearby().stream().noneMatch(e -> e.kind() == EntityView.Kind.HOSTILE
                    && e.pos().horizontalDistance(land.center()) < SAFE_FROM_HOSTILES);
        };
        return world.nearestDryLand(SHORE_RADIUS, safe)
                .<Task>map(land -> new SurfaceTask(hands, land))
                .orElse(Task.failed("reach land", "no safe place to stand within " + SHORE_RADIUS + " blocks"
                        + (attacker.isPresent() ? " (keeping away from the attacker)" : "")));
    }

    @Override public String why(WorldView world) { return "out of its depth with nothing to do"; }
}