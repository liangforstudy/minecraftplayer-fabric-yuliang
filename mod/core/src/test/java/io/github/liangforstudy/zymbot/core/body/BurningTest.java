package io.github.liangforstudy.zymbot.core.body;

import static org.junit.jupiter.api.Assertions.*;

import io.github.liangforstudy.zymbot.core.Bot;
import io.github.liangforstudy.zymbot.core.FakeClock;
import io.github.liangforstudy.zymbot.core.FakeWorld;
import io.github.liangforstudy.zymbot.core.Profile;
import io.github.liangforstudy.zymbot.core.api.BlockPos;
import io.github.liangforstudy.zymbot.core.api.ItemView;
import io.github.liangforstudy.zymbot.core.config.ZymbotConfig;
import io.github.liangforstudy.zymbot.core.task.PourWaterTask;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** doc/9b9t/roadmap.md, "Before routes" 4: put out the burn. */
class BurningTest {
    @TempDir Path dir;
    final FakeClock clock = new FakeClock();

    static FakeWorld onStone() {
        FakeWorld w = new FakeWorld("Bluetails");
        w.blocks.put(new BlockPos(0, 63, 0), "minecraft:stone");
        return w;
    }

    Bot started(FakeWorld w) {
        Bot b = new Bot(new ZymbotConfig(), dir.resolve("zymbot.json"), dir.resolve(w.name), w.id, false, clock, Profile.ANARCHY);
        b.onJoin("9b9t.com", w.name);
        b.start("test");
        return b;
    }

    void ticks(Bot b, FakeWorld w, int n) {
        for (int i = 0; i < n; i++) {
            clock.now += 50;
            b.tick(w, w);
        }
    }

    String log(Bot b) {
        return String.join("\n", b.decisions().latest(30).stream().map(Object::toString).toList());
    }

    @Test
    void onFire_withAWaterBucketInTheMainInventory_poursItOut_scoopsItBack_takesBackTheSlot() {
        FakeWorld w = onStone().give(20, PourWaterTask.WATER_BUCKET, 1, null);
        w.selected = 1;
        Bot b = started(w);
        ticks(b, w, 3);
        w.onFire = true;
        ticks(b, w, 15);
        assertFalse(w.onFire, "put out:\n" + log(b));
        assertTrue(w.inventory.stream().anyMatch(i -> i.id().equals(PourWaterTask.WATER_BUCKET)), "the water's back in the bucket: " + w.inventory);
        assertFalse(w.inWater, "and not left at our feet");
        assertEquals(1, w.selected, "the slot we had");
        assertTrue(log(b).contains("putting the fire out with water — because on fire"), log(b));
    }

    @Test
    void standingInFire_noSafeSpotToStepTo_poursTheBucket_butNotInLava() {
        FakeWorld w = onStone().give(4, PourWaterTask.WATER_BUCKET, 1, null);
        w.blocks.put(new BlockPos(0, 64, 0), "minecraft:fire");
        w.blocks.put(new BlockPos(1, 64, 0), "minecraft:fire");                  // more fire beside us, as live
        w.damage = new io.github.liangforstudy.zymbot.core.api.Damage("minecraft:in_fire", null, null, null);
        w.onFire = true;
        Bot b = started(w);
        ticks(b, w, 8);
        assertFalse(w.onFire, log(b));
        assertEquals("minecraft:air", w.blockAt(new BlockPos(0, 64, 0)), "the fire block's gone (water poured, then scooped)");
        assertTrue(w.inventory.stream().anyMatch(i -> i.id().equals(PourWaterTask.WATER_BUCKET)), "scooped back, not cut off: " + log(b));
        assertTrue(log(b).contains("putting the fire out with water") && !log(b).contains("stepping off the hazard to"), log(b));

        FakeWorld lava = onStone().give(4, PourWaterTask.WATER_BUCKET, 1, null);
        lava.blocks.put(new BlockPos(0, 64, 0), "minecraft:lava");
        lava.onFire = true;
        assertFalse(new BurningInterrupt(new ZymbotConfig()).triggered(lava), "in lava: step out first");
    }

