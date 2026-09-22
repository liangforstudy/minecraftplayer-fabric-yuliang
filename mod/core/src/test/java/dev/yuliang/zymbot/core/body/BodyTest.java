package dev.yuliang.zymbot.core.body;

import static org.junit.jupiter.api.Assertions.*;

import dev.yuliang.zymbot.core.Bot;
import dev.yuliang.zymbot.core.FakeClock;
import dev.yuliang.zymbot.core.FakeWorld;
import dev.yuliang.zymbot.core.api.BlockPos;
import dev.yuliang.zymbot.core.api.Damage;
import dev.yuliang.zymbot.core.api.Vec3;
import dev.yuliang.zymbot.core.brain.Brain;
import dev.yuliang.zymbot.core.brain.DecisionLog;
import dev.yuliang.zymbot.core.brain.Objective;
import dev.yuliang.zymbot.core.config.ZymbotConfig;
import dev.yuliang.zymbot.core.task.FollowTask;
import dev.yuliang.zymbot.core.task.WalkTask;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** PHASE1.md "done means", run against the fake world. */
class BodyTest {
    @TempDir Path dir;
    final FakeClock clock = new FakeClock();

    Bot running(FakeWorld w, ZymbotConfig cfg, boolean headless) {
        Bot b = new Bot(cfg, dir.resolve("zymbot.json"), dir.resolve(w.name), w.id, headless, clock);
        b.onJoin("127.0.0.1:25565", w.name);
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
        return String.join("\n", b.decisions().latest(50).stream().map(Object::toString).toList());
    }

    // ------------------------------------------------------------------ food choice

    @Test
    void spiceOfFabric_varietyBeatsRepeats() {
        FakeWorld w = new FakeWorld("Bot1").give(0, "minecraft:bread", 5, FakeWorld.food(5))
                .give(1, "minecraft:carrot", 5, FakeWorld.food(3));
        w.hunger = 10;
        assertEquals("minecraft:bread", FoodChooser.best(w, List.of(), List.of(), false).orElseThrow().id());
        // bread twice in the last 11: 5 × 0.49 = 2.45 < carrot 3
        assertEquals("minecraft:carrot",
                FoodChooser.best(w, List.of("minecraft:bread", "minecraft:bread"), List.of(), false).orElseThrow().id());
    }

    @Test
    void neverEatsHarmfulOrListedFood_andNothingAtFullHunger() {
        FakeWorld w = new FakeWorld("Bot1").give(0, "minecraft:rotten_flesh", 5, FakeWorld.poison(4))
                .give(1, "minecraft:dried_kelp", 5, FakeWorld.food(1));
        w.hunger = 5;
        assertTrue(FoodChooser.best(w, List.of(), new ZymbotConfig().neverEat, false).isEmpty());

        w.give(2, "minecraft:bread", 1, FakeWorld.food(5));
        w.hunger = 20;
        assertTrue(FoodChooser.best(w, List.of(), List.of(), false).isEmpty(), "can't eat bread at full hunger");
        w.give(3, "minecraft:golden_apple", 1, FakeWorld.healing(4));
        assertEquals("minecraft:golden_apple", FoodChooser.best(w, List.of(), List.of(), false).orElseThrow().id());
    }

    @Test
    void healingFoodIsKeptForCriticalHealth() {
        FakeWorld w = new FakeWorld("Bot1").give(0, "minecraft:carrot", 5, FakeWorld.food(3))
                .give(1, "farm_and_charm:nettle_tea_cup", 2, FakeWorld.healing(1))
                .give(2, "minecraft:golden_apple", 1, FakeWorld.healing(4));
        w.hunger = 10;
        assertEquals("minecraft:carrot", FoodChooser.best(w, List.of("minecraft:carrot", "minecraft:carrot"), List.of(), false)
                .orElseThrow().id(), "a repeated carrot still beats spending a heal on hunger");
        w.inventory.removeIf(i -> i.id().equals("minecraft:carrot"));
        assertEquals("minecraft:golden_apple", FoodChooser.best(w, List.of(), List.of(), false).orElseThrow().id(),
                "...unless heals are all that's left");
    }

