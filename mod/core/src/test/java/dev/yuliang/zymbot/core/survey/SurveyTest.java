package dev.yuliang.zymbot.core.survey;

import static org.junit.jupiter.api.Assertions.*;

import dev.yuliang.zymbot.core.Bot;
import dev.yuliang.zymbot.core.FakeClock;
import dev.yuliang.zymbot.core.FakeWorld;
import dev.yuliang.zymbot.core.api.BlockPos;
import dev.yuliang.zymbot.core.api.Vec3;
import dev.yuliang.zymbot.core.config.ZymbotConfig;
import dev.yuliang.zymbot.core.survey.SurveyCatalog.Kind;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** PHASE3.md §2: the survey's lists, grouping, nearest, and words. */
class SurveyTest {
    @TempDir Path dir;

    static Survey survey(FakeWorld w) {
        return Surveyor.group(Surveyor.copy(w, "test", 0), 0);
    }

    @Test
    void theCatalogKnowsWhatItIsLookingFor() {
        assertEquals(Kind.LOG, SurveyCatalog.classify("minecraft:oak_log"));
        assertEquals(Kind.LOG, SurveyCatalog.classify("minecraft:crimson_stem"));
        assertEquals(Kind.LOG, SurveyCatalog.classify("minecraft:stripped_birch_log"));
        assertNull(SurveyCatalog.classify("minecraft:mushroom_stem"));
        assertNull(SurveyCatalog.classify("minecraft:oak_leaves"));
        assertEquals(Kind.WILD_FOOD, SurveyCatalog.classify("farm_and_charm:wild_carrots"));
        assertEquals(Kind.WILD_FOOD, SurveyCatalog.classify("farmersdelight:wild_cabbages"));
        assertEquals(Kind.WILD_FOOD, SurveyCatalog.classify("farmersdelight:red_mushroom_colony"));
        assertEquals(Kind.WILD_FOOD, SurveyCatalog.classify("minecraft:brown_mushroom"));
        assertEquals(Kind.WILD_FOOD, SurveyCatalog.classify("minecraft:sweet_berry_bush"));
        assertEquals(Kind.CRAFTING_TABLE, SurveyCatalog.classify("minecraft:crafting_table"));
        assertEquals(Kind.FURNACE, SurveyCatalog.classify("minecraft:furnace"));
        assertEquals(Kind.STORAGE, SurveyCatalog.classify("minecraft:barrel"));
        assertEquals(Kind.BED, SurveyCatalog.classify("minecraft:red_bed"));
        assertEquals(Kind.CAMPFIRE, SurveyCatalog.classify("minecraft:campfire"));
        assertNull(SurveyCatalog.classify("minecraft:stone"));
        assertNull(SurveyCatalog.classify("minecraft:potted_oak_log"));
    }

    @Test
    void logsGroupIntoTrees_nearestFirst_hugeSpotted() {
        FakeWorld w = new FakeWorld("Bot1");
        w.pos = new Vec3(0.5, 64, 0.5);
        w.tree(8, 64, -8, 5, "minecraft:oak_log", false);        // NE, ~11 blocks
        w.tree(-30, 64, 0, 12, "minecraft:dark_oak_log", true);   // W, huge
        w.tree(9, 64, -8, 1, "minecraft:oak_log", false);         // touching the first: same tree
        Survey s = survey(w);
        assertEquals(2, s.all(Kind.LOG).size());
        Survey.Group near = s.nearestTree().orElseThrow();
        assertEquals(new BlockPos(8, 64, -8), near.pos(), "the trunk's base");
        assertEquals(6, near.blocks());
        assertFalse(near.huge());
        assertTrue(s.all(Kind.LOG).get(1).huge());
        assertEquals(48, s.all(Kind.LOG).get(1).blocks());
        assertEquals("NE", Survey.direction(s.at(), near.pos()));
    }