    @Test
    void inTheNether_noBucket_andAPotionOnlyWhenLow() {
        FakeWorld w = onStone().give(4, PourWaterTask.WATER_BUCKET, 1, null);
        w.nether = true;
        w.onFire = true;
        BurningInterrupt in = new BurningInterrupt(new ZymbotConfig());
        assertFalse(in.triggered(w), "water boils away in the Nether");
        w.fireResPotion = Optional.of(new ItemView(5, "minecraft:potion", 1, null));
        assertFalse(in.triggered(w), "health 20: not worth a 3-minute potion");
        w.health = 14;
        assertTrue(in.triggered(w));
        assertTrue(in.why(w).contains("drink fire resistance"), in.why(w));
        ZymbotConfig never = new ZymbotConfig();
        never.setTunable("fireres", 0);
        assertFalse(new BurningInterrupt(never).triggered(w), "fireres 0: never drinks one");
        w.fireResistant = true;
        assertFalse(in.triggered(w), "already fire resistant");
    }

    @Test
    void notBurning_orNothingToPutItOutWith_isNoTrigger() {
        BurningInterrupt in = new BurningInterrupt(new ZymbotConfig());
        FakeWorld w = onStone();
        w.onFire = true;
        assertFalse(in.triggered(w), "no bucket, no potion");
        w.give(3, PourWaterTask.WATER_BUCKET, 1, null);
        assertTrue(in.triggered(w));
        w.onFire = false;
        assertFalse(in.triggered(w));
    }

    @Test
    void steppedOffToACorner_fireDiagonallyAcross_stillCountsAsTouching() {
        FakeWorld w = onStone();
        w.blocks.put(new BlockPos(1, 64, 1), "minecraft:fire");
        w.pos = new io.github.liangforstudy.zymbot.core.api.Vec3(0.8, 64, 0.8);       // the body overlaps 1 64 1
        w.damage = new io.github.liangforstudy.zymbot.core.api.Damage("minecraft:in_fire", null, null, null);
        assertTrue(new HazardInterrupt(new Body(java.util.ArrayList::new, id -> false, () -> 0L, () -> {})).triggered(w));
        assertTrue(HazardInterrupt.touchesHazard(w, new BlockPos(0, 63, 0)), "and the block isn't a safe spot either");
    }

    @Test
    void burnedUnderABurningCanopy_movesOn_toASpotWithNoFireNearOrOverhead() {
        FakeWorld w = onStone().give(4, PourWaterTask.WATER_BUCKET, 1, null);
        w.blocks.put(new BlockPos(0, 70, 0), "minecraft:fire");                    // in the leaves, 6 up
        w.damage = new io.github.liangforstudy.zymbot.core.api.Damage("minecraft:on_fire", null, null, null);
        w.onFire = true;
        HazardInterrupt h = new HazardInterrupt(new Body(java.util.ArrayList::new, id -> false, () -> 0L, () -> {}));
        assertTrue(h.triggered(w), "burned with fire overhead: move on, don't stand pouring");
        w.dryLand = new BlockPos(1, 63, 0);                                        // still under it
        w.blocks.put(new BlockPos(1, 63, 0), "minecraft:stone");
        assertTrue(h.respond(w, w).failedUpfront(), "a spot under the same fire isn't safe");
        w.dryLand = new BlockPos(7, 63, 0);
        w.blocks.put(new BlockPos(7, 63, 0), "minecraft:stone");
        assertFalse(h.respond(w, w).failedUpfront(), "7 blocks off, clear overhead, is");
        Bot b = started(w);
        ticks(b, w, 3);
        assertEquals(7, w.paths.goal.x(), "walking out from under it:\n" + log(b));
    }
}