    // ------------------------------------------------------------------ hunger reflex (test 3)

    @Test
    void hungry_eatsFromMainInventory_recordsIt_thenRotates() {
        FakeWorld w = new FakeWorld("Bot1").give(12, "minecraft:bread", 3, FakeWorld.food(5))
                .give(20, "minecraft:carrot", 3, FakeWorld.food(3));
        w.hunger = 14;
        Bot b = running(w, new ZymbotConfig(), true);

        ticks(b, w, 3);
        assertTrue(w.useHeld, "holding use: " + log(b));
        assertEquals("minecraft:bread", w.inventory.stream().filter(i -> i.slot() == w.selected).findFirst().orElseThrow().id(),
                "bread was swapped into the hand");
        w.finishBite();                                     // hunger 19
        ticks(b, w, 2);
        assertFalse(w.useHeld, "let go after the bite");
        assertEquals(List.of("minecraft:bread"), b.memory().recentFoods);

        // bread once already (5 × 0.7 = 3.5) still beats carrot (3); twice (2.45) doesn't
        b.memory().recentFoods.add("minecraft:bread");
        w.hunger = 10;
        ticks(b, w, 25);                                   // past the repeat delay
        assertTrue(w.useHeld, log(b));
        assertEquals("minecraft:carrot", w.inventory.stream().filter(i -> i.slot() == w.selected).findFirst().orElseThrow().id());
    }

    @Test
    void hungryWithNoFood_saysSo_andStepsAsideForOrders() {
        FakeWorld w = new FakeWorld("Bot1");
        w.hunger = 3;
        Bot b = running(w, new ZymbotConfig(), true);
        ticks(b, w, 2);
        assertTrue(log(b).contains("no safe food"), log(b));
        b.goTo(50, 64, 0);
        ticks(b, w, 2);
        assertEquals(new BlockPos(50, 64, 0), w.paths.goal, "a failed reflex must not block the order");
    }

    // ------------------------------------------------------------------ critical health (test 4)

    @Test
    void aReflexThatCantHelp_doesntInterruptOneThatCan() {
        FakeWorld w = new FakeWorld("Bot1").give(0, "minecraft:bread", 4, FakeWorld.food(5));
        w.health = 5;                                          // critical, but no heal and no attacker
        w.hunger = 10;
        Bot b = running(w, new ZymbotConfig(), true);
        ticks(b, w, 3);
        assertTrue(w.useHeld, "eating: " + log(b));
        clock.now += 31_000;                                   // critical's cooldown runs out mid-bite
        ticks(b, w, 300);                                      // still inside the 20 s bite window
        assertTrue(w.useHeld, "the bite isn't cancelled by a reflex that can't act: " + log(b));
        assertFalse(log(b).contains("idle — because health"), log(b));
    }

    @Test
    void criticalHealth_eatsAHeal_first() {
        FakeWorld w = new FakeWorld("Bot1").give(0, "minecraft:bread", 5, FakeWorld.food(5))
                .give(1, "farm_and_charm:nettle_tea_cup", 1, FakeWorld.healing(1));
        w.health = 6;
        w.hunger = 12;                                      // hungry too — health comes first
        Bot b = running(w, new ZymbotConfig(), true);
        ticks(b, w, 3);
        assertEquals(1, w.selected, log(b));
        assertTrue(log(b).contains("eating farm_and_charm:nettle_tea_cup — because health 6 ≤ 8"), log(b));
    }

