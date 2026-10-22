package io.github.liangforstudy.zymbot.core;

import static org.junit.jupiter.api.Assertions.*;

import io.github.liangforstudy.zymbot.core.api.BlockPos;
import io.github.liangforstudy.zymbot.core.api.Vec3;
import io.github.liangforstudy.zymbot.core.config.ZymbotConfig;
import io.github.liangforstudy.zymbot.core.task.BreakTask;
import io.github.liangforstudy.zymbot.core.task.Task.Status;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BreakTaskTest {
    @TempDir Path dir;
    final FakeClock clock = new FakeClock();
    final List<String> log = new ArrayList<>();
    static final BlockPos LOG = new BlockPos(20, 64, 0);

    BreakTask task(FakeWorld w) {
        return new BreakTask(w, LOG, "ordered by /zbot punch", (what, why) -> log.add(what + " — because " + why));
    }

    FakeWorld world() {
        FakeWorld w = new FakeWorld("Bot1");
        w.blocks.put(LOG, "minecraft:oak_log");
        return w;
    }

    @Test
    void walksIntoReach_thenBreaksWithTheAxe_thenPicksUpTheLog() {
        FakeWorld w = world();
        w.give(3, "minecraft:wooden_axe", 1, null);
        BreakTask t = task(w);
        assertEquals(Status.RUNNING, t.tick(w));
        assertEquals("breaking minecraft:oak_log at 20 64 0 — because ordered by /zbot punch", log.get(0));
        assertEquals(LOG, w.paths.goal, "walks there first: it's 20 blocks away");
        assertNull(w.mining, "no digging out of reach");

        w.pos = new Vec3(18.5, 64, 0.5);                        // arrived, 2 blocks short
        w.paths.busy = false;
        assertEquals(Status.RUNNING, t.tick(w));
        assertEquals(3, w.selected, "holds the axe for a log");
        for (int i = 0; i < 3; i++) assertEquals(Status.RUNNING, t.tick(w));
        assertEquals("minecraft:air", w.blockAt(LOG));
        assertNull(w.mining);

        assertEquals(Status.RUNNING, t.tick(w));               // sees the drop, walks over it
        assertEquals(LOG, w.paths.goal);
        w.paths.arrive();
        w.pickUpNear();
        assertEquals(Status.DONE, t.tick(w));
        assertTrue(w.inventory.stream().anyMatch(i -> i.id().equals("minecraft:oak_log")));
        assertTrue(log.stream().anyMatch(l -> l.startsWith("picked up 1 oak_log")), log.toString());
        assertTrue(w.stopMiningCalls > 0, "let go of attack");
    }

    @Test
    void bareHands_whenNoTool() {
        FakeWorld w = world();
        w.pos = new Vec3(18.5, 64, 0.5);
        BreakTask t = task(w);
        t.tick(w);
        assertEquals(LOG, w.mining, "digs at once, bare-handed");
        assertEquals(0, w.selected);
    }

    @Test
    void collect_givesUpAfterFiveSeconds() {
        FakeWorld w = world();
        w.pos = new Vec3(18.5, 64, 0.5);
        w.digTicks = 1;
        BreakTask t = task(w);
        t.tick(w);                                               // broken
        Status s = Status.RUNNING;
        int n = 0;
        while (s == Status.RUNNING && n++ < 200) s = t.tick(w);  // never walks onto it
        assertEquals(Status.DONE, s);
        assertTrue(n > BreakTask.COLLECT_TICKS && n < BreakTask.COLLECT_TICKS + 5, "n=" + n);
        assertTrue(log.stream().anyMatch(l -> l.startsWith("picked up nothing") && l.contains("left 1")), log.toString());
    }

    @Test
    void fails_whenOutOfReachAndNoPath() {
        FakeWorld w = world();
        w.paths.available = false;
        BreakTask t = task(w);
        assertEquals(Status.FAILED, t.tick(w));
        assertTrue(t.failure().startsWith("out of reach and no path"), t.failure());
    }

    @Test
    void fails_whenStandingThereButItCantBeSeen() {
        FakeWorld w = world();
        w.pos = new Vec3(18.5, 64, 0.5);
        w.hidden.add(LOG);
        BreakTask t = task(w);
        assertEquals(Status.FAILED, t.tick(w));
        assertTrue(t.failure().contains("can't see it"), t.failure());
    }

    /** 2026-10-14: a vine in front of the log, and the bot gave the tree up. */
    @Test
    void aVineInTheWay_isClearedFirst_butAWallIsNot() {
        FakeWorld w = world();
        w.pos = new Vec3(18.5, 64, 0.5);
        BlockPos vine = new BlockPos(19, 64, 0);
        w.blocks.put(vine, "minecraft:vine");
        w.blockers.put(LOG, vine);
        BreakTask t = task(w);
        Status s = Status.RUNNING;
        for (int i = 0; i < 100 && s == Status.RUNNING; i++) {
            s = t.tick(w);
            if (w.paths.busy) w.paths.arrive();
            w.pickUpNear();
        }
        assertEquals(Status.DONE, s, t.failure() + " " + log);
        assertEquals("minecraft:air", w.blockAt(vine));
        assertEquals("minecraft:air", w.blockAt(LOG));
        assertTrue(log.stream().anyMatch(l -> l.startsWith("breaking minecraft:vine") && l.contains("in the way of oak_log")), log.toString());

        FakeWorld wall = world();
        wall.pos = new Vec3(18.5, 64, 0.5);
        BlockPos stone = new BlockPos(19, 64, 0);
        wall.blocks.put(stone, "minecraft:stone");
        wall.blockers.put(LOG, stone);
        BreakTask t2 = new BreakTask(wall, LOG, "test", (a, b) -> {});
        Status s2 = Status.RUNNING;
        for (int i = 0; i < 100 && s2 == Status.RUNNING; i++) {
            s2 = t2.tick(wall);
            if (wall.paths.busy) wall.paths.arrive();
        }
        assertEquals(Status.FAILED, s2);
        assertEquals("minecraft:stone", wall.blockAt(stone), "never digs through a wall to get a log");
        assertTrue(t2.failure().contains("stone in the way"), t2.failure());
    }

    @Test
    void chop_punchesThreeLogs_thenSweepsOnce() {
        FakeWorld w = new FakeWorld("Bot1");
        w.tree(20, 64, 0, 5, "minecraft:oak_log", false);
        io.github.liangforstudy.zymbot.core.task.ChopTask t = new io.github.liangforstudy.zymbot.core.task.ChopTask(w, LOG, 3, "test",
                (what, why) -> log.add(what + " — because " + why));
        Status s = Status.RUNNING;
        for (int i = 0; i < 2000 && s == Status.RUNNING; i++) {
            s = t.tick(w);
            if (w.paths.busy) w.paths.arrive();
            w.pickUpNear();
        }
        assertEquals(Status.DONE, s, t.failure() + " " + log);
        assertEquals(3, log.stream().filter(l -> l.startsWith("broke ")).count(), log.toString());
        assertEquals("minecraft:oak_log", w.blockAt(new BlockPos(20, 67, 0)), "stops at three");
        int pickedAt = -1, lastBroke = -1;
        for (int i = 0; i < log.size(); i++) {
            if (log.get(i).startsWith("broke ")) lastBroke = i;
            if (log.get(i).startsWith("picked up")) pickedAt = i;
        }
        assertTrue(pickedAt > lastBroke, "one sweep, after the punching: " + log);
        assertTrue(log.get(pickedAt).startsWith("picked up 3 oak_log — because swept around the oak_log tree at 20 0"), log.toString());
    }

    @Test
    void fails_whenTheBlockChangesOrIsGone() {
        FakeWorld w = world();
        BreakTask t = task(w);
        t.tick(w);
        w.blocks.put(LOG, "minecraft:stone");
        assertEquals(Status.FAILED, t.tick(w));
        assertTrue(t.failure().contains("changed"), t.failure());

        FakeWorld air = new FakeWorld("Bot2");
        BreakTask t2 = new BreakTask(air, LOG, "test", (a, b) -> {});
        assertEquals(Status.FAILED, t2.tick(air));
        assertTrue(t2.failure().startsWith("nothing to break"), t2.failure());
    }

    @Test
    void fails_whenTooHungry() {
        FakeWorld w = world();
        w.hunger = 6;
        BreakTask t = task(w);
        assertEquals(Status.FAILED, t.tick(w));
        assertTrue(t.failure().startsWith("too hungry"), t.failure());
    }

    @Test
    void fails_whenItTakesTooLong_andLetsGo() {
        FakeWorld w = world();
        w.pos = new Vec3(18.5, 64, 0.5);
        w.digTicks = 100_000;
        BreakTask t = task(w);
        Status s = Status.RUNNING;
        int n = 0;
        while (s == Status.RUNNING && n++ < 2000) s = t.tick(w);
        assertEquals(Status.FAILED, s);
        assertTrue(t.failure().startsWith("took too long"), t.failure());
        assertNull(w.mining, "attack released");
    }

    @Test
    void cancel_releasesAttack() {
        FakeWorld w = world();
        w.pos = new Vec3(18.5, 64, 0.5);
        w.digTicks = 100;
        BreakTask t = task(w);
        t.tick(w);
        t.tick(w);
        assertEquals(LOG, w.mining);
        t.cancel();
        assertNull(w.mining);
        assertEquals("minecraft:oak_log", w.blockAt(LOG));
    }

    @Test
    void punchOrder_breaks_andStoppingTheBotLetsGo() {
        FakeWorld w = world();
        w.pos = new Vec3(18.5, 64, 0.5);
        w.digTicks = 100;
        Bot b = new Bot(new ZymbotConfig(), dir.resolve("z.json"), dir.resolve("m"), w.id, true, clock);
        b.onJoin("127.0.0.1:25565", w.name);
        b.start("test");
        tick(b, w);
        assertEquals("breaking minecraft:oak_log at 20 64 0", b.punch(20, 64, 0));
        for (int i = 0; i < 5; i++) tick(b, w);
        assertEquals(LOG, w.mining);
        b.cancel();
        tick(b, w);
        assertNull(w.mining, "cancel lets go of attack");

        b.punch(20, 64, 0);
        for (int i = 0; i < 5; i++) tick(b, w);
        assertEquals(LOG, w.mining);
        b.stop("test");
        assertNull(w.mining, "stop lets go of attack");
    }

    void tick(Bot b, FakeWorld w) {
        clock.now += 50;
        b.tick(w, w);
    }
}
