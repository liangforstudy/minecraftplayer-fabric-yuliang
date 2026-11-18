package io.github.liangforstudy.zymbot.core.body;

import io.github.liangforstudy.zymbot.core.api.Hands;
import io.github.liangforstudy.zymbot.core.api.ItemView;
import io.github.liangforstudy.zymbot.core.api.WorldView;
import io.github.liangforstudy.zymbot.core.brain.Interrupt;
import io.github.liangforstudy.zymbot.core.config.ZymbotConfig;
import io.github.liangforstudy.zymbot.core.task.EatTask;
import io.github.liangforstudy.zymbot.core.task.Task;
import java.util.Comparator;
import java.util.Optional;

/**
 * Anarchy's own eating (issue #8; replaces Meteor's AutoEat, roadmap "Shared: eating"), under
 * {@code /zbot start}: at hunger {@code eat} or below, the best food from the ticked list. Healing food
 * (golden apples) is never eaten for hunger: it's kept for low health, which the critical-health reflex
 * eats it for. With nothing ticked in the inventory it doesn't trigger at all, so it never loops failing.
 */
public final class AutoEatInterrupt implements Interrupt {
    private final ZymbotConfig config;
    private final Body body;
    private String lastChoice = "";

    public AutoEatInterrupt(ZymbotConfig config, Body body) {
        this.config = config;
        this.body = body;
    }

    @Override public String name() { return "auto-eat"; }

    /** The ticked food to eat now: most hunger first, then saturation, then already in the hotbar. */
    public static Optional<ItemView> pick(WorldView world, ZymbotConfig config) {
        if (world.hunger() >= 20) return Optional.empty();
        return world.inventory().stream()
                .filter(i -> FoodChooser.safe(i, config.neverEat) && !i.food().heals() && config.eatFoods.contains(i.id()))
                .max(Comparator.<ItemView>comparingInt(i -> i.food().nutrition())
                        .thenComparingDouble(i -> i.food().saturation())
                        .thenComparing(ItemView::inHotbar));
    }

    @Override
    public boolean triggered(WorldView world) {
        return config.autoEat && world.hunger() <= config.eatBelowHunger && pick(world, config).isPresent();
    }

    @Override
    public Task respond(WorldView world, Hands hands) {
        var item = pick(world, config);
        lastChoice = item.map(i -> " (" + FoodChooser.explain(world, i, config.neverEat) + ")").orElse("");
        return item.<Task>map(i -> new EatTask(hands, i, body::ate))
                .orElseGet(() -> Task.failed("eat", "no ticked food in the inventory"));
    }

    @Override
    public String why(WorldView world) {
        return "hunger " + world.hunger() + " ≤ " + config.eatBelowHunger + lastChoice;
    }
}