    @Test
    void criticalHealth_noHeal_retreatsAwayFromTheAttacker() {
        FakeWorld w = new FakeWorld("Bot1");
        w.health = 5;
        w.damage = new Damage("minecraft:mob_attack", "Zombie", new Vec3(-3, 64, 0));   // attacker to the west
        Bot b = running(w, new ZymbotConfig(), true);
        ticks(b, w, 2);
        assertNotNull(w.paths.goal, log(b));
        assertTrue(w.paths.goal.x() > 10, "flees east, away from the zombie: " + w.paths.goal);
        assertTrue(w.paths.sprint, "runs — a walker can't outpace a zombie");
        assertTrue(log(b).contains("hurt by Zombie"), log(b));

        w.damage = null;
        w.paths.arrive();
        ticks(b, w, Brain.REPEAT_DELAY_TICKS + 3);
        assertTrue(log(b).contains("failed: recover (health 5 ≤ 8") && log(b).contains("because no healing food"),
                "still critical with nothing to run from: " + log(b));
    }

    // ------------------------------------------------------------------ orders (tests 1, 2, 5, 6, 7)

    @Test
    void goTo_walksAndArrives() {
        FakeWorld w = new FakeWorld("Bot1");
        Bot b = running(w, new ZymbotConfig(), true);
        assertEquals("walking to 200 64 0", b.goTo(200, 64, 0));
        ticks(b, w, 5);
        assertEquals(new BlockPos(200, 64, 0), w.paths.goal);
        assertTrue(b.status().get(1).contains("walking to 200 64 0"), b.status().toString());
        w.paths.arrive();
        ticks(b, w, 1);
        assertFalse(b.hasOrder());
        assertTrue(log(b).contains("done: walk to 200 64 0"), log(b));
    }

    @Test
    void goTo_withoutBaritone_failsWithAReason() {
        FakeWorld w = new FakeWorld("Bot1");
        w.paths.available = false;
        Bot b = running(w, new ZymbotConfig(), true);
        ticks(b, w, 1);
        assertTrue(b.goTo(10, null, 10).contains("install Baritone"));
        ticks(b, w, 2);
        assertTrue(log(b).contains("failed: walking to 10 10 — because no pathfinder"), log(b));
        assertTrue(String.join("\n", b.status()).contains("pathfinder: none — install Baritone"));
    }

    @Test
    void goTo_staysDry_swimsOnlyWhenNoDryPath_andSaysSo() {
        FakeWorld w = new FakeWorld("Bot1");
        Bot b = running(w, new ZymbotConfig(), true);
        b.goTo(50, 64, 0);
        ticks(b, w, 2);
        assertFalse(w.paths.swim, "first try is dry");
        assertFalse(w.paths.sprint, "ordinary walks never sprint (R19)");

        FakeWorld island = new FakeWorld("Bot1");
        island.paths.onlyWet = true;                          // the target is across water
        Bot c = running(island, new ZymbotConfig(), true);
        c.goTo(80, 64, 0);
        ticks(c, island, 50);
        assertTrue(island.paths.swim, log(c));
        assertTrue(island.paths.busy);
        assertTrue(log(c).contains("swimming — because no dry path to 80 64 0"), log(c));
        island.paths.arrive();
        ticks(c, island, 1);
        assertTrue(log(c).contains("done: walk to 80 64 0"), log(c));
    }

    @Test
    void goTo_plansItsOwnCrossing_andLogsWhy() {
        String[] m = dev.yuliang.zymbot.core.route.RoutePlannerTest.grid(60, 60, '~');
        dev.yuliang.zymbot.core.route.RoutePlannerTest.paint(m, 0, 0, 59, 19, '.');
        dev.yuliang.zymbot.core.route.RoutePlannerTest.paint(m, 25, 25, 35, 35, '.');
        FakeWorld w = new FakeWorld("Bot1");
        w.terrain = dev.yuliang.zymbot.core.route.RoutePlannerTest.map(m);
        w.pos = new Vec3(30.5, 64, 5.5);
        Bot b = running(w, new ZymbotConfig(), true);
        b.goTo(30, 64, 30);
        ticks(b, w, 2);
        assertTrue(log(b).contains("planning to swim 5 blocks — because there's no dry way within 96 blocks"), log(b));
        assertEquals(19, w.paths.goal.z(), "first leg: the near shore");
        assertFalse(w.paths.swim, "dry leg: water banned");
        w.paths.arrive();
        ticks(b, w, 2);
        assertEquals(25, w.paths.goal.z(), "second leg: across to the island");
        assertTrue(w.paths.swim, "the crossing leg may swim");
        w.paths.arrive();
        ticks(b, w, 2);
        assertEquals(new BlockPos(30, 64, 30), w.paths.goal, "then the exact target");
        assertFalse(w.paths.swim);
        w.paths.arrive();
        ticks(b, w, 2);
        assertTrue(log(b).contains("done: walk to 30 64 30"), log(b));
    }

