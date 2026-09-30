package dev.yuliang.zymbot.core;

import static org.junit.jupiter.api.Assertions.*;

import dev.yuliang.zymbot.core.api.BlockPos;
import dev.yuliang.zymbot.core.api.Vec3;
import dev.yuliang.zymbot.core.task.DiveTask;
import dev.yuliang.zymbot.core.task.Task.Status;
import org.junit.jupiter.api.Test;

/** Diving to a grave 6 blocks under water (owner, 2026-09-30). */
class DiveTaskTest {
    private static final BlockPos GRAVE = new BlockPos(79, 56, -48);

    @Test
    void besideIt_swimsOverAndSinks() {
        FakeWorld w = new FakeWorld("Bot1");
        w.pos = new Vec3(76.5, 62, -47.5);
        DiveTask t = new DiveTask(w, GRAVE);
        assertEquals(Status.RUNNING, t.tick(w));
        assertTrue(w.forwardHeld);
        assertTrue(w.sneakHeld);
        assertFalse(w.jumpHeld);
    }

    @Test
    void rightAbove_justSinks_thenDoneInReach() {
        FakeWorld w = new FakeWorld("Bot1");
        w.pos = new Vec3(79.5, 62, -47.5);
        DiveTask t = new DiveTask(w, GRAVE);
        assertEquals(Status.RUNNING, t.tick(w));
        assertFalse(w.forwardHeld);
        assertTrue(w.sneakHeld);
        w.pos = new Vec3(79.5, 59, -47.5);
        assertEquals(Status.DONE, t.tick(w));
        assertFalse(w.sneakHeld);
    }

    @Test
    void lowOnAir_givesUpBeforeTheDrowningReflex() {
        FakeWorld w = new FakeWorld("Bot1");
        w.pos = new Vec3(79.5, 62, -47.5);
        w.air = 200;
        DiveTask t = new DiveTask(w, GRAVE);
        assertEquals(Status.FAILED, t.tick(w));
        assertFalse(w.sneakHeld);
    }
}
