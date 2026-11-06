package io.github.liangforstudy.zymbot.core.body;

import static org.junit.jupiter.api.Assertions.*;

import io.github.liangforstudy.zymbot.core.Bot;
import io.github.liangforstudy.zymbot.core.FakeClock;
import io.github.liangforstudy.zymbot.core.FakeWorld;
import io.github.liangforstudy.zymbot.core.Profile;
import io.github.liangforstudy.zymbot.core.api.EntityView;
import io.github.liangforstudy.zymbot.core.api.Fuse;
import io.github.liangforstudy.zymbot.core.api.Vec3;
import io.github.liangforstudy.zymbot.core.body.Combat.Gear;
import io.github.liangforstudy.zymbot.core.config.ZymbotConfig;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** doc/9b9t/roadmap.md, "Creepers" (owner, 2026-11-03): a lone creeper is hit, stepped back from, and hit again. */
class CreeperTest {
    @TempDir Path dir;
    final FakeClock clock = new FakeClock();

    @Test
    void aLoneCreeperIsFought_inArmor_butNotInACrowd_underFire_orLow() {
        assertTrue(Combat.decide("minecraft:creeper", Gear.GOOD, 1, 0, 20, 12).fight());
        assertTrue(Combat.decide("minecraft:creeper", Gear.BASIC, 1, 0, 20, 12).fight());
        assertFalse(Combat.decide("minecraft:creeper", Gear.BARE, 1, 0, 20, 12).fight(), "bare: a blast at 3 takes most of our health");
        assertFalse(Combat.decide("minecraft:creeper", Gear.GOOD, 2, 0, 20, 12).fight(), "another mob pushes us into it");
        assertFalse(Combat.decide("minecraft:creeper", Gear.GOOD, 1, 1, 20, 12).fight());
        assertFalse(Combat.decide("minecraft:creeper", Gear.BASIC, 1, 0, 15, 12).fight());
        assertEquals("creeper, good armor — hit it and step back", Combat.decide("minecraft:creeper", Gear.GOOD, 1, 0, 20, 12).why());
    }

    FakeWorld armed() {
        FakeWorld w = new FakeWorld("Bluetails").give(2, "minecraft:diamond_sword", 1, null);
        w.armor = 20;
        return w;
    }

