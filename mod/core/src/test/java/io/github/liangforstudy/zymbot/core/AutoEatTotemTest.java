package io.github.liangforstudy.zymbot.core;

import static org.junit.jupiter.api.Assertions.*;

import io.github.liangforstudy.zymbot.core.body.AutoEatInterrupt;
import io.github.liangforstudy.zymbot.core.brain.Phase;
import io.github.liangforstudy.zymbot.core.config.ZymbotConfig;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Issue #8: Zymbot's own eating, auto totem, auto-start on 9b9t. */
class AutoEatTotemTest {
    @TempDir Path dir;
    final FakeClock clock = new FakeClock();

    Bot bot(FakeWorld w, ZymbotConfig cfg) {
        return new Bot(cfg, dir.resolve("zymbot.json"), dir.resolve(w.name), w.id, false, clock, Profile.ANARCHY);
    }

    void ticks(Bot b, FakeWorld w, int n) {
        for (int i = 0; i < n; i++) {
            clock.advance(50);
            b.tick(w, w);
        }
    }

    @Test
    void eatsATickedFood_whenHungry_underStart() {
        FakeWorld w = new FakeWorld("Bluetails").give(0, "minecraft:bread", 5, FakeWorld.food(5));
        w.hunger = 4;
        Bot b = bot(w, new ZymbotConfig());
        b.onJoin("9b9t.com", "Bluetails");
        ticks(b, w, 10);
        assertFalse(w.useHeld, "standing by: nothing without /zbot start");
        b.start("test");
        ticks(b, w, 10);
        assertTrue(w.useHeld, b.decisions().latest(10).toString());
    }

    @Test
    void onlyTickedFoods_andGoldenApplesKeptForLowHealth() {
        ZymbotConfig cfg = new ZymbotConfig();
        FakeWorld w = new FakeWorld("Bluetails")
                .give(0, "minecraft:golden_apple", 3, FakeWorld.healing(4))
                .give(1, "minecraft:rotten_flesh", 8, FakeWorld.food(4))
                .give(2, "minecraft:beef", 2, FakeWorld.food(3));
        w.hunger = 6;
        assertTrue(AutoEatInterrupt.pick(w, cfg).isEmpty(), "golden apple kept, rotten flesh and raw beef unticked");
        cfg.eatFoods.add("minecraft:beef");
        assertEquals("minecraft:beef", AutoEatInterrupt.pick(w, cfg).orElseThrow().id());
        w.give(3, "minecraft:cooked_beef", 1, FakeWorld.food(8));
        assertEquals("minecraft:cooked_beef", AutoEatInterrupt.pick(w, cfg).orElseThrow().id(), "the most hunger first");
    }

    @Test
    void refillsTheOffhandTotem_evenWhileAHumanHoldsTheKeys() {
        FakeWorld w = new FakeWorld("Bluetails").give(20, "minecraft:totem_of_undying", 1, null);
        w.offhand = "minecraft:shield";
        Bot b = bot(w, new ZymbotConfig());
        b.onJoin("9b9t.com", "Bluetails");
        ticks(b, w, 5);
        assertEquals("minecraft:shield", w.offhand, "not without /zbot start");
        b.start("test");
        b.humanInput(w);
        ticks(b, w, 3);
        assertEquals("minecraft:totem_of_undying", w.offhand);
        assertEquals(1, w.offhandSwaps);
        assertTrue(w.inventory().stream().anyMatch(i -> i.slot() == 20 && i.id().equals("minecraft:shield")));
        ticks(b, w, 20);
        assertEquals(1, w.offhandSwaps, "a totem there already: nothing more");
    }

    @Test
    void autoTotemOff_leavesTheOffhandAlone() {
        ZymbotConfig cfg = new ZymbotConfig();
        cfg.autoTotem = false;
        FakeWorld w = new FakeWorld("Bluetails").give(20, "minecraft:totem_of_undying", 1, null);
        Bot b = bot(w, cfg);
        b.onJoin("9b9t.com", "Bluetails");
        b.start("test");
        ticks(b, w, 5);
        assertEquals("", w.offhand);
    }

    @Test
    void autoStartOn9b9t_byAnyAddress_onlyWhenOn() {
        ZymbotConfig cfg = new ZymbotConfig();
        assertFalse(cfg.autoStart9b9t, "off by default");
        Bot b = bot(new FakeWorld("Bluetails"), cfg);
        b.onJoin("eu.9b9t.com", "Bluetails");
        assertEquals(Phase.STOPPED, b.phase());
        cfg.autoStart9b9t = true;
        b.onJoin("eu.9b9t.com", "Bluetails");
        assertTrue(b.isRunning());
        b.stop("test");
        b.onJoin("play.example.net", "Bluetails");
        assertFalse(b.isRunning(), "only 9b9t");
    }
}
