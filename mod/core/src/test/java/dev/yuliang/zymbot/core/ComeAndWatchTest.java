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
}
