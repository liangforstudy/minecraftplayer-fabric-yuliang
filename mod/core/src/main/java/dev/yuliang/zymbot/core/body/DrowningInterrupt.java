package dev.yuliang.zymbot.core.body;

import dev.yuliang.zymbot.core.api.Hands;
import dev.yuliang.zymbot.core.api.WorldView;
import dev.yuliang.zymbot.core.brain.Interrupt;
import dev.yuliang.zymbot.core.task.SurfaceTask;
import dev.yuliang.zymbot.core.task.Task;

/**
 * Interrupt #1a, above critical health — air running out in the water: surface and swim to dry
 * land. Drowning has no attacker, so critical health alone can't help (FIXLIST #2).
 */
public final class DrowningInterrupt implements Interrupt {
    /** Act with a third of the air gone: 10 s left, plenty to reach a shore 24 blocks off. */
    static final double AIR_FRACTION = 2.0 / 3.0;

    @Override public String name() { return "drowning"; }

    @Override
    public boolean triggered(WorldView world) {
        return !world.isDead() && (world.inWater() || world.headInWater())
                && world.air() <= world.maxAir() * AIR_FRACTION;
    }

    @Override
    public Task respond(WorldView world, Hands hands) {
        return new SurfaceTask(hands, world.nearestDryLand(SurfaceTask.LAND_RADIUS, SurfaceTask.faceOnShore(world)).orElse(null));
    }

    @Override
    public String why(WorldView world) {
        return "air " + world.air() / 20 + "s of " + world.maxAir() / 20 + "s, under water";
    }
}
