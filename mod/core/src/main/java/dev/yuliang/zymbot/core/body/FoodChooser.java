package dev.yuliang.zymbot.core.body;

import dev.yuliang.zymbot.core.api.ItemView;
import dev.yuliang.zymbot.core.api.WorldView;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Picks what to eat under Spice of Fabric (SURVIVAL_EARLY_GAME §1.1). Spice decays a food's hunger
 * per player ({@code nutrition × 0.7^(times among your last 11 meals)}) and the server shows each
 * player the <b>already-decayed</b> value in the item itself — seen live 2026-09-22: bread 2 for
 * Bot1, 5 for Bot2. So the live value is the truth and is used as it is; our own meal memory only
 * breaks ties toward variety. Never picks harmful food or anything on the never-eat list.
 */
public final class FoodChooser {
    public static final double SPICE_DECAY = 0.7;
    public static final int SPICE_HISTORY = 11;

    private FoodChooser() {}

    /** Hunger this item gives right now — the game's own (Spice-decayed) number. */
    public static double value(ItemView item, List<String> recent) {
        return item.food().nutrition();
    }

    /** Times it's among our recent meals — the tie-breaker (fewer is better). */
    static long repeats(ItemView item, List<String> recent) {
        return recent.stream().filter(item.id()::equals).count();
    }

    /**
     * What the choice was made from, for the decision log: "bread 2 · also apple 4, potato 2" — the
     * values seen at that moment, best first after the pick, so a strange pick shows why.
     */
    public static String explain(WorldView world, ItemView picked, Collection<String> neverEat) {
        java.util.Map<String, Integer> others = new java.util.LinkedHashMap<>();
        world.inventory().stream().filter(i -> safe(i, neverEat) && !i.id().equals(picked.id()))
                .sorted(Comparator.comparingInt((ItemView i) -> -i.food().nutrition()))
                .forEach(i -> others.putIfAbsent(i.id(), i.food().nutrition()));
        String rest = others.entrySet().stream().limit(4).map(e -> shortId(e.getKey()) + " " + e.getValue())
                .collect(java.util.stream.Collectors.joining(", "));
        return shortId(picked.id()) + " " + picked.food().nutrition() + (rest.isEmpty() ? "" : " · also " + rest);
    }

    private static String shortId(String id) {
        return id.startsWith("minecraft:") ? id.substring(10) : id;
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
                .thenComparingLong(i -> -repeats(i, recent))              // variety, when it's a tie
                .thenComparingDouble(i -> i.food().saturation())
                .thenComparing(ItemView::inHotbar);                       // fewer steps
        if (healing) return edible.stream().filter(i -> i.food().heals()).max(byValue);
        Optional<ItemView> plain = edible.stream().filter(i -> !i.food().heals()).max(byValue);
        return plain.isPresent() ? plain : edible.stream().max(byValue);
    }
}
