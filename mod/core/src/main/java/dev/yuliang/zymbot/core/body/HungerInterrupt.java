package dev.yuliang.zymbot.core.body;

import dev.yuliang.zymbot.core.api.Hands;
import dev.yuliang.zymbot.core.api.WorldView;
import dev.yuliang.zymbot.core.brain.Interrupt;
import dev.yuliang.zymbot.core.config.ZymbotConfig;
import dev.yuliang.zymbot.core.task.EatTask;
import dev.yuliang.zymbot.core.task.Task;

/** Interrupt #6 — hunger at or below the threshold: eat the best food (BOT_BEHAVIOUR.md). */
public final class HungerInterrupt implements Interrupt {
    private final ZymbotConfig config;
    private final Body body;
    private String lastChoice = "";

    public HungerInterrupt(ZymbotConfig config, Body body) {
        this.config = config;
        this.body = body;
    }

    @Override public String name() { return "hunger"; }

    @Override
    public boolean triggered(WorldView world) {
        return world.hunger() <= config.eatBelowHunger;
    }

    @Override
    public Task respond(WorldView world, Hands hands) {
        var pick = FoodChooser.best(world, body.recentFoods(), config.neverEat, false);
        lastChoice = pick.map(item -> " (" + FoodChooser.explain(world, item, config.neverEat) + ")").orElse("");
        return pick.<Task>map(item -> new EatTask(hands, item, body::ate))
                .orElseGet(() -> Task.failed("eat", "no safe food in the inventory"));
    }

    @Override
    public String why(WorldView world) {
        return "hunger " + world.hunger() + " ≤ " + config.eatBelowHunger + lastChoice;
    }
}
