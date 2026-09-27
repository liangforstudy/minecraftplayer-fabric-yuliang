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
        assertEquals("Bot1 status:", w2.notices.get(0), w2.notices.toString());
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
        assertTrue(b.grave().startsWith("going to the grave at 10 64 0"));
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
}
