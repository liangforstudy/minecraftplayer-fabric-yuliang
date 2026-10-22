package io.github.liangforstudy.zymbot.core.craft;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * A shaped recipe the bot can lay out by hand (PHASE3.md step 3). Only what M0 needs, written here
 * rather than read from the game: the bot places the items the way a player would and reads the
 * result slot, so a pack that changed a recipe shows up as "the result slot stayed empty".
 *
 * @param cells  the pattern, row by row, {@code width × height}; null is an empty cell
 * @param makes  items one craft gives
 */
public record Recipe(String name, Ingredient result, int width, int height, List<Ingredient> cells, int makes) {

    /**
     * What may go in a cell (or come out): "any log", "any planks", one item.
     *
     * @param id the one item it is, or null for a family ("any planks")
     */
    public record Ingredient(String name, String id, Predicate<String> matches) {
        public boolean test(String item) { return item != null && matches.test(item); }

        public static Ingredient item(String id) { return new Ingredient(shortName(id), id, id::equals); }
    }

    /** Any log, wood or stem that turns into planks. Stripped ones too (still wood). */
    public static final Ingredient LOG = new Ingredient("log", null, id ->
            (id.endsWith("_log") || id.endsWith("_wood") || id.endsWith("_stem") || id.endsWith("_hyphae"))
                    && !id.equals("minecraft:mushroom_stem") && !id.startsWith("minecraft:potted_"));
    /** Any planks (the #minecraft:planks tag, by name). */
    public static final Ingredient PLANKS = new Ingredient("planks", null, id -> id.endsWith("_planks"));
    public static final Ingredient STICK = Ingredient.item("minecraft:stick");

    public static final Recipe PLANKS_FROM_LOG = new Recipe("planks", PLANKS, 1, 1, cells(LOG), 4);
    public static final Recipe STICKS = new Recipe("sticks", STICK, 1, 2, cells(PLANKS, PLANKS), 4);
    public static final Recipe CRAFTING_TABLE = new Recipe("crafting table", Ingredient.item("minecraft:crafting_table"),
            2, 2, cells(PLANKS, PLANKS, PLANKS, PLANKS), 1);
    public static final Recipe WOODEN_AXE = new Recipe("wooden axe", Ingredient.item("minecraft:wooden_axe"), 2, 3,
            cells(PLANKS, PLANKS,
                  PLANKS, STICK,
                  null, STICK), 1);
    public static final Recipe WOODEN_HOE = new Recipe("wooden hoe", Ingredient.item("minecraft:wooden_hoe"), 2, 3,
            cells(PLANKS, PLANKS,
                  null, STICK,
                  null, STICK), 1);

    public static final List<Recipe> ALL = List.of(PLANKS_FROM_LOG, STICKS, CRAFTING_TABLE, WOODEN_AXE, WOODEN_HOE);

    public Recipe {
        if (cells.size() != width * height) throw new IllegalArgumentException(name + ": " + cells.size() + " cells, not " + width + "×" + height);
    }

    /** Fits the 2×2 inventory grid; otherwise it needs a crafting table. */
    public boolean fitsHand() { return width <= 2 && height <= 2; }

    /** The grid cell (row by row, in a grid {@code gridWidth} wide) for pattern cell {@code i}. */
    public int gridCell(int i, int gridWidth) { return (i / width) * gridWidth + i % width; }

    /** One of {@link #ALL} by name ("axe", "wooden axe", "table", "planks"...), or null. */
    public static Recipe named(String name) {
        String n = name.trim().toLowerCase(Locale.ROOT);
        for (Recipe r : ALL) if (r.name.equals(n) || r.name.endsWith(" " + n) || r.name.startsWith(n)) return r;
        return null;
    }

    public static String shortName(String id) {
        return id == null ? "nothing" : id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    private static List<Ingredient> cells(Ingredient... cells) { return Arrays.asList(cells); }
}