    @Test
    void wildFoodPlantsAFewBlocksApartAreOnePatch_andBedHalvesOneBed() {
        FakeWorld w = new FakeWorld("Bot1");
        w.blocks.put(new BlockPos(0, 64, 20), "farm_and_charm:wild_carrots");
        w.blocks.put(new BlockPos(2, 64, 22), "farm_and_charm:wild_carrots");
        w.blocks.put(new BlockPos(3, 64, 24), "farm_and_charm:wild_carrots");
        w.blocks.put(new BlockPos(-20, 64, 0), "minecraft:brown_mushroom");
        w.blocks.put(new BlockPos(5, 64, 5), "minecraft:white_bed");
        w.blocks.put(new BlockPos(5, 64, 6), "minecraft:white_bed");
        w.blocks.put(new BlockPos(0, 64, 20 + 100), "minecraft:crafting_table");   // out of range
        Survey s = survey(w);
        assertEquals(2, s.all(Kind.WILD_FOOD).size());
        assertEquals(3, s.all(Kind.WILD_FOOD).get(1).blocks());
        assertEquals("minecraft:brown_mushroom", s.nearestWildFood().orElseThrow().id());
        assertEquals(1, s.all(Kind.BED).size());
        assertTrue(s.nearestCraftingTable().isEmpty());
    }

    @Test
    void summaryAndLines_sayWhatWasFound() {
        FakeWorld w = new FakeWorld("Bot1");
        w.pos = new Vec3(0.5, 64, 0.5);
        w.spawn = new BlockPos(-100, 70, 0);
        w.day = 3;
        w.time = 14000;
        w.give(0, "minecraft:bread", 3, FakeWorld.food(5));
        w.give(1, "minecraft:dirt", 10, null);
        w.give(2, "minecraft:dirt", 5, null);
        w.tree(8, 64, -8, 4, "minecraft:oak_log", false);
        w.tree(20, 64, 20, 4, "minecraft:birch_log", false);
        w.blocks.put(new BlockPos(0, 64, 20), "minecraft:crafting_table");
        w.blocks.put(new BlockPos(0, 64, 30), "farmersdelight:wild_onions");
        Survey s = survey(w);
        String sum = s.summary();
        assertTrue(sum.startsWith("2 trees (nearest 11 blocks NE, oak_log)"), sum);
        assertTrue(sum.contains("1 wild food patch (nearest 30 blocks S, wild_onions)"), sum);
        assertTrue(sum.contains("a crafting table 20 blocks S"), sum);
        assertFalse(sum.contains("furnace"), sum);
        List<String> lines = s.lines(5000);
        String all = String.join("\n", lines);
        assertTrue(all.contains("15 dirt"), all);
        assertTrue(all.contains("3 bread 5/3.0"), all);
        assertTrue(all.contains("plains, day 3 night, spawn 100 blocks W"), all);
        assertTrue(lines.size() <= 8, all);
    }

    @Test
    void nothingAround_saysSo() {
        Survey s = survey(new FakeWorld("Bot1"));
        assertEquals("no trees, no wild food", s.summary());
        assertTrue(s.nearestTree().isEmpty());
    }

    @Test
    void theBotSurveysOnStart_logsIt_andAnswersTheCommand() {
        FakeWorld w = new FakeWorld("Bot1");
        w.tree(4, 64, 0, 3, "minecraft:oak_log", false);
        Bot b = new Bot(new ZymbotConfig(), dir.resolve("zymbot.json"), dir.resolve("Bot1"), w.id, true, new FakeClock());
        b.onJoin("x", "Bot1");
        b.start("test");
        b.tick(w, w);
        assertTrue(b.survey().isPresent());
        assertTrue(b.status().stream().anyMatch(l -> l.contains("surveyed — because started : 1 tree (nearest 5 blocks E")),
                b.status().toString());
        List<String> out = b.surveyNow();
        assertTrue(out.get(0).startsWith("survey ("), out.toString());
        assertTrue(out.get(0).contains("asked by /zbot survey"), out.toString());
    }
}