    @Test
    void goTo_aGoalThePlannerCantStandOn_isFinishedByBaritone() {
        String[] m = dev.yuliang.zymbot.core.route.RoutePlannerTest.grid(40, 40, '.');
        dev.yuliang.zymbot.core.route.RoutePlannerTest.paint(m, 30, 30, 30, 30, '~');   // the goal is a one-block puddle
        FakeWorld w = new FakeWorld("Bot1");
        w.terrain = dev.yuliang.zymbot.core.route.RoutePlannerTest.map(m);
        w.pos = new Vec3(5.5, 64, 5.5);
        ZymbotConfig cfg = new ZymbotConfig();
        cfg.swimCostBlocks = 1_000_000;                        // the planner will never pick the puddle
        Bot b = running(w, cfg, true);
        b.goTo(30, null, 30);
        for (int leg = 0; leg < 6 && !new BlockPos(30, 0, 30).equals(w.paths.goal); leg++) {
            ticks(b, w, 2);
            if (w.paths.goal != null && !new BlockPos(30, 0, 30).equals(w.paths.goal)) w.paths.arrive();
        }
        assertEquals(new BlockPos(30, 0, 30), w.paths.goal, "Baritone gets the exact spot for the last bit: " + log(b));
        assertFalse(log(b).contains("no progress"), log(b));
    }

    @Test
    void planningHappensOffTheGameThread_andTheTickNeverWaits() {
        java.util.List<Runnable> queued = new java.util.ArrayList<>();
        var inline = dev.yuliang.zymbot.core.task.RouteTask.PLANNER;
        dev.yuliang.zymbot.core.task.RouteTask.PLANNER = queued::add;       // a planner that hasn't finished yet
        try {
            FakeWorld w = new FakeWorld("Bot1");
            w.terrain = dev.yuliang.zymbot.core.route.RoutePlannerTest.map(dev.yuliang.zymbot.core.route.RoutePlannerTest.grid(60, 60, '.'));
            w.pos = new Vec3(5.5, 64, 5.5);
            Bot b = running(w, new ZymbotConfig(), true);
            b.goTo(50, 64, 50);
            ticks(b, w, 5);
            assertEquals(1, queued.size(), "handed to the planner thread");
            assertNull(w.paths.goal, "not walking yet");
            assertTrue(b.status().get(1).contains("planning a route"), b.status().get(1));
            queued.get(0).run();                                             // the planner finishes
            ticks(b, w, 2);
            assertNotNull(w.paths.goal, "walking the first leg");
        } finally {
            dev.yuliang.zymbot.core.task.RouteTask.PLANNER = inline;
        }
    }

    @Test
    void goTo_startingInWater_swimsAtOnce() {
        FakeWorld w = new FakeWorld("Bot1");
        w.inWater = true;
        Bot b = running(w, new ZymbotConfig(), true);
        b.goTo(50, 64, 0);
        ticks(b, w, 2);
        assertTrue(w.paths.swim);
        assertTrue(log(b).contains("swimming — because already in the water"), log(b));
    }

