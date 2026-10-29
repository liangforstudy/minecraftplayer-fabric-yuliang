package io.github.liangforstudy.zymbot.core.body;

import static org.junit.jupiter.api.Assertions.*;

import io.github.liangforstudy.zymbot.core.Bot;
import io.github.liangforstudy.zymbot.core.FakeClock;
import io.github.liangforstudy.zymbot.core.FakeWorld;
import io.github.liangforstudy.zymbot.core.Profile;
import io.github.liangforstudy.zymbot.core.api.Damage;
import io.github.liangforstudy.zymbot.core.api.EntityView;
import io.github.liangforstudy.zymbot.core.api.ItemView;
import io.github.liangforstudy.zymbot.core.api.Vec3;
import io.github.liangforstudy.zymbot.core.body.Combat.Gear;
import io.github.liangforstudy.zymbot.core.config.ZymbotConfig;
import io.github.liangforstudy.zymbot.core.task.FightTask;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** doc/9b9t/roadmap.md, "Attacked mid-route": fight weak mobs, back off from the rest, scaled to the armor. */
class CombatTest {
    @TempDir Path dir;
    final FakeClock clock = new FakeClock();

    // ------------------------------------------------------------------ the rules

    @Test
    void aLoneZombieIsFought_twoOnlyWithArmor() {
        assertTrue(Combat.decide("minecraft:zombie", Gear.BARE, 1, 0, 20, 12).fight());
        assertFalse(Combat.decide("minecraft:zombie", Gear.BARE, 2, 0, 20, 12).fight());
        assertTrue(Combat.decide("minecraft:zombie", Gear.BASIC, 2, 0, 20, 12).fight());
        assertFalse(Combat.decide("minecraft:zombie", Gear.BASIC, 3, 0, 20, 12).fight());
        assertTrue(Combat.decide("minecraft:spider", Gear.GOOD, 4, 0, 20, 12).fight());
    }

    @Test
    void lowOnHealth_onlyGoodArmorKeepsFighting() {
        assertFalse(Combat.decide("minecraft:zombie", Gear.BASIC, 1, 0, 15, 12).fight(), "12 + 4 margin");
        assertTrue(Combat.decide("minecraft:zombie", Gear.GOOD, 1, 0, 15, 12).fight());
    }

    @Test
    void groundShootersAreRushed_whenTheyDontOutnumberTheArmor_elseCover() {
        assertEquals(Combat.Action.FIGHT, Combat.decide("minecraft:skeleton", Gear.BARE, 1, 1, 20, 12).action(),
                "running only gives it more shots");
        assertEquals(Combat.Action.COVER, Combat.decide("minecraft:skeleton", Gear.BARE, 1, 2, 20, 12).action());
        assertEquals(Combat.Action.COVER, Combat.decide("minecraft:skeleton", Gear.BASIC, 1, 2, 20, 12).action());
        assertEquals(Combat.Action.FIGHT, Combat.decide("minecraft:skeleton", Gear.GOOD, 1, 2, 20, 12).action());
        assertEquals(Combat.Action.COVER, Combat.decide("minecraft:skeleton", Gear.GOOD, 1, 3, 20, 12).action(),
                "four skeletons took full iron from 17 to 11 in 3 s");
        assertEquals(Combat.Action.COVER, Combat.decide("minecraft:skeleton", Gear.BASIC, 1, 1, 14, 12).action());
        assertEquals("skeleton, health 14 in basic armor — cover",
                Combat.decide("minecraft:skeleton", Gear.BASIC, 1, 1, 14, 12).why());
    }

    @Test
    void flyersAlwaysMeanCover_evenInGoodArmor() {
        assertEquals(Combat.Action.COVER, Combat.decide("minecraft:ghast", Gear.GOOD, 1, 1, 20, 12).action());
        assertEquals(Combat.Action.COVER, Combat.decide("minecraft:blaze", Gear.GOOD, 1, 1, 20, 12).action());
    }

    @Test
    void neverHit_creepersStrongOnesNeutralsAndUnknowns() {
        for (String t : List.of("minecraft:creeper", "minecraft:zombified_piglin", "minecraft:enderman",
                "minecraft:vindicator", "minecraft:warden", "somemod:thing")) {
            assertFalse(Combat.decide(t, Gear.GOOD, 1, 0, 20, 12).fight(), t);
        }
        assertEquals(Combat.Kind.LEAVE_ALONE, Combat.kind("minecraft:zombified_piglin"));
    }

