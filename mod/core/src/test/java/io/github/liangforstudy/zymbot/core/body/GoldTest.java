package io.github.liangforstudy.zymbot.core.body;

import static org.junit.jupiter.api.Assertions.*;

import io.github.liangforstudy.zymbot.core.Bot;
import io.github.liangforstudy.zymbot.core.FakeClock;
import io.github.liangforstudy.zymbot.core.FakeWorld;
import io.github.liangforstudy.zymbot.core.Profile;
import io.github.liangforstudy.zymbot.core.config.ZymbotConfig;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** doc/9b9t/roadmap.md, "Before routes" 6: one gold piece on in the Nether, so piglins leave us be. */
class GoldTest {
    @TempDir Path dir;
    final FakeClock clock = new FakeClock();

    static final String IRON_HELMET = "minecraft:iron_helmet", GOLD_HELMET = "minecraft:golden_helmet",
            GOLD_BOOTS = "minecraft:golden_boots";

    /** Full iron, in the Nether, standing by. */
    FakeWorld ironInTheNether() {
        FakeWorld w = new FakeWorld("Bluetails");
        w.nether = true;
        w.worn[0] = IRON_HELMET;
        w.worn[1] = "minecraft:iron_chestplate";
        w.worn[2] = "minecraft:iron_leggings";
        w.worn[3] = "minecraft:iron_boots";
        w.armor = 15;
        return w;
    }

    Bot anarchy(FakeWorld w) {
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
        return String.join("\n", b.decisions().latest(20).stream().map(Object::toString).toList());
    }

    @Test
    void aGoldHelmetInTheMainInventory_goesOnForTheIronOne_andTheSlotComesBack() {
        FakeWorld w = ironInTheNether();
        w.give(20, GOLD_HELMET, 1, null);
        w.give(8, "minecraft:cobblestone", 16, null);
        w.selected = 2;
        Bot b = anarchy(w);
        ticks(b, w, 10);
        assertEquals(GOLD_HELMET, w.worn[0], log(b));
        assertTrue(w.inventory.stream().anyMatch(i -> i.id().equals(IRON_HELMET)), "the iron helmet is kept: " + w.inventory);
        assertTrue(w.inventory.stream().anyMatch(i -> i.id().equals("minecraft:cobblestone")), "slot 8's blocks moved, not lost");
        assertEquals(2, w.selected, "back on the slot it had");
        assertFalse(w.useHeld, "use let go");
        assertTrue(log(b).contains("golden helmet, no armor lost"), log(b));
    }

    @Test
    void theCheapestPieceToLoseGoesOn_bootsOverAChestplate() {
        FakeWorld w = ironInTheNether();
        w.give(3, "minecraft:golden_chestplate", 1, null);   // 6 → 5: one less
        w.give(4, GOLD_BOOTS, 1, null);                      // 2 → 1: one less too, but the boots come later in the tie
        assertEquals("minecraft:golden_chestplate", GoldInterrupt.best(w).orElseThrow().id(), "a tie goes head first");
        w.worn[1] = "minecraft:diamond_chestplate";          // 8 → 5 now
        assertEquals(GOLD_BOOTS, GoldInterrupt.best(w).orElseThrow().id());
    }

    @Test
    void noTrigger_outsideTheNether_withGoldOn_orWithNoneCarried() {
        FakeWorld w = ironInTheNether();
        Bot b = anarchy(w);
        GoldInterrupt g = new GoldInterrupt(new Body(List::of, id -> false, clock, () -> {}), clock);
        assertFalse(g.triggered(w), "none carried");
        w.give(5, GOLD_HELMET, 1, null);
        assertTrue(g.triggered(w));
        w.nether = false;
        assertFalse(g.triggered(w), "piglins live in the Nether");
        w.nether = true;
        w.worn[3] = GOLD_BOOTS;
        assertFalse(g.triggered(w), "one gold piece is enough");
    }

    @Test
    void aSwapThatDoesntTake_givesUp_andWaits30sBeforeTryingAgain() {
        FakeWorld w = ironInTheNether();
        w.give(5, GOLD_HELMET, 1, null);
        w.armorSwapsTake = false;
        Bot b = anarchy(w);
        ticks(b, w, 20);
        assertEquals(IRON_HELMET, w.worn[0]);
        assertTrue(log(b).contains("wouldn't go on"), log(b));
        long tries = b.decisions().latest(50).stream().filter(d -> d.toString().startsWith("putting golden helmet on")).count();
        ticks(b, w, 20 * 20);
        assertEquals(tries, b.decisions().latest(50).stream().filter(d -> d.toString().startsWith("putting golden helmet on")).count(),
                "not again within 30 s: " + log(b));
        w.armorSwapsTake = true;
        ticks(b, w, 12 * 20);
        assertEquals(GOLD_HELMET, w.worn[0], "on, the next time: " + log(b));
    }
}