    @Test
    void follow_swimsAcrossOnlyWhenStalled_thenDryAgain() {
        FakeWorld w = new FakeWorld("Bot1");
        w.player("Bot2", 30, 0);                              // across a river, not getting closer
        Bot b = running(w, new ZymbotConfig(), true);
        b.follow("Bot2");
        ticks(b, w, 3);
        assertFalse(w.paths.swim);
        ticks(b, w, FollowTask.STALLED_TICKS + 5);
        assertTrue(w.paths.swim, log(b));
        assertTrue(log(b).contains("swimming — because no dry way to Bot2"), log(b));

        w.pos = new Vec3(28, 64, 0);                          // made it across
        ticks(b, w, 2);
        assertFalse(w.paths.swim, "back to dry once close");
    }

    // ------------------------------------------------------------------ drowning (FIXLIST #2)

    @Test
    void drowning_stopsTheWalk_swimsForLand_thenTheOrderResumes() {
        FakeWorld w = new FakeWorld("Bot1");
        w.health = 7;                                          // critical too — air comes first
        Bot b = running(w, new ZymbotConfig(), true);
        b.follow("Bot2");
        w.player("Bot2", 3, 0);
        ticks(b, w, 2);
        assertEquals("Bot2", w.paths.following);

        w.inWater = true;                                      // stopped next to Bot2, in the water, sinking
        w.onGround = false;
        w.headInWater = true;
        w.air = 190;
        w.dryLand = new BlockPos(6, 63, 0);
        ticks(b, w, 2);
        assertFalse(w.paths.busy, "the pathfinder stops");
        assertTrue(w.jumpHeld && w.forwardHeld, "swims up and for the shore");
        assertNotNull(w.lookedAt);
        assertEquals(6.5, w.lookedAt.x());
        assertTrue(log(b).contains("swimming to dry land at 6 0 — because air 9s of 15s, under water"), log(b));

        w.inWater = false;                                     // climbed out
        w.headInWater = false;
        w.onGround = true;
        w.air = 300;
        ticks(b, w, 2);
        assertFalse(w.jumpHeld || w.forwardHeld, "lets go of the keys");
        assertEquals("Bot2", w.paths.following, "the follow order resumes");
    }

    @Test
    void drowning_withNoLandInReach_treadsWater() {
        FakeWorld w = new FakeWorld("Bot1");
        w.inWater = true;
        w.onGround = false;
        w.headInWater = true;
        w.air = 150;
        Bot b = running(w, new ZymbotConfig(), true);
        ticks(b, w, 2);
        assertTrue(w.jumpHeld);
        assertFalse(w.forwardHeld);
        assertTrue(log(b).contains("treading water"), log(b));
    }

    @Test
    void goTo_givesUpWhenStuck() {
        FakeWorld w = new FakeWorld("Bot1");
        Bot b = running(w, new ZymbotConfig(), true);
        b.goTo(100, 64, 0);
        ticks(b, w, WalkTask.STUCK_TICKS + 5);                 // busy, but never moves
        assertTrue(log(b).contains("stuck"), log(b));
        assertFalse(w.paths.busy);
    }

    @Test
    void humanTakesTheControls_walkStops_thenResumes() {
        FakeWorld w = new FakeWorld("Bot1");
        ZymbotConfig cfg = new ZymbotConfig();
        cfg.setRole(w.id, "Bot1", ZymbotConfig.Role.BOT);
        Bot b = running(w, cfg, false);
        b.goTo(100, 64, 0);
        ticks(b, w, 2);
        assertTrue(w.paths.busy);
        b.humanInput(w);
        ticks(b, w, 1);
        assertFalse(w.paths.busy, "Baritone stops the moment a human touches the keys");
        ticks(b, w, cfg.humanPauseSeconds * 20 + 2);
        assertTrue(w.paths.busy, "the order resumes after the countdown: " + log(b));
    }

    @Test
    void dyingMidWalk_cancelsTheOrder() {
        FakeWorld w = new FakeWorld("Bot1");
        Bot b = running(w, new ZymbotConfig(), true);
        b.goTo(100, 64, 0);
        ticks(b, w, 2);
        w.dead = true;
        ticks(b, w, 1);
        assertFalse(b.hasOrder());
        assertFalse(w.paths.busy);
        ticks(b, w, 5);                                        // respawned
        assertFalse(w.paths.busy, "doesn't walk off again after respawning");
    }

