package dev.yuliang.zymbot.core;

import static org.junit.jupiter.api.Assertions.*;

import dev.yuliang.zymbot.core.api.BlockPos;
import dev.yuliang.zymbot.core.api.ItemView;
import dev.yuliang.zymbot.core.api.Vec3;
import dev.yuliang.zymbot.core.config.ZymbotConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** PHASE3.md step 4 and §6 checks 1–2: M0 from an empty inventory to a wooden axe and hoe, every step logged. */
class LadderTest {
    @TempDir Path dir;
    final FakeClock clock = new FakeClock();
    final List<String> log = new ArrayList<>();

    Bot bot(FakeWorld w) {
        Bot b = new Bot(new ZymbotConfig(), dir.resolve("z.json"), dir.resolve("m"), w.id, true, clock);
        b.decisions().onRecord(e -> log.add(e.toString()));
        b.onJoin("127.0.0.1:25565", w.name);
        b.start("test");
        return b;
    }

    /** One tick: the clock moves, the pathfinder gets there at once, and the drops it walks onto are picked up. */
    void tick(Bot b, FakeWorld w) {
        clock.now += 50;
        b.tick(w, w);
        if (w.paths.busy) w.paths.arrive();
        w.pickUpNear();
    }

    boolean run(Bot b, FakeWorld w, int maxTicks) {
        for (int i = 0; i < maxTicks; i++) {
            tick(b, w);
            if (count(w, "minecraft:wooden_axe") > 0 && count(w, "minecraft:wooden_hoe") > 0) {
                for (int j = 0; j < 30; j++) tick(b, w);       // the last craft settles and is logged
                return true;
            }
        }
        return false;
    }

    static int count(FakeWorld w, String id) {
        return w.inventory.stream().filter(i -> i.id().equals(id)).mapToInt(ItemView::count).sum();
    }

    int first(String prefix) {
        for (int i = 0; i < log.size(); i++) if (log.get(i).startsWith(prefix)) return i;
        return -1;
    }

    @Test
    void coldStart_logsPlanksTableSticksAxeHoe_inThatOrder() {
        FakeWorld w = new FakeWorld("Bot1");
        w.pos = new Vec3(0.5, 64, 0.5);
        w.tree(5, 64, 0, 5, "minecraft:oak_log", false);
        Bot b = bot(w);
        assertTrue(run(b, w, 6000), String.join("\n", log));

        int logs = first("picked up 1 oak_log"), planks = first("crafted 12 oak_planks"), table = first("crafted 1 crafting_table"),
                placed = first("placed crafting_table"), sticks = first("crafted 4 stick"), axe = first("crafted 1 wooden_axe"),
                hoe = first("crafted 1 wooden_hoe");
        assertTrue(logs >= 0 && logs < planks && planks < table && table < placed && placed < sticks && sticks < axe && axe < hoe,
                String.join("\n", log));
        assertEquals(3, log.stream().filter(l -> l.startsWith("picked up 1 oak_log")).count(), "3 logs: 11 planks' worth");
        assertTrue(log.stream().anyMatch(l -> l.contains("M0 step 1/5")), String.join("\n", log));
        assertEquals("minecraft:oak_log", w.blockAt(new BlockPos(5, 67, 0)), "stopped punching once it had enough");
        assertNull(w.menuKind, "no screen left open");

        tick(b, w);
        assertTrue(b.status().stream().anyMatch(l -> l.equals("ladder: all met (up to M0 HAS_TOOL)")), b.status().toString());
    }

    @Test
    void aTableAlreadyNear_isUsed_notDoubled() {
        FakeWorld w = new FakeWorld("Bot1");
        w.pos = new Vec3(0.5, 64, 0.5);
        w.tree(5, 64, 0, 5, "minecraft:oak_log", false);
        BlockPos theirs = new BlockPos(-3, 64, 3);
        w.blocks.put(theirs, "minecraft:crafting_table");
        Bot b = bot(w);
        assertTrue(run(b, w, 6000), String.join("\n", log));
        assertEquals(-1, first("crafted 1 crafting_table"), String.join("\n", log));
        assertEquals(-1, first("placed crafting_table"), String.join("\n", log));
        assertEquals(2, log.stream().filter(l -> l.startsWith("picked up 1 oak_log")).count(), "2 logs: no table to make");
        assertEquals(1, w.blocks.values().stream().filter("minecraft:crafting_table"::equals).count());
    }