    Bot walking(FakeWorld w) {
        Bot b = new Bot(new ZymbotConfig(), dir.resolve("zymbot.json"), dir.resolve(w.name), w.id, false, clock, Profile.ANARCHY);
        b.onJoin("9b9t.com", w.name);
        b.goTo(200, null, 0);
        ticks(b, w, 2);
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

    static EntityView creeper(FakeWorld w, double x) {
        EntityView e = new EntityView(700, UUID.nameUUIDFromBytes("creeper".getBytes()), "creeper", "minecraft:creeper",
                EntityView.Kind.HOSTILE, new Vec3(x, 64, 0));
        w.nearby.removeIf(o -> o.id() == 700);
        w.nearby.add(e);
        return e;
    }

    @Test
    void waitsCharged_hitAtReach_steppedBackWhileItRecharges_untilItsDead() {
        FakeWorld w = armed();
        Bot b = walking(w);
        creeper(w, -4.5);
        ticks(b, w, 2);
        assertEquals(2, w.selected, "the sword in hand");
        assertTrue(!w.forwardHeld && w.attacked.isEmpty(), "charged and out of reach: let it walk in\n" + log(b));
        ticks(b, w, 40);
        assertTrue(w.forwardHeld, "it isn't coming: step in");
        creeper(w, -3.1);
        ticks(b, w, 1);
        assertEquals(1, w.attacked.size(), "hit at the edge of its range:\n" + log(b));
        w.attackReady = false;
        creeper(w, -3.0);
        ticks(b, w, 1);
        assertTrue(w.backHeld && !w.forwardHeld, "recharging: a step back");
        w.attackReady = true;
        ticks(b, w, 1);
        assertEquals(2, w.attacked.size());
        w.nearby.removeIf(e -> e.id() == 700);                       // dead
        ticks(b, w, 2);
        assertFalse(w.backHeld || w.forwardHeld, "keys let go");
        assertTrue(log(b).contains("hit it and step back") && log(b).contains("killed the creeper — because 2 hits"), log(b));
    }

    @Test
    void theHitCount_lastsAcrossRunningClear() {
        FakeWorld w = armed();
        Bot b = walking(w);
        EntityView c = creeper(w, -3.0);
        ticks(b, w, 1);
        w.attackReady = false;
        ticks(b, w, 1);
        assertEquals(1, w.attacked.size());
        w.fuses.put(c.id(), new Fuse(0.3f, true, false));
        ticks(b, w, 2);
        w.pos = new Vec3(9, 64, 0);                                  // ran clear: out of the reflex's range
        w.fuses.put(c.id(), Fuse.CALM);
        ticks(b, w, 3);
        creeper(w, 6.0);                                             // it follows; in again
        w.attackReady = true;
        ticks(b, w, 1);
        w.attackReady = false;
        ticks(b, w, 1);
        assertEquals(2, w.attacked.size(), log(b));
        w.nearby.removeIf(e -> e.id() == 700);
        ticks(b, w, 2);
        assertTrue(log(b).contains("killed the creeper — because 2 hits, ran clear 1x"), log(b));
    }

    @Test
    void itHissesAnyway_runsClearOfSeven_thenGoesBackIn() {
        FakeWorld w = armed();
        Bot b = walking(w);
        EntityView c = creeper(w, -3.0);
        ticks(b, w, 2);
        w.fuses.put(c.id(), new Fuse(0.3f, true, false));
        ticks(b, w, 2);
        assertTrue(w.paths.goal != null && w.paths.goal.x() > 5, "runs away east: " + w.paths.goal + "\n" + log(b));
        assertTrue(log(b).contains("running clear of the creeper — because it's hissing"), log(b));
        int hits = w.attacked.size();
        w.pos = new Vec3(5, 64, 0);                                  // 8 off, and it fizzles
        w.fuses.put(c.id(), Fuse.CALM);
        ticks(b, w, 2);
        assertFalse(w.backHeld || w.forwardHeld, "clear and fizzled: waits for it, weapon charged\n" + log(b));
        assertEquals(hits, w.attacked.size());
    }

    @Test
    void seenAlreadyLit_atReachWithTheWeaponCharged_hitsFirst_thenRuns() {
        FakeWorld w = armed();
        Bot b = walking(w);
        EntityView c = creeper(w, -3.1);
        w.fuses.put(c.id(), new Fuse(0.1f, true, false));          // live: hissing by the time we saw it in reach
        ticks(b, w, 2);
        assertEquals(1, w.attacked.size(), "one hit, then away\n" + log(b));
        assertTrue(log(b).contains("running clear of the creeper"), log(b));

        ticks(b, w, 12);
        w.nearby.removeIf(e -> e.id() == 700);                       // gone while hissing, long after the hit: it went off
        ticks(b, w, 4);
        assertTrue(log(b).contains("the creeper went off — because gone while hissing, after 1 hit, ran clear 1x")
                && !log(b).contains("killed the creeper"), log(b));

        FakeWorld kill = armed();
        Bot b3 = walking(kill);
        EntityView c3 = creeper(kill, -3.1);
        kill.fuses.put(c3.id(), new Fuse(0.1f, true, false));
        ticks(b3, kill, 2);
        kill.nearby.removeIf(e -> e.id() == 700);                    // gone right after the hit: the hit killed it
        ticks(b3, kill, 2);
        assertTrue(log(b3).contains("killed the creeper — because 1 hit") && !log(b3).contains("went off"), log(b3));

        FakeWorld late = armed();
        Bot b2 = walking(late);
        EntityView c2 = creeper(late, -3.1);
        late.fuses.put(c2.id(), new Fuse(0.7f, true, false));
        ticks(b2, late, 2);
        assertTrue(late.attacked.isEmpty(), "too late to hit: just run");
    }

    @Test
    void stillLitButTenBlocksOff_stopsRunning_andAKillStopsTheRunToo() {
        FakeWorld w = armed();
        Bot b = walking(w);
        EntityView c = creeper(w, -3.0);
        ticks(b, w, 2);
        w.fuses.put(c.id(), new Fuse(0.9f, true, false));
        ticks(b, w, 2);
        assertNotNull(w.paths.goal, "running");
        w.pos = new Vec3(8, 64, 0);                                  // 11 off, the stalled fuse still "lit"
        ticks(b, w, 2);
        assertFalse(w.forwardHeld || w.backHeld, "stands and watches");
        long notes = log(b).lines().filter(l -> l.startsWith("running clear")).count();
        ticks(b, w, 5);
        assertEquals(notes, log(b).lines().filter(l -> l.startsWith("running clear")).count(), "no new run each tick");

        w.pos = new Vec3(2, 64, 0);                                  // 5 off: running again
        ticks(b, w, 2);
        int stops = w.paths.stops;
        w.nearby.removeIf(e -> e.id() == 700);
        ticks(b, w, 1);
        assertTrue(w.paths.stops > stops, "the run-clear's path is cancelled with the fight\n" + log(b));
    }

    @Test
    void aChargedOne_orOneWithCompany_isKeptAwayFrom() {
        FakeWorld w = armed();
        Bot b = walking(w);
        EntityView c = creeper(w, -3.5);
        w.fuses.put(c.id(), new Fuse(0, false, true));
        ticks(b, w, 2);
        assertTrue(w.attacked.isEmpty() && w.paths.goal.x() > 10, "away: " + w.paths.goal);
        assertTrue(log(b).contains("charged creeper — keep away"), log(b));

        FakeWorld w2 = armed();
        Bot b2 = walking(w2);
        creeper(w2, -3.5);
        CombatTest.mob(w2, "minecraft:zombie", -4, 3);
        ticks(b2, w2, 2);
        assertTrue(w2.attacked.isEmpty(), log(b2));
        assertTrue(log(b2).contains("creeper and 1 more — keep away"), log(b2));
    }

    @Test
    void noStepBack_overAnEdge() {
        FakeWorld w = armed();
        w.blocks.put(new io.github.liangforstudy.zymbot.core.api.BlockPos(1, 63, 0), "minecraft:air");   // a drop behind us
        Bot b = walking(w);
        creeper(w, -3.0);
        w.attackReady = false;
        ticks(b, w, 2);
        assertFalse(w.backHeld, "no ground behind: stand");
    }
}