    @Test
    void dyingAndRespawningBetweenTwoTicks_stillCancelsTheOrder() {
        FakeWorld w = new FakeWorld("Bot1");
        Bot b = running(w, new ZymbotConfig(), true);
        b.goTo(450, 64, 90);
        ticks(b, w, 3);
        assertTrue(w.paths.busy);
        w.lastDeath = "minecraft:overworld 291, 64, 90";        // server: killed and respawned at once
        ticks(b, w, 3);
        assertFalse(b.hasOrder(), "the walk died with it: " + log(b));
        assertFalse(w.paths.busy);
        assertTrue(log(b).contains("died — because respawned at once"), log(b));
    }

    @Test
    void aBenchedReflexActsAsSoonAsItCan() {
        FakeWorld w = new FakeWorld("Bot1");
        w.health = 5;                                           // critical, nothing to do about it
        Bot b = running(w, new ZymbotConfig(), true);
        ticks(b, w, 3);
        assertTrue(log(b).contains("failed: recover"), log(b));
        w.damage = new Damage("minecraft:mob_attack", "Zombie", new Vec3(2, 64, 0));   // then a zombie attacks
        ticks(b, w, Brain.RECHECK_TICKS + 2);
        assertNotNull(w.paths.goal, "retreats within a second, not after the 30 s bench: " + log(b));
        assertEquals(1, b.decisions().latest(50).stream().filter(d -> d.toString().contains("failed: recover")).count(),
                "the bench is quiet — no repeated 'failed' lines");
    }

    @Test
    void follow_givesUpWhenTheyreOutOfSight() {
        FakeWorld w = new FakeWorld("Bot1");
        w.player("Bot2", 5, 5);
        Bot b = running(w, new ZymbotConfig(), true);
        b.follow("Bot2");
        ticks(b, w, 3);
        assertEquals("Bot2", w.paths.following);
        w.nearby.clear();
        ticks(b, w, 30 * 20 + 2);
        assertTrue(log(b).contains("lost sight of Bot2"), log(b));
    }

    @Test
    void ordersNeedTheBotRunning() {
        FakeWorld w = new FakeWorld("Human");
        Bot b = new Bot(new ZymbotConfig(), dir.resolve("zymbot.json"), dir.resolve("h"), w.id, false, clock);
        b.onJoin("x", "Human");
        assertTrue(b.goTo(1, 2, 3).contains("isn't running"));
    }

    // ------------------------------------------------------------------ leash (P1-2)

    @Test
    void leash_pullsBackOnlyWhileWorkingOnItsOwn() {
        FakeWorld w = new FakeWorld("Bot1");
        w.player("Bot2", 150, 0);                              // a human, 150 blocks off
        ZymbotConfig cfg = new ZymbotConfig();
        Body body = new Body(ArrayList::new, id -> false, clock, () -> {});
        List<Objective> plan = new ArrayList<>();
        Brain[] brain = new Brain[1];
        brain[0] = new Brain(List.of(new LeashInterrupt(cfg, body, () -> brain[0].autonomous())),
                world -> plan.stream().findFirst(), new DecisionLog(20, clock));

        brain[0].tick(w, w);
        assertNull(w.paths.goal, "idle: the leash doesn't turn it into a follower");

        brain[0].order(Objective.of("walk", "ordered", (wv, h) -> new WalkTask(h.paths(), new BlockPos(-300, 64, 0), false, 0)));
        brain[0].tick(w, w);
        assertEquals(new BlockPos(-300, 64, 0), w.paths.goal, "a direct order isn't leashed");
        brain[0].cancelOrder("test");

        plan.add(Objective.of("scout", "exploring", (wv, h) -> new WalkTask(h.paths(), new BlockPos(-300, 64, 0), false, 0)));
        brain[0].tick(w, w);                                   // starts its own objective
        brain[0].tick(w, w);                                   // ...and the leash catches it
        assertEquals(new BlockPos(150, 64, 0), w.paths.goal, "working on its own: back toward the human");
        assertTrue(brain[0].describe().contains("Bot2 is 150 blocks away (leash 100)"), brain[0].describe());
    }

