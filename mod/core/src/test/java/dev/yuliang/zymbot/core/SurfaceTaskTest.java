package dev.yuliang.zymbot.core;

import static org.junit.jupiter.api.Assertions.*;

import dev.yuliang.zymbot.core.api.BlockPos;
import dev.yuliang.zymbot.core.api.Vec3;
import dev.yuliang.zymbot.core.task.SurfaceTask;
import org.junit.jupiter.api.Test;

/**
 * Climbing out of the water face-on. Aimed straight at the nearest land, Bot1 came in over beach corners
 * (land touching its water only diagonally), caught its hitbox on the two side blocks and spun in place
 * for 10 s: 15327 degrees, 1.4 blocks (2026-09-29).
 */
class SurfaceTaskTest {
    private static final BlockPos LAND = new BlockPos(10, 62, 10);

    @Test
    void cornerOnlyShore_isNotAPlaceToClimbOut() {
        FakeWorld w = new FakeWorld("Bot1");
        w.pos = new Vec3(9.5, 62, 9.5);
        w.blocks.put(new BlockPos(9, 62, 9), "minecraft:water");      // only diagonal to the land
        assertNull(SurfaceTask.launchFor(w, LAND, w.pos));
        assertFalse(SurfaceTask.faceOnShore(w).test(LAND));
    }

    @Test
    void waterStraightBeside_isWhereItClimbsOutFrom() {
        FakeWorld w = new FakeWorld("Bot1");
        w.pos = new Vec3(9.5, 62, 9.5);
        w.blocks.put(new BlockPos(9, 62, 9), "minecraft:water");      // the corner it was in
        w.blocks.put(new BlockPos(9, 62, 10), "minecraft:water");     // west of the land: face-on
        assertEquals(new BlockPos(9, 62, 10), SurfaceTask.launchFor(w, LAND, w.pos));
        assertTrue(SurfaceTask.faceOnShore(w).test(LAND));
    }

    @Test
    void waterOneUp_countsToo_theNearestSideWins() {
        FakeWorld w = new FakeWorld("Bot1");
        w.pos = new Vec3(12.5, 63, 10.5);
        w.blocks.put(new BlockPos(9, 63, 10), "minecraft:water");     // west, one up (a lower bank)
        w.blocks.put(new BlockPos(11, 62, 10), "minecraft:water");    // east, level: nearer
        assertEquals(new BlockPos(11, 62, 10), SurfaceTask.launchFor(w, LAND, w.pos));
    }

    @Test
    void aShelfUnderWater_isNotDryLand() {
        FakeWorld w = new FakeWorld("Bot1");
        w.pos = new Vec3(9.5, 62, 10.5);
        w.blocks.put(new BlockPos(9, 62, 10), "minecraft:water");     // west: face-on
        w.blocks.put(new BlockPos(10, 63, 10), "minecraft:water");    // but the land itself is under water
        assertFalse(SurfaceTask.faceOnShore(w).test(LAND));
    }
}
