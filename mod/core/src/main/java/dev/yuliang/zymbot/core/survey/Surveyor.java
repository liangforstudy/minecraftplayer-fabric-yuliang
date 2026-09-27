package dev.yuliang.zymbot.core.survey;

import dev.yuliang.zymbot.core.api.BlockHit;
import dev.yuliang.zymbot.core.api.BlockPos;
import dev.yuliang.zymbot.core.api.ItemView;
import dev.yuliang.zymbot.core.api.Vec3;
import dev.yuliang.zymbot.core.api.WorldView;
import dev.yuliang.zymbot.core.survey.SurveyCatalog.Kind;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Takes a {@link Survey}: a quick copy on the game thread ({@link #copy}: the matching block positions,
 * the inventory, the numbers), then the grouping and sorting off it ({@link #group}), within a time
 * budget like the route planner's {@code plantime}.
 */
public final class Surveyor {
    private Surveyor() {}

    /** Where the grouping runs. Tests swap in {@code Runnable::run}. */
    public static volatile Executor EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "zymbot-survey");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    /** Everything read on the game thread: immutable, safe to hand to another thread. */
    public record Snapshot(String why, long atMillis, Vec3 pos, String biome, long timeOfDay, long day, BlockPos spawn,
                           float health, int hunger, float saturation, List<ItemView> inventory, List<BlockHit> hits,
                           boolean capped) {}

    /** Game thread: the copy. Only blocks the catalog wants are copied, at most MAX_BLOCKS of them. */
    public static Snapshot copy(WorldView w, String why, long nowMillis) {
        List<BlockHit> hits = List.copyOf(w.scanBlocks(SurveyCatalog.RADIUS, SurveyCatalog.BELOW, SurveyCatalog.ABOVE,
                SurveyCatalog.MAX_BLOCKS, SurveyCatalog::wanted));
        return new Snapshot(why, nowMillis, w.position(), w.biome(), w.timeOfDay(), w.day(), w.worldSpawn().orElse(null),
                w.health(), w.hunger(), w.saturation(), List.copyOf(w.inventory()), hits,
                hits.size() >= SurveyCatalog.MAX_BLOCKS);
    }

    /** Copy now, group on {@link #EXECUTOR}. */
    public static CompletableFuture<Survey> take(WorldView w, String why, long nowMillis, long budgetMs) {
        Snapshot s = copy(w, why, nowMillis);
        return CompletableFuture.supplyAsync(() -> group(s, budgetMs), EXECUTOR);
    }

    /** Any thread: group the copied blocks into trees / patches / spots, nearest first. */
    public static Survey group(Snapshot s, long budgetMs) {
        long t0 = System.nanoTime();
        long deadline = budgetMs <= 0 ? Long.MAX_VALUE : t0 + budgetMs * 1_000_000L;
        BlockPos at = BlockPos.of(s.pos());
        Map<Kind, Map<BlockPos, String>> byKind = new EnumMap<>(Kind.class);
        for (BlockHit h : s.hits()) {
            Kind k = SurveyCatalog.classify(h.id());
            if (k != null) byKind.computeIfAbsent(k, x -> new HashMap<>()).put(h.pos(), h.id());
        }
        boolean truncated = s.capped();
        Map<Kind, List<Survey.Group>> found = new EnumMap<>(Kind.class);
        for (var e : byKind.entrySet()) {
            if (System.nanoTime() > deadline) { truncated = true; break; }
            List<Survey.Group> gs = groups(e.getKey(), e.getValue(), s.pos(), deadline);
            if (gs == null) { truncated = true; break; }
            found.put(e.getKey(), List.copyOf(gs));
        }
        return new Survey(s.why(), s.atMillis(), at, s.biome(), s.timeOfDay(), s.day(), s.spawn(), s.health(),
                s.hunger(), s.saturation(), inventory(s.inventory()), foods(s.inventory()), Map.copyOf(found),
                s.hits().size(), truncated, (System.nanoTime() - t0) / 1_000_000);
    }

    /** Connected components: blocks within JOIN of each other (every axis) are one group. Null: out of time. */
    static List<Survey.Group> groups(Kind kind, Map<BlockPos, String> blocks, Vec3 from, long deadline) {
        int join = SurveyCatalog.JOIN.getOrDefault(kind, 1);
        Set<BlockPos> seen = new HashSet<>();
        List<Survey.Group> out = new ArrayList<>();
        int checked = 0;
        for (BlockPos start : blocks.keySet()) {
            if (!seen.add(start)) continue;
            List<BlockPos> members = new ArrayList<>();
            ArrayDeque<BlockPos> todo = new ArrayDeque<>();
            todo.add(start);
            while (!todo.isEmpty()) {
                BlockPos p = todo.poll();
                members.add(p);
                if (++checked % 256 == 0 && System.nanoTime() > deadline) return null;
                for (int dx = -join; dx <= join; dx++) for (int dy = -join; dy <= join; dy++) for (int dz = -join; dz <= join; dz++) {
                    BlockPos n = new BlockPos(p.x() + dx, p.y() + dy, p.z() + dz);
                    if (blocks.containsKey(n) && seen.add(n)) todo.add(n);
                }
            }
            out.add(group(kind, members, blocks, from));
        }
        out.sort(Comparator.comparingDouble(Survey.Group::distance));
        return out;
    }

    private static Survey.Group group(Kind kind, List<BlockPos> members, Map<BlockPos, String> blocks, Vec3 from) {
        BlockPos pos;
        if (kind == Kind.LOG) {                               // the base: lowest log, nearest us among those
            int low = members.stream().mapToInt(BlockPos::y).min().orElseThrow();
            pos = members.stream().filter(p -> p.y() == low)
                    .min(Comparator.comparingDouble(p -> Survey.distance(from, p))).orElseThrow();
        } else {
            pos = members.stream().min(Comparator.comparingDouble(p -> Survey.distance(from, p))).orElseThrow();
        }
        boolean huge = kind == Kind.LOG && hasTwoByTwo(members, blocks);
        return new Survey.Group(kind, blocks.get(pos), pos, members.size(), huge, Survey.distance(from, pos));
    }

    /** A 2×2 of logs at one height: a huge tree's trunk. */
    static boolean hasTwoByTwo(List<BlockPos> members, Map<BlockPos, String> blocks) {
        for (BlockPos p : members) {
            if (blocks.containsKey(new BlockPos(p.x() + 1, p.y(), p.z()))
                    && blocks.containsKey(new BlockPos(p.x(), p.y(), p.z() + 1))
                    && blocks.containsKey(new BlockPos(p.x() + 1, p.y(), p.z() + 1))) return true;
        }
        return false;
    }

    /** Counts by item id, sorted by id. */
    static Map<String, Integer> inventory(List<ItemView> items) {
        Map<String, Integer> out = new TreeMap<>();
        for (ItemView i : items) out.merge(i.id(), i.count(), Integer::sum);
        return java.util.Collections.unmodifiableMap(out);
    }

    /** "3 bread 5/6.0" — each food carried with the game's live hunger / saturation (as /zbot foods). */
    static List<String> foods(List<ItemView> items) {
        Map<String, ItemView> kinds = new TreeMap<>();
        Map<String, Integer> counts = new HashMap<>();
        for (ItemView i : items) {
            if (!i.edible()) continue;
            kinds.putIfAbsent(i.id(), i);
            counts.merge(i.id(), i.count(), Integer::sum);
        }
        List<String> out = new ArrayList<>();
        for (ItemView i : kinds.values()) {
            out.add(String.format(Locale.ROOT, "%d %s %d/%.1f%s", counts.get(i.id()), i.id().substring(i.id().indexOf(':') + 1),
                    i.food().nutrition(), i.food().saturation(), i.food().heals() ? " (heals)" : i.food().harmful() ? " (harmful)" : ""));
        }
        return List.copyOf(out);
    }
}
