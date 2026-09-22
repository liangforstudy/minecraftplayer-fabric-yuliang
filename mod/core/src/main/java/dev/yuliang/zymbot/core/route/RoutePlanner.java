package dev.yuliang.zymbot.core.route;

import dev.yuliang.zymbot.core.api.BlockPos;
import dev.yuliang.zymbot.core.api.Terrain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;

/**
 * A* over the surface, one column per node, within a square around the start. Cost is hunger, not
 * time: a block of land costs 1, a block of water costs {@code swimCost} (~3 here — see
 * ZymbotConfig.swimCostBlocks). Walls taller than a jump, big drops, lava and the like
 * are impassable. Baritone still walks each leg; this only decides <em>where</em> (and whether) to
 * cross water (PHASE1.md → Water, Step B).
 * <p>
 * If the goal is outside the loaded area, or can't be reached, the route ends at the reachable
 * column closest to it — the caller walks there and plans again.
 */
public final class RoutePlanner {
    public static final int MAX_UP = 1;
    public static final int MAX_DOWN = 3;
    /**
     * Each block climbed costs this many blocks of flat walking: a jump is 0.05 exhaustion, ~5 blocks'
     * worth of walking drain — and the hunger meter showed hills cost far more than flat ground.
     */
    public static final double CLIMB_COST = 5;
    /** Per land block: walking drains 0.1 per 10 s at ~43 blocks per 10 s. */
    public static final double FOOD_PER_LAND_BLOCK = 0.1 / 43.17;
    /**
     * Per water block, crossing at a normal pace (not sprint-swimming): walking's drain at about half
     * the speed, plus vanilla's 0.01 exhaustion per metre in water (= 0.0025 food).
     */
    public static final double FOOD_PER_WATER_BLOCK = 0.1 / 20 + 0.0025;

    public record Step(int x, int y, int z, boolean water) {}

    /** {@code reachesGoal}: ends at the goal column; otherwise at the closest reachable one. */
    public record Route(List<Step> steps, double landBlocks, double waterBlocks, boolean reachesGoal) {
        public double food() { return landBlocks * FOOD_PER_LAND_BLOCK + waterBlocks * FOOD_PER_WATER_BLOCK; }
        public double length() { return landBlocks + waterBlocks; }
    }

    private final Terrain terrain;
    private final int ox, oz, radius, side;
    private final byte[] kind;            // 0 = not read yet, else Terrain.Kind ordinal + 1
    private final int[] height;

    public RoutePlanner(Terrain terrain, int originX, int originZ, int radius) {
        this.terrain = terrain;
        this.ox = originX;
        this.oz = originZ;
        this.radius = radius;
        this.side = 2 * radius + 1;
        this.kind = new byte[side * side];
        this.height = new int[side * side];
    }

