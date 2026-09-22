package dev.yuliang.zymbot.core.body;

import static org.junit.jupiter.api.Assertions.*;

import dev.yuliang.zymbot.core.Bot;
import dev.yuliang.zymbot.core.FakeBus;
import dev.yuliang.zymbot.core.FakeClock;
import dev.yuliang.zymbot.core.FakeWorld;
import dev.yuliang.zymbot.core.config.ZymbotConfig;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Knocked out (civfabric dbno): wait only if someone can revive; otherwise give up at once. */
class DownedTest {
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

    String log(Bot b) {
        return String.join("\n", b.decisions().latest(50).stream().map(Object::toString).toList());
    }

    @Test
    void aloneWithNoMedic_givesUpAtOnce_andStopsWalking() {
        FakeWorld w = new FakeWorld("Bot1");
        Bot b = running(w, new ZymbotConfig());
        b.goTo(100, 64, 0);
        ticks(b, w, 2);
        w.downed = 58;
        ticks(b, w, 3);
        assertFalse(w.paths.busy);
        assertTrue(w.said.contains("/giveup"), w.said + "\n" + log(b));
        assertTrue(log(b).contains("giving up — because knocked out, no medic bot near, no human within 64 blocks"), log(b));
        assertEquals(1, w.said.stream().filter("/giveup"::equals).count(), "once");
    }

    @Test
    void aHumanNear_getsAMessage_andAFewSecondsToRevive() {
        FakeWorld w = new FakeWorld("Bot1");
        w.player("Bot2", 20, 0);
        Bot b = running(w, new ZymbotConfig());
        w.downed = 59;
        ticks(b, w, 40);                                       // 2 s: told them, still waiting
        assertTrue(w.said.stream().anyMatch(s -> s.startsWith("/msg Bot2 I'm knocked out at 0 0")), w.said.toString());
        assertFalse(w.said.contains("/giveup"));
        assertTrue(b.status().get(1).contains("waiting") && b.status().get(1).contains("for a human"), b.status().get(1));
        ticks(b, w, 45 * 20);
        assertTrue(w.said.contains("/giveup"), "nobody came: " + log(b));
        assertTrue(log(b).contains("nobody came to revive me in 45s"), log(b));
    }

    @Test
    void beingRevived_neverGivesUp() {
        FakeWorld w = new FakeWorld("Bot1");
        w.player("Bot2", 3, 0);
        Bot b = running(w, new ZymbotConfig());
        w.downed = 50;
        w.beingRevived = true;
        ticks(b, w, 40 * 20);
        assertFalse(w.said.contains("/giveup"));
        w.downed = -1;                                         // back on our feet
        w.beingRevived = false;
        ticks(b, w, 2);
        assertFalse(b.status().get(1).contains("knocked out"), b.status().get(1));
    }

    @Test
    void teammatesHearAboutIt() {
        FakeBus net = new FakeBus();
        ZymbotConfig a = new ZymbotConfig(), c = new ZymbotConfig();
        a.teamKey = c.teamKey = "shared-team-key-123";
        FakeWorld w1 = new FakeWorld("Bot1"), w3 = new FakeWorld("Bot3");
        FakeWorld.sameServer(w1, w3);
        Bot b1 = running(w1, a), b3 = running(w3, c);
        b1.attachTransport(net.endpoint());
        b3.attachTransport(net.endpoint());
        w1.downed = 57;
        ticks(b1, w1, 2);
        ticks(b3, w3, 2);
        assertTrue(log(b3).contains("Bot1 is knocked out — because at 0, 0, 57s to bleed out"), log(b3));
    }
}
