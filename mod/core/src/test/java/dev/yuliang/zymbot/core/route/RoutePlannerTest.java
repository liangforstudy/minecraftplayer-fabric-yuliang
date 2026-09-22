package dev.yuliang.zymbot.core.route;

import static org.junit.jupiter.api.Assertions.*;

import dev.yuliang.zymbot.core.api.BlockPos;
import dev.yuliang.zymbot.core.api.Terrain;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** PHASE1.md → Water, Step B: where (and whether) to cross, on drawn terrain. */
public class RoutePlannerTest {
    /** Rows are z, columns are x. '.' land (y 64), '~' water (surface 63), '#' blocked, '^' a cliff (y 70). */
    public static Terrain map(String... rows) {
        return new Terrain() {
            public Kind kind(int x, int z) {
                if (z < 0 || z >= rows.length || x < 0 || x >= rows[z].length()) return Kind.UNLOADED;
                return switch (rows[z].charAt(x)) {
                    case '~' -> Kind.WATER;
                    case '#' -> Kind.BLOCKED;
                    default -> Kind.LAND;
                };
            }
            public int height(int x, int z) {
                char c = rows[z].charAt(x);
                return c == '^' ? 70 : c == '~' ? 63 : 64;
            }
        };
    }

    public static String[] grid(int w, int h, char fill) {
        String[] rows = new String[h];
        Arrays.fill(rows, String.valueOf(fill).repeat(w));
        return rows;
    }

    public static void paint(String[] rows, int x0, int z0, int x1, int z1, char c) {
        for (int z = z0; z <= z1; z++) {
            char[] r = rows[z].toCharArray();
            for (int x = x0; x <= x1; x++) r[x] = c;
            rows[z] = new String(r);
        }
    }

    static RoutePlanner.Route plan(String[] rows, BlockPos from, BlockPos to, double swimCost) {
        return new RoutePlanner(map(rows), from.x(), from.z(), 96).plan(from, to, swimCost).orElseThrow();
    }

    @Test
    void aBridgeWithinReach_beatsSwimming() {
        String[] m = grid(80, 40, '.');
        paint(m, 0, 18, 79, 21, '~');                         // a 4-wide river across the map
        paint(m, 50, 18, 51, 21, '.');                        // ...with a bridge 40 blocks along
        var r = plan(m, new BlockPos(10, 64, 5), new BlockPos(10, 64, 35), 85);
        assertEquals(0, r.waterBlocks(), "walks 80 extra blocks rather than swim 4 (= 340)");
        assertTrue(r.reachesGoal());
    }

    @Test
    void atTheMeasuredSwimCost_aFarBridgeLoses_aNearOneWins() {
        String[] m = grid(80, 40, '.');
        paint(m, 0, 18, 79, 21, '~');                         // a 4-wide river
        paint(m, 50, 18, 51, 21, '.');                        // bridge 40 blocks along: 80 extra blocks of walking
        var far = plan(m, new BlockPos(10, 64, 5), new BlockPos(10, 64, 35), 3);
        assertTrue(far.waterBlocks() > 0, "4 blocks of water at ×3 = 12 beats an 80-block detour");
        paint(m, 12, 18, 13, 21, '.');                        // now a bridge right there
        var near = plan(m, new BlockPos(10, 64, 5), new BlockPos(10, 64, 35), 3);
        assertEquals(0, near.waterBlocks(), "a bridge a few blocks off still wins");
    }

    @Test
    void noBridge_crossesAtTheNarrowestPoint() {
        String[] m = grid(80, 40, '.');
        paint(m, 0, 14, 79, 25, '~');                         // 12 wide...
        paint(m, 60, 14, 62, 18, '.');                        // ...narrowed to 2 at x 60–62 by a spit of land
        paint(m, 60, 21, 62, 25, '.');
        var r = plan(m, new BlockPos(10, 64, 5), new BlockPos(10, 64, 35), 85);
        assertTrue(r.waterBlocks() > 0 && r.waterBlocks() <= 3, "swims only the 2-block gap: " + r.waterBlocks());
        assertTrue(r.steps().stream().filter(RoutePlanner.Step::water).allMatch(s -> s.x() >= 59 && s.x() <= 63));
    }

    @Test
    void aLakeIsWalkedRound() {
        String[] m = grid(70, 50, '.');
        paint(m, 20, 10, 45, 40, '~');
        var r = plan(m, new BlockPos(10, 64, 25), new BlockPos(60, 64, 25), 85);
        assertEquals(0, r.waterBlocks());
    }