    @Test
    void gearByArmorPoints() {
        assertEquals(Gear.BARE, Gear.of(0));
        assertEquals(Gear.BASIC, Gear.of(7));     // e.g. chain or a few iron pieces
        assertEquals(Gear.GOOD, Gear.of(15));     // full iron
    }

    @Test
    void bestWeapon_inTheHotbar_byMaterialThenSwordOverAxe() {
        List<ItemView> inv = List.of(
                new ItemView(2, "minecraft:wooden_sword", 1, null),
                new ItemView(3, "minecraft:iron_axe", 1, null),
                new ItemView(4, "minecraft:iron_sword", 1, null),
                new ItemView(12, "minecraft:diamond_sword", 1, null),   // not in the hotbar
                new ItemView(5, "minecraft:bread", 3, null));
        assertEquals(4, Combat.bestWeapon(inv).getAsInt());
        assertTrue(Combat.bestWeapon(List.of(new ItemView(0, "minecraft:dirt", 64, null))).isEmpty());
    }

    // ------------------------------------------------------------------ in the bot

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
        return String.join("\n", b.decisions().latest(50).stream().map(Object::toString).toList());
    }

    static EntityView mob(FakeWorld w, String type, double x, double z) {
        String name = type.substring(type.indexOf(':') + 1);
        EntityView e = new EntityView(w.nearby.size() + 500, UUID.randomUUID(), name, type, EntityView.Kind.HOSTILE,
                new Vec3(x, 64, z));
        w.nearby.add(e);
        return e;
    }

    static void hitBy(FakeWorld w, EntityView e) {
        w.damage = new Damage("minecraft:mob_attack", e.name(), e.pos(), e.uuid());
    }

    @Test
    void aZombieHitsMidWalk_swordOut_hitOnTheRecharge_thenTheWalkGoesOn() {
        FakeWorld w = new FakeWorld("Bluetails").give(3, "minecraft:iron_sword", 1, null);
        Bot b = walking(w);
        EntityView z = mob(w, "minecraft:zombie", 2, 0);
        hitBy(w, z);
        w.attackReady = false;
        ticks(b, w, 2);
        assertEquals(3, w.selected, "the sword in hand");
        assertTrue(w.attacked.isEmpty(), "no swing before the recharge");
        w.attackReady = true;
        ticks(b, w, 1);
        assertEquals(List.of(z.id()), w.attacked);
        assertTrue(log(b).contains("fighting zombie") && log(b).contains("hit by zombie, bare armor"), log(b));

        w.damage = null;
        w.nearby.clear();                                        // dead
        ticks(b, w, 3);
        assertEquals(new io.github.liangforstudy.zymbot.core.api.BlockPos(200, 64, 0).x(), w.paths.goal.x(),
                "back on the walk: " + log(b));
    }

    @Test
    void aZombieOutOfReach_isClosedOnFirst() {
        FakeWorld w = new FakeWorld("Bluetails");
        Bot b = walking(w);
        EntityView z = mob(w, "minecraft:zombie", -6, 0);
        hitBy(w, z);
        ticks(b, w, 2);
        assertEquals(-6, w.paths.goal.x(), "walks to it");
        assertTrue(w.attacked.isEmpty());
    }

    @Test
    void twoZombies_bare_backsOff() {
        FakeWorld w = new FakeWorld("Bluetails");
        Bot b = walking(w);
        EntityView z = mob(w, "minecraft:zombie", -2, 0);
        mob(w, "minecraft:zombie", -3, 1);
        hitBy(w, z);
        ticks(b, w, 2);
        assertTrue(w.attacked.isEmpty());
        assertTrue(w.paths.goal.x() > 10, "runs east, away: " + w.paths.goal);
        assertTrue(log(b).contains("2 mobs, bare armor fights 1"), log(b));
    }

    @Test
    void mobsInACaveBelow_areNoCrowd_andACreeperThereIsNoThreat() {
        FakeWorld w = new FakeWorld("Bluetails");
        Bot b = walking(w);
        EntityView z = mob(w, "minecraft:zombie", 2, 0);
        w.nearby.add(new EntityView(900, UUID.randomUUID(), "skeleton", "minecraft:skeleton", EntityView.Kind.HOSTILE,
                new Vec3(1, 40, 1)));
        w.nearby.add(new EntityView(901, UUID.randomUUID(), "creeper", "minecraft:creeper", EntityView.Kind.HOSTILE,
                new Vec3(-1, 40, 0)));
        hitBy(w, z);
        ticks(b, w, 2);
        assertEquals(List.of(z.id()), w.attacked.subList(0, 1), log(b));
    }

    @Test
    void twoZombies_inFullIron_areFought() {
        FakeWorld w = new FakeWorld("Bluetails");
        w.armor = 15;
        Bot b = walking(w);
        EntityView z = mob(w, "minecraft:zombie", -2, 0);
        mob(w, "minecraft:zombie", -3, 1);
        hitBy(w, z);
        ticks(b, w, 2);
        assertEquals(List.of(z.id()), w.attacked.subList(0, 1));
    }

    @Test
    void aSiegeGrowsMidFight_theFightGivesWayToBackingOff() {
        FakeWorld w = new FakeWorld("Bluetails");
        w.armor = 7;                                                // basic: 2 at once
        Bot b = walking(w);
        EntityView z = mob(w, "minecraft:zombie", -2, 0);
        hitBy(w, z);
        ticks(b, w, 2);
        assertFalse(w.attacked.isEmpty(), "one zombie: fought");
        mob(w, "minecraft:zombie", -3, 2);                          // two more arrive
        mob(w, "minecraft:zombie", -3, -2);
        ticks(b, w, FightTask.RECHECK_TICKS + io.github.liangforstudy.zymbot.core.brain.Brain.REPEAT_DELAY_TICKS + 2);
        assertTrue(w.paths.goal.x() > 10, "backing off east: " + w.paths.goal + "\n" + log(b));
        assertTrue(log(b).contains("3 mobs, basic armor fights 2"), log(b));
    }

    @Test
    void aZombieWalkingUp_isFoughtBeforeItHits() {
        FakeWorld w = new FakeWorld("Bluetails");
        Bot b = walking(w);
        EntityView z = mob(w, "minecraft:zombie", -2, 0);
        ticks(b, w, 2);
        assertEquals(List.of(z.id()), w.attacked.subList(0, 1), log(b));
        assertTrue(log(b).contains("2 blocks off: zombie, bare armor"), log(b));
    }

    @Test
    void twoZombiesWalkingUp_bare_backsOffBeforeTheyHit() {
        FakeWorld w = new FakeWorld("Bluetails");
        Bot b = walking(w);
        mob(w, "minecraft:zombie", -5, 0);
        mob(w, "minecraft:zombie", -5, 1);
        ticks(b, w, 2);
        assertTrue(w.attacked.isEmpty());
        assertTrue(w.paths.goal.x() > 10, "runs east, away: " + w.paths.goal + "\n" + log(b));
    }

    @Test
    void aZombieFurtherOff_orAnEndermanClose_isLeftBe() {
        FakeWorld w = new FakeWorld("Bluetails");
        Bot b = walking(w);
        mob(w, "minecraft:zombie", -10, 0);
        mob(w, "minecraft:enderman", -2, 0);
        ticks(b, w, 3);
        assertTrue(w.attacked.isEmpty());
        assertEquals(200, w.paths.goal.x(), "still walking: " + log(b));
    }

    @Test
    void aSkeletonInSight_isRushed_butNotOneBehindAWall() {
        FakeWorld w = new FakeWorld("Bluetails");
        wall(w);                                                    // at x 3, between us and it
        Bot b = walking(w);
        mob(w, "minecraft:skeleton", 12, 0);
        ticks(b, w, 3);
        assertEquals(200, w.paths.goal.x(), "out of its sight: " + log(b));
        w.blocks.clear();
        ticks(b, w, 3);
        assertEquals(12, w.paths.goal.x(), "rushes it: " + log(b));
    }

    @Test
    void aCreeperClose_isBackedAwayFrom_beforeItHits() {
        FakeWorld w = new FakeWorld("Bluetails");
        w.armor = 20;
        Bot b = walking(w);
        mob(w, "minecraft:creeper", -3, 0);
        ticks(b, w, 2);
        assertTrue(w.attacked.isEmpty());
        assertTrue(w.paths.goal.x() > 10, "away from it: " + w.paths.goal);
        assertTrue(log(b).contains("a creeper 3 blocks away"), log(b));
    }

    @Test
    void aZombifiedPiglin_isNeverHitBack() {
        FakeWorld w = new FakeWorld("Bluetails");
        w.armor = 20;
        Bot b = walking(w);
        EntityView p = mob(w, "minecraft:zombified_piglin", -2, 0);
        hitBy(w, p);
        ticks(b, w, 5);
        assertTrue(w.attacked.isEmpty());
        assertTrue(w.paths.goal.x() > 10, w.paths.goal.toString());
    }

    @Test
    void aLongFight_turningAndBobbing_isNotFidgeting() {
        FakeWorld w = new FakeWorld("Bluetails").give(0, "minecraft:iron_sword", 1, null);
        w.armor = 15;
        Bot b = new Bot(new ZymbotConfig(), dir.resolve("zymbot.json"), dir.resolve(w.name), w.id, false, clock, Profile.ANARCHY);
        b.onJoin("9b9t.com", w.name);
        b.start("test");                                           // handed over: the watchdog runs (a borrow has none)
        EntityView z = mob(w, "minecraft:zombie", 2, 0);
        hitBy(w, z);
        for (int i = 0; i < FidgetWatchdog.WINDOW_TICKS + 40; i++) {   // what a fight looks like: turning, hopping
            w.yaw += 15;
            w.pos = new Vec3(0.5, 64 + 0.3 * Math.sin(i * 0.6), 0.5);
            ticks(b, w, 1);
        }
        assertFalse(log(b).contains("holding still"), log(b));
        assertTrue(w.attacked.size() > 100, "still swinging: " + w.attacked.size());
    }

    @Test
    void started_theOwnersKeysPauseTheFight_andItGoesOnHalfASecondAfterTheyLetGo() {
        FakeWorld w = new FakeWorld("Bluetails").give(0, "minecraft:iron_sword", 1, null);
        Bot b = new Bot(new ZymbotConfig(), dir.resolve("zymbot.json"), dir.resolve(w.name), w.id, false, clock, Profile.ANARCHY);
        b.onJoin("9b9t.com", w.name);
        b.start("test");
        EntityView z = mob(w, "minecraft:zombie", 2, 0);
        hitBy(w, z);
        ticks(b, w, 2);
        assertFalse(w.attacked.isEmpty(), "fighting");
        for (int i = 0; i < 20; i++) {                             // the owner holds a key for a second
            b.humanInput(w);
            int before = w.attacked.size();
            ticks(b, w, 1);
            assertEquals(before, w.attacked.size(), "no swings while the owner moves");
        }
        int before = w.attacked.size();
        ticks(b, w, new ZymbotConfig().anarchyResumeMillis / 50 + 3);
        assertTrue(w.attacked.size() > before, "fighting again just after letting go: " + log(b));
        assertEquals(1, w.notices.stream().filter(n -> n.contains("takes over again")).count(), w.notices.toString());
        b.humanInput(w);                                           // a second press: no second notice
        ticks(b, w, 20);
        assertEquals(1, w.notices.stream().filter(n -> n.contains("takes over again")).count(), w.notices.toString());
    }

    /** A stone wall two high, three blocks east, from z -4 to 4. */
    static void wall(FakeWorld w) {
        for (int z = -4; z <= 4; z++) {
            w.blocks.put(new io.github.liangforstudy.zymbot.core.api.BlockPos(3, 64, z), "minecraft:stone");
            w.blocks.put(new io.github.liangforstudy.zymbot.core.api.BlockPos(3, 65, z), "minecraft:stone");
        }
    }

    @Test
    void twoSkeletonsBare_takesCoverBehindTheWall() {
        FakeWorld w = new FakeWorld("Bluetails");
        wall(w);
        Bot b = walking(w);
        EntityView s = mob(w, "minecraft:skeleton", -14, 0);
        mob(w, "minecraft:skeleton", -14, 3);
        hitBy(w, s);
        ticks(b, w, 2);
        assertTrue(w.attacked.isEmpty());
        assertEquals(4, w.paths.goal.x(), "just behind the wall: " + w.paths.goal + "\n" + log(b));
        assertTrue(Math.abs(w.paths.goal.z()) <= 3, w.paths.goal.toString());
        assertTrue(log(b).contains("2 shooters, bare armor rushes 1 — cover"), log(b));
    }

    @Test
    void aGhast_inGoodArmor_takesCover_andStaysWhileHidden() {
        FakeWorld w = new FakeWorld("Bluetails");
        w.armor = 20;
        wall(w);
        Bot b = walking(w);
        EntityView g = new EntityView(700, UUID.randomUUID(), "ghast", "minecraft:ghast", EntityView.Kind.HOSTILE,
                new Vec3(-16, 64, 0));
        w.nearby.add(g);
        hitBy(w, g);
        ticks(b, w, 2);
        assertEquals(4, w.paths.goal.x(), log(b));
        w.paths.arrive();
        w.damage = null;
        ticks(b, w, 40);
        assertEquals(4, (int) Math.floor(w.pos.x()), "stays in cover while the ghast is about: " + log(b));
        w.nearby.clear();                                          // it drifts off
        ticks(b, w, 3 * 20 + 25);                                 // gone for 3 s: not just flickering out of range
        assertEquals(200, w.paths.goal.x(), "then back on the walk: " + log(b));
    }

    @Test
    void inCover_aSkeletonThatWalksRoundIntoReach_isHit() {
        FakeWorld w = new FakeWorld("Bluetails").give(1, "minecraft:iron_sword", 1, null);
        wall(w);
        Bot b = walking(w);
        EntityView s = mob(w, "minecraft:skeleton", -14, 0);
        mob(w, "minecraft:skeleton", -14, 3);
        hitBy(w, s);
        ticks(b, w, 2);
        w.paths.arrive();                                          // behind the wall
        w.nearby.set(0, new EntityView(s.id(), s.uuid(), s.name(), s.type(), s.kind(),
                new Vec3(w.pos.x() + 2, 64, w.pos.z())));           // it came round
        ticks(b, w, 2);
        assertTrue(w.attacked.contains(s.id()), log(b));
        assertEquals(1, w.selected, "sword out");
    }

    @Test
    void criticalHealth_withShootersAbout_takesCoverRatherThanRunning() {
        FakeWorld w = new FakeWorld("Bluetails");
        w.armor = 15;
        wall(w);
        Bot b = walking(w);
        w.health = 10;                                             // ≤ 12: critical
        EntityView s = mob(w, "minecraft:skeleton", -14, 0);
        hitBy(w, s);
        ticks(b, w, 2);
        assertEquals(4, w.paths.goal.x(), "behind the wall, not 40 blocks east: " + w.paths.goal + "\n" + log(b));
        assertTrue(log(b).contains("health 10 ≤ 12"), log(b));
    }

    @Test
    void noCoverInReach_backsOffInstead() {
        FakeWorld w = new FakeWorld("Bluetails");
        Bot b = walking(w);
        EntityView s = mob(w, "minecraft:skeleton", -14, 0);
        mob(w, "minecraft:skeleton", -14, 3);
        hitBy(w, s);
        ticks(b, w, 3);
        assertTrue(w.paths.goal.x() > 20, "open field: runs east " + w.paths.goal + "\n" + log(b));
    }

    @Test
    void backingOff_goesAroundAnotherMobAhead_notThroughIt() {
        FakeWorld w = new FakeWorld("Bluetails");
        Bot b = walking(w);
        EntityView p = mob(w, "minecraft:zombified_piglin", -3, 0);    // behind us, never hit back
        mob(w, "minecraft:zombie", 8, 0);                              // and one straight ahead
        hitBy(w, p);
        ticks(b, w, 2);
        var goal = w.paths.goal;
        double angle = Math.toDegrees(Math.atan2(Math.abs(goal.z()), goal.x()));
        assertTrue(angle >= 45, "turned well off the zombie's line: " + goal + " (" + Math.round(angle) + "°)");
        assertTrue(angle < 135, "but still away from the piglin: " + goal);
    }

    @Test
    void backingOff_turnsAlongACliffFace_notIntoIt_andNeverOverLava() {
        FakeWorld w = new FakeWorld("Bluetails");
        for (int x = 3; x <= 6; x++) for (int z = -12; z <= 12; z++) for (int y = 64; y <= 72; y++)
            w.blocks.put(new io.github.liangforstudy.zymbot.core.api.BlockPos(x, y, z), "minecraft:netherrack");
        for (int x = -12; x <= 12; x++) for (int z = 2; z <= 14; z++)          // a lava lake to the south
            w.blocks.put(new io.github.liangforstudy.zymbot.core.api.BlockPos(x, 63, z), "minecraft:lava");
        Bot b = walking(w);
        EntityView p = mob(w, "minecraft:zombified_piglin", -3, 0);
        hitBy(w, p);
        ticks(b, w, 2);
        var goal = w.paths.goal;
        assertTrue(goal.z() < -10, "along the wall, away from the lava: north " + goal + "\n" + log(b));
        assertTrue(goal.x() < 3, "not into the wall: " + goal);
    }

    @Test
    void zymcivKeepsItsOwnRules() {
        Bot b = new Bot(new ZymbotConfig(), dir.resolve("z.json"), dir.resolve("Bot1"), new FakeWorld("Bot1").id, true, clock);
        assertFalse(b.reflexes().contains("fight-or-flee"));
    }
}
