package dev.yuliang.zymbot.core.body;

import dev.yuliang.zymbot.core.api.EntityView;
import dev.yuliang.zymbot.core.api.Hands;
import dev.yuliang.zymbot.core.api.WorldView;
import dev.yuliang.zymbot.core.brain.Interrupt;
import dev.yuliang.zymbot.core.config.ZymbotConfig;
import dev.yuliang.zymbot.core.task.EatTask;
import dev.yuliang.zymbot.core.task.RetreatTask;
import dev.yuliang.zymbot.core.task.Task;
import java.util.Optional;

/**
 * Interrupt #1 — health at or below critical. Natural regeneration is off on this server, so
 * damage never fixes itself: eat something that heals; failing that, get away from whatever is
 * hurting us, toward a human.
 */
public final class CriticalHealthInterrupt implements Interrupt {
    private final ZymbotConfig config;
    private final Body body;

    public CriticalHealthInterrupt(ZymbotConfig config, Body body) {
        this.config = config;
        this.body = body;
    }

    @Override public String name() { return "critical-health"; }

    @Override
    public boolean triggered(WorldView world) {
        return !world.isDead() && world.health() <= config.criticalHealth;
    }

    @Override
    public Task respond(WorldView world, Hands hands) {
        var heal = FoodChooser.best(world, body.recentFoods(), config.neverEat, true);
        if (heal.isPresent()) return new EatTask(hands, heal.get(), body::ate);
        var attack = body.recentAttack()
                .filter(a -> a.from().horizontalDistance(world.position()) < RetreatTask.DISTANCE);   // not already clear
        if (attack.isPresent()) {
            Optional<EntityView> human = body.nearestHuman(world);
            return new RetreatTask(hands.paths(), attack.get().from(), human.map(EntityView::pos), body::reportSwim);
        }
        return Task.failed("recover", "no healing food, and nothing to run from");
    }

    @Override
    public String why(WorldView world) {
        String hurt = body.recentAttack().map(a -> ", hurt by " + (a.who() == null ? "something" : a.who())).orElse("");
        return "health " + Math.round(world.health()) + " ≤ " + config.criticalHealth + hurt;
    }
}
