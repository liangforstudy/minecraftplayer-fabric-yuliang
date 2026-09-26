package dev.yuliang.zymbot.core.body;

import dev.yuliang.zymbot.core.api.BlockPos;
import dev.yuliang.zymbot.core.api.EntityView;
import dev.yuliang.zymbot.core.api.Hands;
import dev.yuliang.zymbot.core.api.WorldView;
import dev.yuliang.zymbot.core.brain.Interrupt;
import dev.yuliang.zymbot.core.config.ZymbotConfig;
import dev.yuliang.zymbot.core.task.Task;
import dev.yuliang.zymbot.core.task.WalkTask;
import java.util.function.BooleanSupplier;

/**
 * Interrupt #5 — R2: while working on its own objective, the bot stays within the leash of the
 * nearest Zymbot teammate (PHASE2.md P2-2 — a stranger walking past must not become the anchor).
 * It never applies to an idle bot or to a player's direct order (PHASE1.md P1-2).
 */
public final class LeashInterrupt implements Interrupt {
    private final ZymbotConfig config;
    private final Body body;
    private final BooleanSupplier working;

    public LeashInterrupt(ZymbotConfig config, Body body, BooleanSupplier working) {
        this.config = config;
        this.body = body;
        this.working = working;
    }

    @Override public String name() { return "leash"; }

    @Override
    public boolean triggered(WorldView world) {
        if (!working.getAsBoolean()) return false;
        return body.nearestTeammate(world).map(h -> distance(world, h) > config.leashBlocks).orElse(false);
    }

    @Override
    public Task respond(WorldView world, Hands hands) {
        EntityView h = body.nearestTeammate(world).orElseThrow();
        return new WalkTask(hands.paths(), BlockPos.of(h.pos()), false, config.leashBlocks / 2, body::reportSwim);
    }

    @Override
    public String why(WorldView world) {
        return body.nearestTeammate(world)
                .map(h -> h.name() + " is " + Math.round(distance(world, h)) + " blocks away (leash " + config.leashBlocks + ")")
                .orElse("too far from the nearest teammate");
    }

    private static double distance(WorldView world, EntityView h) {
        return h.pos().horizontalDistance(world.position());
    }
}
