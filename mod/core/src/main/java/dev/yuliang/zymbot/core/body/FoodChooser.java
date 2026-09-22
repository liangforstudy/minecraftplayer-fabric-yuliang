package dev.yuliang.zymbot.core.body;

import dev.yuliang.zymbot.core.api.ItemView;
import dev.yuliang.zymbot.core.api.WorldView;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Picks what to eat under Spice of Fabric (SURVIVAL_EARLY_GAME §1.1): an item's hunger is
 * {@code nutrition × 0.7^(times it's among the last 11 eaten)}, so variety beats quality.
 * Never picks harmful food or anything on the never-eat list.
 */
public final class FoodChooser {
    public static final double SPICE_DECAY = 0.7;
    public static final int SPICE_HISTORY = 11;

    private FoodChooser() {}

    /** Hunger this item would give right now. */
    public static double value(ItemView item, List<String> recent) {
        long repeats = recent.stream().filter(item.id()::equals).count();
        return item.food().nutrition() * Math.pow(SPICE_DECAY, repeats);
    }

    public static boolean safe(ItemView item, Collection<String> neverEat) {
        return item.edible() && !item.food().harmful() && !neverEat.contains(item.id());
    }

    /**
     * The best safe food in the inventory, or empty. {@code healing}: only foods that restore
     * health — for critical health. Otherwise healing foods are kept back (natural regeneration is
     * off, so they're the only way back up) unless nothing else is left. Can't eat at full hunger
     * unless the food says it can always be eaten.
     */
    public static Optional<ItemView> best(WorldView world, List<String> recent, Collection<String> neverEat, boolean healing) {
        List<ItemView> edible = world.inventory().stream()
                .filter(i -> safe(i, neverEat))
                .filter(i -> world.hunger() < 20 || i.food().canAlwaysEat())
                .toList();
        Comparator<ItemView> byValue = Comparator.<ItemView>comparingDouble(i -> value(i, recent))
                .thenComparing(ItemView::inHotbar)                        // fewer steps
                .thenComparingDouble(i -> i.food().saturation());
        if (healing) return edible.stream().filter(i -> i.food().heals()).max(byValue);
        Optional<ItemView> plain = edible.stream().filter(i -> !i.food().heals()).max(byValue);
        return plain.isPresent() ? plain : edible.stream().max(byValue);
    }
}