    @Test
    void halfDone_picksUpWhereItIs() {
        FakeWorld w = new FakeWorld("Bot1");
        w.pos = new Vec3(0.5, 64, 0.5);
        w.give(9, "minecraft:spruce_planks", 9, null);
        w.give(10, "minecraft:crafting_table", 1, null);
        Bot b = bot(w);
        assertTrue(run(b, w, 6000), String.join("\n", log));
        assertEquals(-1, first("breaking"), "had wood enough already: " + String.join("\n", log));
        assertTrue(first("placed crafting_table") >= 0, String.join("\n", log));
    }

    @Test
    void aTreeItCantReach_isLeftForAnother() {
        FakeWorld w = new FakeWorld("Bot1");
        w.pos = new Vec3(0.5, 64, 0.5);
        w.tree(4, 64, 0, 4, "minecraft:birch_log", false);       // nearest, but behind glass
        for (int y = 64; y < 68; y++) w.hidden.add(new BlockPos(4, y, 0));
        w.tree(-9, 64, 0, 5, "minecraft:oak_log", false);
        Bot b = bot(w);
        assertTrue(run(b, w, 8000), String.join("\n", log));
        assertTrue(log.stream().anyMatch(l -> l.startsWith("failed: ") && l.contains("birch_log")), String.join("\n", log));
        assertEquals("minecraft:birch_log", w.blockAt(new BlockPos(4, 64, 0)));
        assertTrue(count(w, "minecraft:wooden_hoe") > 0);
    }

    @Test
    void loneOrStrippedLogs_areAPlayersBuild_notATree() {
        FakeWorld w = new FakeWorld("Bot1");
        w.pos = new Vec3(0.5, 64, 0.5);
        w.blocks.put(new BlockPos(3, 64, 0), "minecraft:oak_log");                 // one log on its own
        w.tree(-4, 64, 0, 4, "minecraft:stripped_oak_log", false);                 // a stripped pillar
        Bot b = bot(w);
        for (int i = 0; i < 200; i++) tick(b, w);
        assertEquals(-1, first("breaking"), String.join("\n", log));
        assertTrue(b.status().stream().anyMatch(l -> l.startsWith("ladder: M0 HAS_TOOL — waiting: no tree to punch")), b.status().toString());
    }

    /** With nobody from the team about, a bot with work here does it instead of walking off to spawn (2026-10-14). */
    @Test
    void noTeam_worksBeforeWalkingToSpawn() {
        FakeWorld w = new FakeWorld("Bot1");
        w.pos = new Vec3(0.5, 64, 0.5);
        w.spawn = new BlockPos(300, 64, 0);
        w.tree(5, 64, 0, 5, "minecraft:oak_log", false);
        Bot b = bot(w);
        for (int i = 0; i < 5; i++) tick(b, w);
        clock.now += 31_000;                                    // nobody answered in the 30 s probe wait
        assertTrue(run(b, w, 6000), String.join("\n", log));
        int hoe = first("crafted 1 wooden_hoe");
        assertTrue(hoe > 0 && first("going to spawn") > hoe, "the tools first, then spawn: " + String.join("\n", log));
    }

    @Test
    void debugOrders_craftAndPlace() {
        FakeWorld w = new FakeWorld("Bot1");
        w.pos = new Vec3(0.5, 64, 0.5);
        w.give(9, "minecraft:oak_log", 2, null);
        Bot b = bot(w);
        tick(b, w);
        assertEquals("crafting 2× planks in the inventory grid", b.craft("planks"));
        for (int i = 0; i < 200 && count(w, "minecraft:oak_planks") == 0; i++) tick(b, w);
        assertEquals(8, count(w, "minecraft:oak_planks"));
        assertTrue(b.craft("axe").startsWith("wooden axe needs a crafting table"), b.craft("axe"));
        assertEquals("no crafting table in the inventory", b.placeTable());
        assertTrue(b.craft("sword").startsWith("craft what?"));
    }
}
