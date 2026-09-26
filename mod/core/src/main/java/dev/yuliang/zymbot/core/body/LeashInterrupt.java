package dev.yuliang.zymbot.core.body;

import dev.yuliang.zymbot.core.api.BlockPos;
import dev.yuliang.zymbot.core.api.EntityView;
import dev.yuliang.zymbot.core.api.Hands;
import dev.yuliang.zymbot.core.api.WorldView;
import dev.yuliang.zymbot.core.brain.Interrupt;
import dev.yuliang.zymbot.core.config.ZymbotConfig;
import dev.yuliang.zymbot.core.task.Task;
import dev.yuliang.zymbot.core.task.WalkTask;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Interrupt #5 — R2: the bot stays within the leash of the nearest Zymbot teammate (PHASE2.md P2-2 —
 * a stranger walking past must not become the anchor).
 * <ul>
 *   <li>While working on its own objective: pulled back as soon as it's past the leash.</li>
 *   <li>Idle (PHASE2.md check 6, decided 2026-09-26): it follows the nearest teammate in sight once
 *       they're more than {@code leash_blocks} away. Hysteresis: it starts past the leash and walks
 *       until within {@link #comeWithin} (half the leash), so it doesn't stop-start at the line.
 *       A teammate out of sight, or far off at a start or respawn, is the regroup's job — the idle
 *       follow stays off while a regroup is armed.</li>
 *   <li>Never a player's direct order (PHASE1.md P1-2): an order stops the idle follow at once.</li>
 * </ul>
 */
public final class LeashInterrupt implements Interrupt {
    private final ZymbotConfig config;
    private final Body body;
    private final BooleanSupplier working;
    private final BooleanSupplier idle;
    private final Function<WorldView, Optional<EntityView>> anchor;

    /** The Phase 1 leash: only while working on its own objective, anchored to {@link Body#nearestTeammate}. */
    public LeashInterrupt(ZymbotConfig config, Body body, BooleanSupplier working) {
        this(config, body, working, () -> false, body::nearestTeammate);
    }

    /**
     * @param working true while the planner's own objective runs (not a regroup: that is the walk back)
     * @param idle    true when nothing runs but this reflex's own follow — no order, no objective, no
     *                regroup armed — the idle follow may start, and keeps going only while it holds
     * @param anchor  the nearest current teammate in sight
     */
    public LeashInterrupt(ZymbotConfig config, Body body, BooleanSupplier working, BooleanSupplier idle,
                          Function<WorldView, Optional<EntityView>> anchor) {
        this.config = config;
        this.body = body;
        this.working = working;
        this.idle = idle;
        this.anchor = anchor;
    }

    /** Where a pull back (or an idle follow) stops: well inside the leash, so it doesn't jitter at the line. */
    public static int comeWithin(int leash) { return leash / 2; }

    @Override public String name() { return "leash"; }

    @Override
    public boolean triggered(WorldView world) {
        if (!working.getAsBoolean() && !idle.getAsBoolean()) return false;
        return anchor.apply(world).map(h -> distance(world, h) > config.leashBlocks).orElse(false);
    }

    @Override
    public Task respond(WorldView world, Hands hands) {
        EntityView h = anchor.apply(world).orElseThrow();
        WalkTask walk = new WalkTask(hands.paths(), BlockPos.of(h.pos()), false, comeWithin(config.leashBlocks), body::reportSwim);
        if (working.getAsBoolean()) return walk;
        return new Task() {                                     // the idle follow
            public Status tick(WorldView w) {
                if (!idle.getAsBoolean()) {                     // an order or a regroup came up: theirs now
                    walk.cancel();
                    return Status.DONE;
                }
                return walk.tick(w);
            }
            public void cancel() { walk.cancel(); }
            public String failure() { return walk.failure(); }
            public String describe() { return "following " + h.name(); }
        };
    }

    @Override
    public String why(WorldView world) {
        boolean following = !working.getAsBoolean();
        return anchor.apply(world)
                .map(h -> following
                        ? "idle, " + Math.round(distance(world, h)) + " blocks from " + h.name() + " (leash " + config.leashBlocks + ")"
                        : h.name() + " is " + Math.round(distance(world, h)) + " blocks away (leash " + config.leashBlocks + ")")
                .orElse("too far from the nearest teammate");
    }

    private static double distance(WorldView world, EntityView h) {
        return h.pos().horizontalDistance(world.position());
    }
}
