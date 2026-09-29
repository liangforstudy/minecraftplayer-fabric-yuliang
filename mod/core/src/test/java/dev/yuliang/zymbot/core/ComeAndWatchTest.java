package dev.yuliang.zymbot.core;

import static org.junit.jupiter.api.Assertions.*;

import dev.yuliang.zymbot.core.api.BlockPos;
import dev.yuliang.zymbot.core.api.Vec3;
import dev.yuliang.zymbot.core.config.ZymbotConfig;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ComeAndWatchTest {
    @TempDir Path dir;
    final FakeClock clock = new FakeClock();

    Bot running(FakeWorld w, ZymbotConfig cfg) {
        Bot b = new Bot(cfg, dir.resolve(w.name + ".json"), dir.resolve(w.name), w.id, true, clock);
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

    @Test
    void come_stopsAFewBlocksShort_notOnTopOfYou() {
        FakeWorld w = new FakeWorld("Bot1");
        w.player("Bot2", 30, 0);
        Bot b = running(w, new ZymbotConfig());
        ticks(b, w, 1);
        assertTrue(b.come("Bot2").startsWith("coming to Bot2 at 30 0"));
        ticks(b, w, 3);
        assertEquals(new BlockPos(30, 0, 0), w.paths.goal);
        w.pos = new Vec3(27, 64, 0.5);                         // 3 blocks away: close enough
        ticks(b, w, 2);
        assertFalse(b.hasOrder());
        assertFalse(w.paths.busy, "stops there");
    }

    @Test
    void come_usesTheLastAnnouncement_whenOutOfSight() {
        FakeWorld w = new FakeWorld("Bot1");
        Bot b = running(w, new ZymbotConfig());
        ticks(b, w, 1);
        assertTrue(b.come("Bot2").contains("don't know where Bot2 is"));
        b.memory().roster.put("x", new dev.yuliang.zymbot.core.store.BotMemory.RosterEntry("Bot2", clock.now, 77, -237, "TEAMMATE"));
        assertTrue(b.come("bot2").contains("77 -237 (where they last announced)"));
    }

    @Test
    void watch_sendsMyDecisionsByMsg_untilStopped() {
        FakeBus net = new FakeBus();
        ZymbotConfig a = new ZymbotConfig(), c = new ZymbotConfig();
        a.teamKey = c.teamKey = "shared-team-key-123";
        FakeWorld w1 = new FakeWorld("Bot1"), w2 = new FakeWorld("Bot2");
        FakeWorld.sameServer(w1, w2);
        Bot bot = running(w1, a);
        Bot human = new Bot(c, dir.resolve("h.json"), dir.resolve("h"), w2.id, false, clock);
        human.onJoin("127.0.0.1:25565", "Bot2");
        bot.attachTransport(net.endpoint());
        human.attachTransport(net.endpoint());
        human.tick(w2, w2);
        human.watch("Bot1", true);
        ticks(bot, w1, 2);
        assertTrue(w1.said.stream().anyMatch(s -> s.startsWith("/msg Bot2 watching")), w1.said.toString());
        bot.goTo(10, 64, 0);
        ticks(bot, w1, 80);                                    // chat is rate-limited: give it time
        assertTrue(w1.said.stream().anyMatch(s -> s.startsWith("/msg Bot2 walking to 10 64 0 — because ordered")), w1.said.toString());

        human.watch("Bot1", false);
        ticks(bot, w1, 40);
        int before = w1.said.size();
        bot.goTo(20, 64, 0);
        ticks(bot, w1, 80);
        assertTrue(w1.said.subList(before, w1.said.size()).stream().noneMatch(s -> s.contains("walking to 20")), w1.said.toString());
    }

    @Test
    void see_asksTheBotOverTheBus_andPrintsItsStatus() {
        FakeBus net = new FakeBus();
        ZymbotConfig a = new ZymbotConfig(), c = new ZymbotConfig();
        a.teamKey = c.teamKey = "shared-team-key-123";
        FakeWorld w1 = new FakeWorld("Bot1"), w2 = new FakeWorld("Bot2");
        FakeWorld.sameServer(w1, w2);
        Bot bot = running(w1, a);
        Bot human = new Bot(c, dir.resolve("h.json"), dir.resolve("h"), w2.id, false, clock);
        human.onJoin("127.0.0.1:25565", "Bot2");
        bot.attachTransport(net.endpoint());
        human.attachTransport(net.endpoint());
        human.tick(w2, w2);
        assertEquals(java.util.List.of("asking Bot1 for its status…"), human.see("Bot1"));
        ticks(bot, w1, 1);                                     // the bot answers
        human.tick(w2, w2);                                    // the asker prints it
        assertTrue(w2.notices.get(0).matches("Bot1 status \\(at \\d\\d:\\d\\d:\\d\\d\\):"), w2.notices.toString());
        assertTrue(w2.notices.stream().anyMatch(s -> s.startsWith("  phase: ")), w2.notices.toString());
        assertEquals(1, w2.notices.stream().filter(s -> s.startsWith("  phase: ")).count(), "printed once");
        assertTrue(w2.notices.size() > 5, "every line: " + w2.notices);
        assertTrue(w1.notices.isEmpty(), "nothing shown on the bot's own screen");
    }

    @Test
    void see_saysNoAnswer_afterTheTimeout() {
        FakeWorld w2 = new FakeWorld("Bot2");
        Bot human = new Bot(new ZymbotConfig(), dir.resolve("h.json"), dir.resolve("h"), w2.id, false, clock);
        human.onJoin("127.0.0.1:25565", "Bot2");
        human.tick(w2, w2);
        human.see("Bot9");
        clock.now += Bot.SEE_TIMEOUT_MILLIS - 100;
        human.tick(w2, w2);
        assertTrue(w2.notices.isEmpty(), "not yet");
        clock.now += 200;
        human.tick(w2, w2);
        assertEquals(1, w2.notices.size(), w2.notices.toString());
        assertTrue(w2.notices.get(0).startsWith("no answer from Bot9 in 5s"), w2.notices.toString());
    }

    @Test
    void grave_walkOver_rightClickWithAnEmptyHand_untilItsGone() {
        FakeWorld w = new FakeWorld("Bot1");
        w.give(0, "minecraft:stick", 1, null);
        BlockPos g = new BlockPos(10, 64, 0);
        w.blocks.put(g, "civfabric:grave");
        Bot b = running(w, new ZymbotConfig());
        ticks(b, w, 1);
        assertTrue(b.grave().startsWith("going to my grave at 10 64 0"));   // owner unknown: the click tells
        ticks(b, w, 2);
        assertEquals(g, w.paths.goal, "walks over");
        w.pos = new Vec3(9.5, 64, 0.5);
        ticks(b, w, 2);
        assertEquals(java.util.List.of(g), w.used, "right-clicks it");
        assertEquals(1, w.selected, "with an empty hand (slot 0 holds a stick)");
        w.blocks.remove(g);                                     // the grave gives everything back and vanishes
        ticks(b, w, 2);
        assertFalse(b.hasOrder());
        assertTrue(b.decisions().latest(5).stream().anyMatch(d -> d.toString().startsWith("done: pick up my grave")));
    }

    // ------------------------------------------------------------------ PHASE3_FIXLIST #1: my grave vs grave loot

    static dev.yuliang.zymbot.core.api.GraveOwner owner(String name) {
        return new dev.yuliang.zymbot.core.api.GraveOwner(name, java.util.UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes()));
    }

    String log(Bot b) {
        return String.join("\n", b.decisions().latest(50).stream().map(Object::toString).toList());
    }

    @Test
    void grave_skipsSomeoneElsesNearerGrave_forMine() {
        // 2026-09-27: /zbot grave opened a stranger's grave, the nearest one, then failed "not ours?"
        FakeWorld w = new FakeWorld("Bot1");
        BlockPos theirs = new BlockPos(3, 64, 0), mine = new BlockPos(12, 64, 0);
        w.blocks.put(theirs, "civfabric:grave");
        w.blocks.put(mine, "civfabric:grave");
        w.graveOwners.put(theirs, owner("Stranger"));
        w.graveOwners.put(mine, owner("Bot1"));
        Bot b = running(w, new ZymbotConfig());
        ticks(b, w, 1);
        String r = b.grave();
        assertTrue(r.startsWith("going to my grave at 12 64 0 (skipped: Stranger)"), r);
        assertTrue(log(b).contains("chose Bot1's grave at 12 64 0 — because wanted a grave of mine"), log(b));
    }

    @Test
    void grave_onlyStrangersGraves_walksToTheDeathSpot_neverOpensThem() {
        FakeWorld w = new FakeWorld("Bot1");
        BlockPos theirs = new BlockPos(3, 64, 0);
        w.blocks.put(theirs, "civfabric:grave");
        w.graveOwners.put(theirs, owner("Stranger"));
        w.lastDeath = "minecraft:overworld 200, 64, 0";
        Bot b = running(w, new ZymbotConfig());
        ticks(b, w, 1);
        String r = b.grave();
        assertTrue(r.startsWith("no grave of mine in sight (skipped: Stranger) — walking back to where I died (200 64 0)"), r);
        String loot = b.graveLoot(java.util.List.of("Bot1"), false);  // "grave loot <me>": no death-spot walk
        assertEquals("no grave of bot1 within 16 blocks (skipped: Stranger)", loot);
    }

    @Test
    void grave_unknownOwnerOpensAsAChest_closedAtOnce_noLooting() {
        FakeWorld w = new FakeWorld("Bot1");
        BlockPos g = new BlockPos(2, 64, 0);
        w.blocks.put(g, "civfabric:grave");                     // owner not known to this client
        Bot b = running(w, new ZymbotConfig());
        ticks(b, w, 1);
        b.grave();
        ticks(b, w, 3);
        assertEquals(java.util.List.of(g), w.used);
        w.containerFilled = 12;                                 // civfabric: not the owner → chest screen
        ticks(b, w, 2);
        assertEquals(1, w.closes, "closed at once");
        assertEquals(0, w.takeAlls, "nothing taken");
        assertTrue(log(b).contains("it opened as a chest"), log(b));
    }

    @Test
    void graveLoot_takesFromTheNamedPlayersGrave_untilEmpty() {
        FakeWorld w = new FakeWorld("Bot1");
        BlockPos a = new BlockPos(2, 64, 0), c = new BlockPos(4, 64, 0), mine = new BlockPos(1, 64, 0);
        w.blocks.put(a, "civfabric:grave");
        w.blocks.put(c, "civfabric:grave");
        w.blocks.put(mine, "civfabric:grave");
        w.graveOwners.put(a, owner("Alice"));
        w.graveOwners.put(c, owner("Carol"));
        w.graveOwners.put(mine, owner("Bot1"));
        Bot b = running(w, new ZymbotConfig());
        ticks(b, w, 1);
        assertTrue(b.graveLoot(java.util.List.of("carol"), false).startsWith("going to Carol's grave at 4 64 0"));
        assertTrue(b.graveLoot(java.util.List.of(), false).startsWith("going to Alice's grave at 2 64 0"), "any but mine, nearest");
        assertTrue(b.graveLoot(java.util.List.of("Alice"), true).startsWith("going to Carol's grave"), "except Alice");
        w.pos = new Vec3(3.5, 64, 0.5);                         // beside it
        ticks(b, w, 3);
        assertEquals(java.util.List.of(c), w.used);
        w.containerFilled = 5;
        ticks(b, w, 1);
        assertEquals(1, w.takeAlls);
        w.containerFilled = 0;                                  // the server moved it all over
        ticks(b, w, 2);
        assertEquals(1, w.closes);
        assertTrue(log(b).contains("looted 5 of 5 stacks — because from Carol's grave at 4 64 0"), log(b));
        assertFalse(b.hasOrder());
    }

    @Test
    void graveLootMe_isMyGrave_byTheSamePath() {
        FakeWorld w = new FakeWorld("Bot1");
        BlockPos mine = new BlockPos(2, 64, 0);
        w.blocks.put(mine, "civfabric:grave");
        w.graveOwners.put(mine, owner("Bot1"));
        Bot b = running(w, new ZymbotConfig());
        ticks(b, w, 1);
        assertTrue(b.graveLoot(java.util.List.of("bot1"), false).startsWith("going to my grave at 2 64 0"));
        ticks(b, w, 3);
        w.blocks.remove(mine);                                  // given back: gone
        ticks(b, w, 2);
        assertTrue(log(b).contains("done: pick up my grave"), log(b));
        assertEquals(0, w.takeAlls);
    }

    @Test
    void teammate_grave_borrowsTheControls() {
        FakeWorld w = new FakeWorld("Human");
        BlockPos mine = new BlockPos(2, 64, 0);
        w.blocks.put(mine, "civfabric:grave");
        w.graveOwners.put(mine, owner("Human"));
        ZymbotConfig c = new ZymbotConfig();
        c.accounts.add(new ZymbotConfig.Account(w.id, "Human", ZymbotConfig.Role.TEAMMATE));
        Bot b = new Bot(c, dir.resolve("h.json"), dir.resolve("h"), w.id, false, clock);
        b.onJoin("x", "Human");
        b.tick(w, w);
        String r = b.grave();
        assertTrue(r.startsWith("going to my grave at 2 64 0") && r.contains("borrowing your controls"), r);
        ticks(b, w, 3);
        assertEquals(java.util.List.of(mine), w.used);
        w.blocks.remove(mine);
        ticks(b, w, 3);
        assertFalse(b.isBorrowing(), log(b));
        assertTrue(log(b).contains("gave the controls back"), log(b));
    }
}
