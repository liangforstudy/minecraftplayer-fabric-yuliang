package dev.yuliang.zymbot.core;

import static org.junit.jupiter.api.Assertions.*;

import dev.yuliang.zymbot.core.api.BlockPos;
import dev.yuliang.zymbot.core.api.ItemView;
import dev.yuliang.zymbot.core.api.Vec3;
import dev.yuliang.zymbot.core.body.ChatIn;
import dev.yuliang.zymbot.core.craft.CraftTask;
import dev.yuliang.zymbot.core.craft.PlaceTask;
import dev.yuliang.zymbot.core.craft.Recipe;
import dev.yuliang.zymbot.core.task.Task;
import dev.yuliang.zymbot.core.task.Task.Status;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** PHASE3.md step 3: crafting by slot clicks in the 2×2 and at a table, and placing the table. */
class CraftTaskTest {
    final List<String> log = new ArrayList<>();

    CraftTask craft(FakeWorld w, Recipe r, int times, BlockPos table, ChatIn chat) {
        return new CraftTask(w, r, times, table, "test", chat, (what, why) -> log.add(what + " — because " + why));
    }

    static Status run(FakeWorld w, Task t, int max) {
        Status s = Status.RUNNING;
        for (int i = 0; i < max && s == Status.RUNNING; i++) {
            s = t.tick(w);
            if (w.paths.busy) w.paths.arrive();
        }
        return s;
    }

    static int count(FakeWorld w, String id) {
        return w.inventory.stream().filter(i -> i.id().equals(id)).mapToInt(ItemView::count).sum();
    }

    @Test
    void planks_fromLogsInTheHandGrid_takesOnlyWhatItsTold_andClosesTheScreen() {
        FakeWorld w = new FakeWorld("Bot1");
        w.give(20, "minecraft:oak_log", 5, null);
        CraftTask t = craft(w, Recipe.PLANKS_FROM_LOG, 3, null, null);
        assertEquals(Status.DONE, run(w, t, 300), t.failure());
        assertEquals(12, count(w, "minecraft:oak_planks"));
        assertEquals(2, count(w, "minecraft:oak_log"), "the other two logs went back");
        assertNull(w.menuKind, "screen closed");
        assertNull(w.cursor);
        assertTrue(log.contains("crafted 12 oak_planks — because test"), log.toString());
    }

    @Test
    void wholeStack_goesInWithOneClick() {
        FakeWorld w = new FakeWorld("Bot1");
        w.give(4, "minecraft:birch_log", 3, null);
        assertEquals(Status.DONE, run(w, craft(w, Recipe.PLANKS_FROM_LOG, 3, null, null), 300));
        assertEquals(12, count(w, "minecraft:birch_planks"));
        assertEquals(0, count(w, "minecraft:birch_log"));
    }

    @Test
    void sticksAndTable_inTheHandGrid() {
        FakeWorld w = new FakeWorld("Bot1");
        w.give(10, "minecraft:oak_planks", 7, null);
        assertEquals(Status.DONE, run(w, craft(w, Recipe.STICKS, 1, null, null), 300));
        assertEquals(4, count(w, "minecraft:stick"));
        assertEquals(Status.DONE, run(w, craft(w, Recipe.CRAFTING_TABLE, 1, null, null), 300));
        assertEquals(1, count(w, "minecraft:crafting_table"));
        assertEquals(1, count(w, "minecraft:oak_planks"));
    }

    @Test
    void mixedPlanks_stillMakeATable() {
        FakeWorld w = new FakeWorld("Bot1");
        w.give(10, "minecraft:oak_planks", 2, null);
        w.give(11, "minecraft:spruce_planks", 3, null);
        assertEquals(Status.DONE, run(w, craft(w, Recipe.CRAFTING_TABLE, 1, null, null), 300));
        assertEquals(1, count(w, "minecraft:crafting_table"));
        assertEquals(1, count(w, "minecraft:oak_planks") + count(w, "minecraft:spruce_planks"));
    }

