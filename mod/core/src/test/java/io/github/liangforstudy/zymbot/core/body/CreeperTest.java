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

    FakeWorld archer() {
        return armed().give(4, "minecraft:bow", 1, null).give(20, "minecraft:arrow", 1, null);
    }

    @Test
    void withABow_itsShotFromRange_eachDrawHeldForTwentyServerTicks_thenTheSwordUpClose() {
        FakeWorld w = archer();
        Bot b = walking(w);
        creeper(w, -12);
        ticks(b, w, 2);
        assertEquals(4, w.selected, "bow out at 12 blocks:\n" + log(b));
        ticks(b, w, 2);
        assertTrue(w.usingItem, "drawing");
        assertTrue(w.lookedAt.y() > 64.9, "aimed a little over its body for the drop: " + w.lookedAt);
        ticks(b, w, 20);
        assertEquals(1, w.arrowsShot, "loosed after 22 ticks at 20 TPS");
        w.tps = 9;
        ticks(b, w, 30);
        assertEquals(1, w.arrowsShot, "at 9 TPS a full draw is 47 of our ticks");
        ticks(b, w, 20);
        assertEquals(2, w.arrowsShot);

        creeper(w, -5);                                              // inside 5.5: bow away, sword out
        ticks(b, w, 2);
        assertEquals(2, w.selected, "the sword");
        assertFalse(w.usingItem);
        w.nearby.removeIf(e -> e.id() == 700);
        ticks(b, w, 2);
        assertTrue(log(b).contains("killed the creeper — because 2 arrows, 0 hits")
                && log(b).contains("good armor — shoot it, then hit it and step back"), log(b));
    }

    @Test
    void withABow_aCreeperBehindAWall_isNoTrigger_andNoArrowsMeansNoBow() {
        FakeWorld w = archer();
        for (int y = 64; y <= 67; y++) for (int z = -2; z <= 2; z++) w.blocks.put(new io.github.liangforstudy.zymbot.core.api.BlockPos(-6, y, z), "minecraft:stone");
        Bot b = walking(w);
        creeper(w, -12);
        ticks(b, w, 3);
        assertFalse(log(b).contains("creeper"), log(b));

        FakeWorld none = armed().give(4, "minecraft:bow", 1, null);
        Bot b2 = walking(none);
        creeper(none, -4.5);
        ticks(b2, none, 3);
        assertEquals(2, none.selected, "no arrows: the sword");
    }

    /** A 1-wide, 2-high tunnel along x, stone all round, dead end at x = 2 (we stand at 0.5, it comes from -x). */
    static void tunnel(FakeWorld w) {
        for (int x = -12; x <= 3; x++) for (int y = 63; y <= 66; y++) for (int z = -2; z <= 2; z++) {
            boolean hole = (y == 64 || y == 65) && z == 0 && x <= 1;
            w.blocks.put(new io.github.liangforstudy.zymbot.core.api.BlockPos(x, y, z), hole ? "minecraft:air" : "minecraft:stone");
        }
    }

    @Test
    void cornered_inADeadEnd_hitsItWhileItHisses_ratherThanRunningPastIt() {
        FakeWorld w = armed();
        tunnel(w);
        w.pos = new Vec3(0.5, 64, 0.5);
        Bot b = walking(w);
        EntityView c = creeper(w, -2.5);
        w.attackReady = false;
        ticks(b, w, 2);
        w.fuses.put(c.id(), new Fuse(0.2f, true, false));
        ticks(b, w, 2);
        assertFalse(log(b).contains("running clear"), "no run past it:\n" + log(b));
        assertTrue(log(b).contains("cornered by the creeper — because no open ground to run to but past it"), log(b));
        w.attackReady = true;
        ticks(b, w, 1);
        assertEquals(1, w.attacked.size(), "hit while it hisses");

        FakeWorld open = armed();                                    // the same, in the open: run
        assertFalse(io.github.liangforstudy.zymbot.core.task.CreeperFightTask.cornered(open, new Vec3(-2.5, 64, 0)));
        assertTrue(io.github.liangforstudy.zymbot.core.task.CreeperFightTask.cornered(w, new Vec3(-2.5, 64, 0.5)));

        FakeWorld wall = armed();                                    // a wall behind, open ground to the side
        for (int y = 64; y <= 65; y++) for (int z = -6; z <= 6; z++) for (int x = 1; x <= 4; x++)
            wall.blocks.put(new io.github.liangforstudy.zymbot.core.api.BlockPos(x, y, z), "minecraft:stone");
        wall.pos = new Vec3(0.5, 64, 0.5);
        assertFalse(io.github.liangforstudy.zymbot.core.task.CreeperFightTask.cornered(wall, new Vec3(-2.5, 64, 0.5)),
                "it can run sideways, as the run-clear would");
    }

    @Test
    void aLaggingServer_countsTheCooldownInItsTicks_soTheNextHitWaits() {
        FakeWorld w = armed();
        w.tps = 7;
        Bot b = walking(w);
        creeper(w, -3.0);
        while (w.attacked.isEmpty()) ticks(b, w, 1);
        w.attackReady = false;                                       // the swing empties it, as in the game
        ticks(b, w, 12);                                             // our clock: recharged in 12
        w.attackReady = true;
        int waited = 12;
        while (w.attacked.size() < 2 && waited < 100) {
            ticks(b, w, 1);
            waited++;
        }
        assertTrue(waited >= 36 && waited <= 40, "ready on our 13th tick: 13 x 20/7 = 38 of ours, not 13; waited " + waited);
    }

    @Test
    void atFullSpeed_theNextHitGoesAsSoonAsOurClockSaysReady() {
        FakeWorld w = armed();
        Bot b = walking(w);
        creeper(w, -3.0);
        while (w.attacked.isEmpty()) ticks(b, w, 1);
        w.attackReady = false;
        ticks(b, w, 12);
        w.attackReady = true;
        ticks(b, w, 1);
        assertEquals(2, w.attacked.size());
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