    // ------------------------------------------------------------------ plumbing

    @Test
    void chatOut_isRateLimited() {
        FakeWorld w = new FakeWorld("Bot1");
        ChatOut out = new ChatOut(clock);
        out.say("one");
        out.whisper("Bot2", "two");
        out.flush(w);
        out.flush(w);
        assertEquals(List.of("one"), w.said);
        clock.now += ChatOut.MIN_GAP_MILLIS;
        out.flush(w);
        assertEquals(List.of("one", "/msg Bot2 two"), w.said);
    }

    @Test
    void hungerMeter_chargesDrainToTheActivity() {
        FakeWorld w = new FakeWorld("Bot1");
        HungerMeter m = new HungerMeter();
        w.hunger = 20;
        w.saturation = 9;
        for (int i = 0; i < 100; i++) m.sample(w);             // settling after the join: not counted
        w.saturation = 5;
        m.sample(w);
        w.saturation = 5;
        for (int i = 0; i < 1200; i++) {                       // walk for a minute
            w.pos = new Vec3(i * 0.2, 64, 0);
            if (i == 600) w.saturation = 4.9f;
            m.sample(w);
        }
        assertTrue(m.ticks(HungerMeter.Activity.WALKING) > 1100);
        assertTrue(m.lines().stream().noneMatch(l -> l.startsWith("idle") && !l.contains("0.00 food/min")),
                "the join re-sync isn't counted as idle drain: " + m.lines());
        assertTrue(m.lines().stream().anyMatch(l -> l.startsWith("walking 1.0 min, 0.10 food/min")), m.lines().toString());
    }

    @Test
    void memorySurvivesAHardStop() {
        FakeWorld w = new FakeWorld("Bot1");
        Bot b = running(w, new ZymbotConfig(), true);
        ticks(b, w, 1);
        b.memory().recentFoods.add("minecraft:bread");
        b.set("leash", 64);                                    // any change path; memory is marked below
        // eating marks memory dirty; simulate via the body
        w.hunger = 10;
        w.give(0, "minecraft:carrot", 2, FakeWorld.food(3));
        ticks(b, w, 3);
        w.finishBite();
        ticks(b, w, 2);
        clock.now += Bot.SAVE_EVERY_MILLIS;
        ticks(b, w, 1);                                        // periodic save — no onLeave (process killed)

        Bot again = new Bot(new ZymbotConfig(), dir.resolve("zymbot.json"), dir.resolve(w.name), w.id, true, clock);
        again.onJoin("127.0.0.1:25565", w.name);
        assertEquals(List.of("minecraft:bread", "minecraft:carrot"), again.memory().recentFoods);
    }

    @Test
    void tunables_areRangeChecked() {
        FakeWorld w = new FakeWorld("Bot1");
        Bot b = running(w, new ZymbotConfig(), true);
        assertEquals("eat = 12", b.set("eat", 12));
        assertEquals(12, b.config().eatBelowHunger);
        assertTrue(b.set("eat", 25).contains("1–19"));
        assertTrue(b.set("speed", 3).contains("unknown"));
        ZymbotConfig bad = new ZymbotConfig();
        bad.leashBlocks = 5000;
        assertFalse(bad.normalize().isEmpty());
        assertEquals(1000, bad.leashBlocks);
    }

    @Test
    void recentAttackIsForgotten() {
        Body body = new Body(ArrayList::new, id -> false, clock, () -> {});
        FakeWorld w = new FakeWorld("Bot1");
        w.damage = new Damage("minecraft:mob_attack", "Zombie", new Vec3(1, 64, 1));
        body.sense(w);
        assertTrue(body.recentAttack().isPresent());
        clock.now += Body.ATTACK_MEMORY_MILLIS + 1;
        assertEquals(Optional.empty(), body.recentAttack());
    }
}