    @Test
    void anAxe_needsTheTable_walksThere_opensIt_andCrafts() {
        FakeWorld w = new FakeWorld("Bot1");
        BlockPos table = new BlockPos(12, 64, 0);
        w.blocks.put(table, "minecraft:crafting_table");
        w.give(10, "minecraft:oak_planks", 3, null);
        w.give(11, "minecraft:stick", 4, null);
        CraftTask t = craft(w, Recipe.WOODEN_AXE, 1, table, null);
        assertEquals(Status.RUNNING, t.tick(w));
        assertEquals(table, w.paths.goal, "walks to the table first");
        assertEquals(Status.DONE, run(w, t, 300), t.failure());
        assertEquals(1, count(w, "minecraft:wooden_axe"));
        assertEquals(0, count(w, "minecraft:oak_planks"));
        assertEquals(2, count(w, "minecraft:stick"));
        assertNull(w.menuKind);
    }

    @Test
    void theHoe_layoutIsNotTheAxe() {
        FakeWorld w = new FakeWorld("Bot1");
        BlockPos table = new BlockPos(1, 64, 0);
        w.blocks.put(table, "minecraft:crafting_table");
        w.give(10, "minecraft:oak_planks", 2, null);
        w.give(11, "minecraft:stick", 2, null);
        assertEquals(Status.DONE, run(w, craft(w, Recipe.WOODEN_HOE, 1, table, null), 300));
        assertEquals(1, count(w, "minecraft:wooden_hoe"));
    }

    @Test
    void leftoversInTheGrid_areClearedFirst() {
        FakeWorld w = new FakeWorld("Bot1");
        w.menuKind = dev.yuliang.zymbot.core.api.MenuView.Kind.INVENTORY;   // a human left the screen open...
        w.grid[3] = "minecraft:dirt";                                       // ...with dirt in the grid
        w.gridCount[3] = 2;
        w.give(10, "minecraft:oak_planks", 2, null);
        assertEquals(Status.DONE, run(w, craft(w, Recipe.STICKS, 1, null, null), 300));
        assertEquals(4, count(w, "minecraft:stick"));
        assertEquals(2, count(w, "minecraft:dirt"));
    }

    @Test
    void notEnough_failsBeforeAnyClick() {
        FakeWorld w = new FakeWorld("Bot1");
        w.give(10, "minecraft:oak_planks", 2, null);
        CraftTask t = craft(w, Recipe.CRAFTING_TABLE, 1, null, null);
        assertEquals(Status.FAILED, run(w, t, 300));
        assertTrue(t.failure().startsWith("not enough planks for crafting table — have 2, need 4"), t.failure());
        assertEquals(0, w.clicks);
        assertNull(w.menuKind, "closed again");
    }

    @Test
    void theServerTakingItBack_isAFailure_withWhatItSaid() {
        FakeWorld w = new FakeWorld("Bot1");
        ChatIn chat = new ChatIn();
        w.serverSays = chat;
        w.refuseCrafts = true;
        w.give(10, "minecraft:oak_planks", 2, null);
        CraftTask t = craft(w, Recipe.STICKS, 1, null, chat);
        assertEquals(Status.FAILED, run(w, t, 300));
        assertTrue(t.failure().contains("the server said \"You're too hungry to craft\""), t.failure());
        assertNull(w.menuKind);
        assertEquals(2, count(w, "minecraft:oak_planks"), "closing gave the planks back");
    }

    @Test
    void tooHungry_doesntStart() {
        FakeWorld w = new FakeWorld("Bot1");
        w.hunger = 5;
        w.give(10, "minecraft:oak_planks", 2, null);
        CraftTask t = craft(w, Recipe.STICKS, 1, null, null);
        assertEquals(Status.FAILED, run(w, t, 300));
        assertTrue(t.failure().startsWith("too hungry to craft"), t.failure());
    }

