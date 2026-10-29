package io.github.liangforstudy.zymbot.core.body;

import io.github.liangforstudy.zymbot.core.api.BlockPos;
import io.github.liangforstudy.zymbot.core.api.Hands;
import io.github.liangforstudy.zymbot.core.api.Vec3;
import io.github.liangforstudy.zymbot.core.api.WorldView;
import io.github.liangforstudy.zymbot.core.brain.Interrupt;
import io.github.liangforstudy.zymbot.core.config.ZymbotConfig;
import io.github.liangforstudy.zymbot.core.task.EatTask;
import io.github.liangforstudy.zymbot.core.task.PourWaterTask;
import io.github.liangforstudy.zymbot.core.task.Task;

/**
 * Anarchy only (doc/9b9t/roadmap.md, "Before routes" 4): the burn goes on ~8 s after the last touch of
 * fire, 1 a second; in a ghast's hole that was 8 health (2026-10-29). Put it out: a water bucket poured
 * at our feet and scooped back (not where water boils away, the Nether), else a fire resistance potion,
 * but only at {@code fireres} health or below (14; 0 never): a 3-minute potion is a lot for an 8-second burn.
 * After the hazard reflex: out of the fire first (it keeps relighting us), then the water. Not in
 * lava either: water won't go there.
 */
public final class BurningInterrupt implements Interrupt {
    private final ZymbotConfig config;

    /** The potion's health line is {@code config.fireResistanceHealth} ({@code /zbot set fireres}; 0 = never). */
    public BurningInterrupt(ZymbotConfig config) {
        this.config = config;
    }

    @Override public String name() { return "burning"; }

    @Override
    public boolean triggered(WorldView world) {
        if (world.isDead() || !world.onFire() || world.fireResistant() || world.inWater()) return false;
        if (world.blockAt(feet(world)).contains("lava")) return false;       // water won't go there: step out first
        return canPour(world) || canDrink(world);
    }

    /** A water bucket, somewhere water stays, standing on something it can go on. */
    static boolean canPour(WorldView world) {
        if (world.waterEvaporates() || world.inventory().stream().noneMatch(i -> PourWaterTask.WATER_BUCKET.equals(i.id()))) return false;
        Vec3 p = world.position();
        BlockPos under = new BlockPos((int) Math.floor(p.x()), (int) Math.floor(p.y() - 0.01), (int) Math.floor(p.z()));
        return world.onGround() && world.sturdy(under);
    }

    private static BlockPos feet(WorldView world) {
        Vec3 p = world.position();
        return new BlockPos((int) Math.floor(p.x()), (int) Math.floor(p.y() + 0.01), (int) Math.floor(p.z()));
    }

    boolean canDrink(WorldView world) {
        return world.health() <= config.fireResistanceHealth && world.fireResistancePotion().isPresent();
    }

    @Override
    public Task respond(WorldView world, Hands hands) {
        if (canPour(world)) return new PourWaterTask(hands);
        return world.fireResistancePotion().<Task>map(p -> new EatTask(hands, p, id -> {}))
                .orElseGet(() -> Task.failed("putting the fire out", "no water bucket, no fire resistance"));
    }

    @Override
    public String why(WorldView world) {
        return "on fire, health " + Math.round(world.health())
                + (canPour(world) ? " — water at our feet" : " — drink fire resistance");
    }
}