    @Test
    void anIsland_swimsOnlyTheShortestGap_andTheLegsSaySo() {
        String[] m = grid(60, 60, '~');
        paint(m, 0, 0, 59, 19, '.');                          // mainland to the north
        paint(m, 25, 25, 35, 35, '.');                        // an island 5 blocks offshore
        var r = plan(m, new BlockPos(30, 64, 5), new BlockPos(30, 64, 30), 85);
        assertTrue(r.reachesGoal());
        assertTrue(r.waterBlocks() >= 5 && r.waterBlocks() <= 6, "just the gap: " + r.waterBlocks());
        List<RoutePlanner.Leg> legs = RoutePlanner.legs(r, 48);
        assertEquals(List.of(false, true, false), legs.stream().map(RoutePlanner.Leg::swim).toList(), legs.toString());
        assertEquals(19, legs.get(0).end().z(), "the dry leg stops on the near shore");
        assertEquals(25, legs.get(1).end().z(), "the swim ends on the island's shore");
    }

    @Test
    void cliffsAreWalkedRound() {
        String[] m = grid(40, 30, '.');
        paint(m, 0, 14, 29, 15, '^');                         // a 6-high wall with a gap at x 30+
        var r = plan(m, new BlockPos(5, 64, 5), new BlockPos(5, 64, 25), 85);
        assertTrue(r.steps().stream().noneMatch(s -> s.y() == 70), "never climbs the wall");
        assertTrue(r.reachesGoal());
    }

    @Test
    void aFlatDetour_beatsGoingOverAHill() {
        // a 1-block-high ridge across the map, open at the far end: climbing it costs 2 × 5 blocks
        Terrain t = new Terrain() {
            public Kind kind(int x, int z) { return x < 0 || z < 0 || x >= 40 || z >= 30 ? Kind.UNLOADED : Kind.LAND; }
            public int height(int x, int z) { return z >= 14 && z <= 15 && x < 32 ? 65 : 64; }
        };
        var detour = new RoutePlanner(t, 30, 5, 96).plan(new BlockPos(30, 64, 5), new BlockPos(30, 64, 25), 85).orElseThrow();
        assertTrue(detour.steps().stream().noneMatch(s -> s.y() == 65), "2 blocks round beats 10 blocks of climbing");
        var far = new RoutePlanner(t, 2, 5, 96).plan(new BlockPos(2, 64, 5), new BlockPos(2, 64, 25), 85).orElseThrow();
        assertTrue(far.steps().stream().anyMatch(s -> s.y() == 65), "60 blocks round is worse: go over");
    }

    @Test
    void aGoalBeyondTheLoadedArea_endsAsCloseAsItCan() {
        String[] m = grid(50, 50, '.');
        var r = plan(m, new BlockPos(10, 64, 10), new BlockPos(400, 64, 10), 85);
        assertFalse(r.reachesGoal());
        assertEquals(49, r.steps().get(r.steps().size() - 1).x(), "stops at the edge of what's loaded");
    }

    @Test
    void aDryRoute_isOneLeg_forBaritoneToWalkWhole() {
        String[] m = grid(130, 5, '.');
        var r = plan(m, new BlockPos(0, 64, 2), new BlockPos(95, 64, 2), 85);
        assertEquals(1, RoutePlanner.legs(r, Integer.MAX_VALUE).size());
    }

    @Test
    void longDryStretchesAreCutIntoLegs() {
        String[] m = grid(130, 5, '.');
        var r = plan(m, new BlockPos(0, 64, 2), new BlockPos(95, 64, 2), 85);   // within the 96-block view
        List<RoutePlanner.Leg> legs = RoutePlanner.legs(r, 48);
        assertEquals(List.of(48, 95), legs.stream().map(l -> l.end().x()).toList(), legs.toString());
        assertTrue(legs.stream().noneMatch(RoutePlanner.Leg::swim));
    }

    @Test
    void outOfTime_givesTheBestSoFar_andSaysSo() {
        Terrain drawn = map(grid(193, 193, '.'));
        Terrain slow = new Terrain() {
            public Kind kind(int x, int z) {
                long t = System.nanoTime() + 50_000;
                while (System.nanoTime() < t) Thread.onSpinWait();
                return z == 150 ? Kind.BLOCKED : drawn.kind(x, z);            // a wall: the goal is out of reach
            }
            public int height(int x, int z) { return drawn.height(x, z); }
        };
        long t0 = System.nanoTime();
        var r = new RoutePlanner(slow, 96, 96, 96)
                .plan(new BlockPos(96, 64, 96), new BlockPos(96, 64, 190), 3, new RoutePlanner.Budget(50, false))
                .orElseThrow();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(r.timedOut() && !r.reachesGoal(), r.toString());
        assertTrue(ms < 1000, "stopped near the budget, not after flooding the map: " + ms + " ms");
        var gentle = new RoutePlanner(map(grid(40, 40, '.')), 5, 5, 96)
                .plan(new BlockPos(5, 64, 5), new BlockPos(35, 64, 35), 3, new RoutePlanner.Budget(0, true))
                .orElseThrow();
        assertTrue(gentle.reachesGoal() && !gentle.timedOut(), "gentle still finishes: " + gentle);
    }
}
