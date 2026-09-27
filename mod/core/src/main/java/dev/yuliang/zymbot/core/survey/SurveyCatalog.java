package dev.yuliang.zymbot.core.survey;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the survey looks for, by block registry id — the one place these lists live (PHASE3.md §2).
 * Each kind is matched by exact ids, then id prefixes, then id suffixes. Wild-food ids come from
 * survival-data/wild_forage.json (the harvestable blocks, not the worldgen feature names).
 */
public final class SurveyCatalog {
    private SurveyCatalog() {}

    public enum Kind {
        LOG("tree"), WILD_FOOD("wild food patch"), CRAFTING_TABLE("crafting table"), FURNACE("furnace"),
        STORAGE("chest"), BED("bed"), CAMPFIRE("campfire");

        public final String noun;
        Kind(String noun) { this.noun = noun; }
    }

    /** One kind's rule: exact ids, "namespace:prefix" starts, "_suffix" ends; {@code not} vetoes. */
    record Rule(Kind kind, Set<String> exact, List<String> prefixes, List<String> suffixes, List<String> not) {}

    static final List<Rule> RULES = List.of(
            // stripped logs are usually a player's; still wood, still count
            new Rule(Kind.LOG, Set.of(), List.of(), List.of("_log", "_stem"), List.of("potted_")),
            new Rule(Kind.WILD_FOOD,
                    Set.of("minecraft:brown_mushroom", "minecraft:red_mushroom", "minecraft:sweet_berry_bush",
                            "farmersdelight:brown_mushroom_colony", "farmersdelight:red_mushroom_colony"),
                    List.of("farm_and_charm:wild_", "farmersdelight:wild_", "ubesdelight:wild_", "brewery:wild_"),
                    List.of(), List.of()),
            new Rule(Kind.CRAFTING_TABLE, Set.of("minecraft:crafting_table"), List.of(), List.of(), List.of()),
            new Rule(Kind.FURNACE, Set.of("minecraft:furnace", "minecraft:smoker", "minecraft:blast_furnace"),
                    List.of(), List.of(), List.of()),
            new Rule(Kind.STORAGE, Set.of("minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel"),
                    List.of(), List.of(), List.of()),
            new Rule(Kind.BED, Set.of(), List.of("minecraft:"), List.of("_bed"), List.of()),
            new Rule(Kind.CAMPFIRE, Set.of("minecraft:campfire", "minecraft:soul_campfire"), List.of(), List.of(), List.of()));

    /** Ids that look like a match but aren't (a mushroom stem block is not a tree). */
    static final Set<String> NEVER = Set.of("minecraft:mushroom_stem");

    /** The kind this block id is, or null. */
    public static Kind classify(String id) {
        if (id == null || NEVER.contains(id)) return null;
        for (Rule r : RULES) {
            if (r.not().stream().anyMatch(id::contains)) continue;
            if (r.exact().contains(id)) return r.kind();
            boolean prefix = r.prefixes().isEmpty() || r.prefixes().stream().anyMatch(id::startsWith);
            boolean suffix = r.suffixes().isEmpty() || r.suffixes().stream().anyMatch(id::endsWith);
            if ((!r.prefixes().isEmpty() || !r.suffixes().isEmpty()) && prefix && suffix) return r.kind();
        }
        return null;
    }

    public static boolean wanted(String id) { return classify(id) != null; }

    /** How the survey is bounded. */
    public static final int RADIUS = 56, BELOW = 16, ABOVE = 24, MAX_BLOCKS = 8192;
    /** Blocks apart (each axis) that still count as one tree / one food patch / one spot. */
    static final Map<Kind, Integer> JOIN = Map.of(Kind.LOG, 1, Kind.WILD_FOOD, 3);
}
