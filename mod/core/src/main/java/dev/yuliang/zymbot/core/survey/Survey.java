package dev.yuliang.zymbot.core.survey;

import dev.yuliang.zymbot.core.api.BlockPos;
import dev.yuliang.zymbot.core.api.Vec3;
import dev.yuliang.zymbot.core.survey.SurveyCatalog.Kind;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * What the bot found around itself at one moment (PHASE3.md §2): itself, the loaded area near it,
 * and the world. Read only — a value the planner queries ("nearest tree?"). Built by
 * {@link Surveyor} off the game thread.
 *
 * @param found     every group found, by kind, nearest first
 * @param truncated the scan hit its block cap or its time budget: there may be more than this
 */
public record Survey(String why, long takenAtMillis, BlockPos at, String biome, long timeOfDay, long day,
                     BlockPos spawn, float health, int hunger, float saturation,
                     Map<String, Integer> inventory, List<String> foods,
                     Map<Kind, List<Group>> found, int blocksSeen, boolean truncated, long searchMs) {

    /**
     * One thing found: a tree (its connected logs), a food patch (wild plants a few blocks apart), or
     * one table / furnace / chest / bed / campfire (joined halves count once).
     *
     * @param pos      the tree's lowest log (its base); otherwise the group's block nearest to us
     * @param blocks   how many blocks make it up (logs in the tree, plants in the patch)
     * @param huge     a tree with a 2×2 trunk somewhere (dark oak, giant spruce/jungle…)
     * @param distance straight-line blocks from where we stood to {@code pos}
     */
    public record Group(Kind kind, String id, BlockPos pos, int blocks, boolean huge, double distance) {}

    public List<Group> all(Kind kind) { return found.getOrDefault(kind, List.of()); }
    public Optional<Group> nearest(Kind kind) { return all(kind).stream().findFirst(); }
    public Optional<Group> nearestTree() { return nearest(Kind.LOG); }
    public Optional<Group> nearestWildFood() { return nearest(Kind.WILD_FOOD); }
    public Optional<Group> nearestCraftingTable() { return nearest(Kind.CRAFTING_TABLE); }

    // ------------------------------------------------------------------ words

    /** Eight-point compass from {@code from} to {@code to}: north is -z, east is +x. "here" when on top. */
    public static String direction(BlockPos from, BlockPos to) {
        int dx = to.x() - from.x(), dz = to.z() - from.z();
        if (dx == 0 && dz == 0) return "here";
        double deg = Math.toDegrees(Math.atan2(dx, -dz));
        int i = (int) Math.floorMod(Math.round(deg / 45.0), 8L);
        return new String[]{"N", "NE", "E", "SE", "S", "SW", "W", "NW"}[i];
    }

    public static String partOfDay(long t) {
        t = Math.floorMod(t, 24000L);
        if (t < 6000) return "morning";
        if (t < 12000) return "afternoon";
        if (t < 13800) return "evening";
        if (t < 22200) return "night";
        return "dawn";
    }

    private String where(Group g) {
        return Math.round(g.distance()) + " blocks " + direction(at, g.pos());
    }

    private static String plural(int n, String noun) {
        if (n == 1) return (noun.matches("^[aeiou].*") ? "an " : "a ") + noun;
        return n + " " + (noun.endsWith("ch") || noun.endsWith("sh") ? noun + "es" : noun + "s");
    }

    private static String shortId(String id) { return id.substring(id.indexOf(':') + 1); }

    /** One line: trees, food, workstations — what the decision log says. */
    public String summary() {
        List<String> parts = new ArrayList<>();
        List<Group> trees = all(Kind.LOG);
        if (trees.isEmpty()) parts.add("no trees");
        else {
            Group t = trees.get(0);
            long huge = trees.stream().filter(Group::huge).count();
            parts.add(trees.size() + (trees.size() == 1 ? " tree" : " trees") + " (nearest " + where(t)
                    + (t.huge() ? ", huge " : ", ") + shortId(t.id()) + (huge > 0 && !t.huge() ? "; " + huge + " huge" : "") + ")");
        }
        List<Group> food = all(Kind.WILD_FOOD);
        if (food.isEmpty()) parts.add("no wild food");
        else parts.add(food.size() + (food.size() == 1 ? " wild food patch" : " wild food patches")
                + " (nearest " + where(food.get(0)) + ", " + shortId(food.get(0).id()) + ")");
        for (Kind k : List.of(Kind.CRAFTING_TABLE, Kind.FURNACE, Kind.STORAGE, Kind.BED, Kind.CAMPFIRE)) {
            List<Group> gs = all(k);
            if (gs.isEmpty()) continue;
            parts.add(plural(gs.size(), k.noun) + " " + (gs.size() == 1 ? "" : "(nearest ") + where(gs.get(0))
                    + (gs.size() == 1 ? "" : ")"));
        }
        if (truncated) parts.add("(scan cut short — there may be more)");
        return String.join(", ", parts);
    }

    /** A few lines, for /zbot survey. */
    public List<String> lines(long nowMillis) { return lines(nowMillis, null); }

    /**
     * As {@link #lines(long)}, saying where it was taken relative to {@code here}: every distance
     * in it is from *there*, and an old one read at spawn looked like it was about spawn (2026-09-27:
     * "838s ago", taken 85 blocks E). Null {@code here}: don't say.
     */
    public List<String> lines(long nowMillis, BlockPos here) {
        List<String> out = new ArrayList<>();
        out.add("survey (" + Math.max(0, (nowMillis - takenAtMillis) / 1000) + "s ago, "
                + (here == null ? "" : takenWhere(here) + ", ") + why + "):");
        out.add("  around: " + summary());
        List<String> others = new ArrayList<>();
        for (Kind k : Kind.values()) {
            List<Group> gs = all(k);
            for (int i = 1; i < Math.min(gs.size(), 3); i++) others.add(k.noun + " " + where(gs.get(i)));
        }
        if (!others.isEmpty()) out.add("  next: " + String.join(", ", others));
        out.add(String.format(Locale.ROOT, "  self: health %d, hunger %d/20 (+%.1f saturation); carrying %s",
                Math.round(health), hunger, saturation, inventory.isEmpty() ? "nothing"
                        : inventory.entrySet().stream().map(e -> e.getValue() + " " + shortId(e.getKey()))
                        .collect(Collectors.joining(", "))));
        out.add("  food: " + (foods.isEmpty() ? "none" : String.join(", ", foods)));
        out.add("  world: " + shortId(biome) + ", day " + day + " " + partOfDay(timeOfDay) + ", "
                + (spawn == null ? "spawn unknown" : spawnWords()));
        out.add(String.format(Locale.ROOT, "  scan: %d blocks within %d across, %d below to %d above; grouped in %d ms off the game thread%s",
                blocksSeen, SurveyCatalog.RADIUS, SurveyCatalog.BELOW, SurveyCatalog.ABOVE, searchMs, truncated ? " — cut short" : ""));
        return out;
    }

    /** "taken here" or "85 blocks E of here" (flat distance, as spawn's). */
    public String takenWhere(BlockPos here) {
        long d = Math.round(Math.hypot(at.x() - here.x(), at.z() - here.z()));
        return d == 0 ? "taken here" : d + " blocks " + direction(here, at) + " of here";
    }

    private String spawnWords() {
        long d = Math.round(Math.hypot(spawn.x() - at.x(), spawn.z() - at.z()));
        return d == 0 ? "at spawn" : "spawn " + d + " blocks " + direction(at, spawn);
    }

    /** Flat distance to a point; handy for callers holding a Vec3. */
    public static double distance(Vec3 from, BlockPos to) {
        double dx = to.x() + 0.5 - from.x(), dy = to.y() - from.y(), dz = to.z() + 0.5 - from.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