    @Test
    void cancel_closesTheScreen() {
        FakeWorld w = new FakeWorld("Bot1");
        w.give(10, "minecraft:oak_planks", 4, null);
        CraftTask t = craft(w, Recipe.CRAFTING_TABLE, 1, null, null);
        for (int i = 0; i < 6; i++) t.tick(w);
        assertNotNull(w.menuKind);
        t.cancel();
        assertNull(w.menuKind);
        assertEquals(4, count(w, "minecraft:oak_planks"), "nothing lost");
    }

    @Test
    void recipesByName() {
        assertSame(Recipe.WOODEN_AXE, Recipe.named("axe"));
        assertSame(Recipe.CRAFTING_TABLE, Recipe.named("table"));
        assertSame(Recipe.STICKS, Recipe.named("stick"));
        assertSame(Recipe.PLANKS_FROM_LOG, Recipe.named("planks"));
        assertNull(Recipe.named("diamond sword"));
        assertTrue(Recipe.LOG.test("vinery:dark_cherry_log"));
        assertFalse(Recipe.LOG.test("minecraft:mushroom_stem"));
    }

    // ------------------------------------------------------------------ placing

    @Test
    void place_fromTheMainInventory_intoTheHotbar_thenNextToUs() {
        FakeWorld w = new FakeWorld("Bot1");
        w.pos = new Vec3(0.5, 64, 0.5);
        w.give(0, "minecraft:dirt", 3, null);
        w.give(20, "minecraft:crafting_table", 1, null);
        PlaceTask t = new PlaceTask(w, "minecraft:crafting_table", "minecraft:crafting_table", "test", (a, b) -> log.add(a));
        assertEquals(Status.DONE, run(w, t, 100), t.failure());
        BlockPos at = t.placed();
        assertEquals("minecraft:crafting_table", w.blockAt(at));
        assertEquals(64, at.y(), "on the ground, at our feet's level");
        assertNotEquals(new BlockPos(0, 64, 0), at, "never where we stand");
        assertEquals(1, w.selected, "moved into the first empty hotbar slot and held it");
        assertEquals(0, count(w, "minecraft:crafting_table"));
        assertTrue(log.stream().anyMatch(l -> l.startsWith("placed crafting_table at")), log.toString());
    }

    @Test
    void place_fails_withNothingToPlace_orNowhereToPutIt() {
        FakeWorld w = new FakeWorld("Bot1");
        PlaceTask t = new PlaceTask(w, "minecraft:crafting_table", "minecraft:crafting_table", "test", (a, b) -> {});
        assertEquals(Status.FAILED, run(w, t, 10));
        assertTrue(t.failure().startsWith("no crafting_table"), t.failure());

        FakeWorld air = new FakeWorld("Bot2");
        air.groundY = -100;                                     // standing on nothing solid (a pillar of one)
        air.give(0, "minecraft:crafting_table", 1, null);
        PlaceTask t2 = new PlaceTask(air, "minecraft:crafting_table", "minecraft:crafting_table", "test", (a, b) -> {});
        assertEquals(Status.FAILED, run(air, t2, 100));
        assertTrue(t2.failure().startsWith("no free spot"), t2.failure());
    }

    @Test
    void place_neverInsideUs() {
        Vec3 me = new Vec3(0.5, 64, 0.5);
        assertTrue(PlaceTask.touchesBody(new BlockPos(0, 64, 0), me));
        assertTrue(PlaceTask.touchesBody(new BlockPos(0, 65, 0), me));
        assertFalse(PlaceTask.touchesBody(new BlockPos(1, 64, 0), me));
        assertFalse(PlaceTask.touchesBody(new BlockPos(0, 66, 0), me), "above our head is free");
        assertTrue(PlaceTask.touchesBody(new BlockPos(1, 64, 0), new Vec3(0.9, 64, 0.5)), "standing near the edge: the next block is in us");
    }
}