    /** @param swimCost cost of a water block; 0 or less forbids water */
    public Optional<Route> plan(BlockPos from, BlockPos goal, double swimCost) {
        int start = index(from.x(), from.z());
        if (start < 0 || kindAt(start) == Terrain.Kind.UNLOADED || kindAt(start) == Terrain.Kind.BLOCKED) return Optional.empty();
        int goalIdx = index(goal.x(), goal.z());
        double[] g = new double[side * side];
        Arrays.fill(g, Double.MAX_VALUE);
        int[] came = new int[side * side];
        boolean[] closed = new boolean[side * side];
        g[start] = 0;
        came[start] = -1;
        PriorityQueue<double[]> open = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        open.add(new double[]{h(start, goal), start});
        int best = start;
        double bestH = h(start, goal);
        while (!open.isEmpty()) {
            int cur = (int) open.poll()[1];
            if (closed[cur]) continue;
            closed[cur] = true;
            double hc = h(cur, goal);
            if (hc < bestH) { bestH = hc; best = cur; }
            if (cur == goalIdx) break;
            int cx = cur / side, cz = cur % side;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                int nx = cx + dx, nz = cz + dz;
                if (nx < 0 || nz < 0 || nx >= side || nz >= side) continue;
                int n = nx * side + nz;
                if (closed[n]) continue;
                double step = passable(cur, n, swimCost);
                if (step < 0) continue;
                int climb = Math.max(0, height[n] - height[cur]);
                double cost = g[cur] + step * (dx != 0 && dz != 0 ? Math.sqrt(2) : 1) + climb * CLIMB_COST;
                if (cost < g[n]) {
                    g[n] = cost;
                    came[n] = cur;
                    open.add(new double[]{cost + h(n, goal), n});
                }
            }
        }
        int end = goalIdx >= 0 && closed[goalIdx] ? goalIdx : best;
        if (end == start) return Optional.empty();
        List<Step> steps = new ArrayList<>();
        double land = 0, water = 0;
        for (int at = end; at != -1; at = came[at]) {
            boolean wet = kindAt(at) == Terrain.Kind.WATER;
            steps.add(new Step(x(at), height[at], z(at), wet));
            int prev = came[at];
            if (prev != -1) {
                double d = (x(at) != x(prev) && z(at) != z(prev)) ? Math.sqrt(2) : 1;
                if (wet) water += d; else land += d;
            }
        }
        Collections.reverse(steps);
        return Optional.of(new Route(List.copyOf(steps), land, water, end == goalIdx));
    }

    /** Cost multiplier for stepping from a to b, or -1 if you can't. */
    private double passable(int a, int b, double swimCost) {
        Terrain.Kind kb = kindAt(b);
        if (kb == Terrain.Kind.UNLOADED || kb == Terrain.Kind.BLOCKED) return -1;
        int dy = height[b] - height[a];
        if (dy > MAX_UP || dy < -MAX_DOWN) return -1;
        if (kb == Terrain.Kind.WATER) return swimCost > 0 ? swimCost : -1;
        return 1;
    }

    private Terrain.Kind kindAt(int i) {
        if (kind[i] == 0) {
            int x = x(i), z = z(i);
            Terrain.Kind k = terrain.kind(x, z);
            kind[i] = (byte) (k.ordinal() + 1);
            if (k == Terrain.Kind.LAND || k == Terrain.Kind.WATER) height[i] = terrain.height(x, z);
        }
        return Terrain.Kind.values()[kind[i] - 1];
    }

    private double h(int i, BlockPos goal) {
        double dx = Math.abs(x(i) - goal.x()), dz = Math.abs(z(i) - goal.z());
        return Math.max(dx, dz) + (Math.sqrt(2) - 1) * Math.min(dx, dz);   // octile, land cost: admissible
    }

    private int index(int x, int z) {
        int ix = x - ox + radius, iz = z - oz + radius;
        return ix < 0 || iz < 0 || ix >= side || iz >= side ? -1 : ix * side + iz;
    }

    private int x(int i) { return i / side - radius + ox; }
    private int z(int i) { return i % side - radius + oz; }

    // ------------------------------------------------------------------ legs

    /** A stretch Baritone walks in one go: dry, or one crossing ({@code swim}), shore to shore. */
    public record Leg(BlockPos end, boolean swim, double length) {}

    /**
     * Splits a route into legs: a dry leg ends on the near shore; a swim leg runs from there to the
     * first dry step on the far side; dry stretches are also cut every {@code maxLand} blocks.
     */
    public static List<Leg> legs(Route route, int maxLand) {
        List<Leg> out = new ArrayList<>();
        List<Step> s = route.steps();
        boolean swimming = !s.isEmpty() && s.get(0).water();
        double run = 0;
        for (int i = 1; i < s.size(); i++) {
            Step a = s.get(i - 1), b = s.get(i);
            run += (a.x() != b.x() && a.z() != b.z()) ? Math.sqrt(2) : 1;
            boolean last = i == s.size() - 1;
            if (swimming) {
                if (!b.water() || last) {                   // out on the far shore
                    out.add(new Leg(new BlockPos(b.x(), b.y(), b.z()), true, run));
                    run = 0;
                    swimming = false;
                }
            } else if (b.water()) {
                swimming = true;                             // (only if a dry leg didn't end on the shore)
            } else {
                boolean waterNext = !last && s.get(i + 1).water();
                if (waterNext || last || run >= maxLand) {
                    out.add(new Leg(new BlockPos(b.x(), b.y(), b.z()), false, run));
                    run = 0;
                    swimming = waterNext;
                }
            }
        }
        return out;
    }
}
