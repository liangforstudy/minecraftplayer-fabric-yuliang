package dev.yuliang.zymbot.core;

import static org.junit.jupiter.api.Assertions.*;

import dev.yuliang.zymbot.core.brain.Phase;
import dev.yuliang.zymbot.core.config.ZymbotConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Phase 0's "done when" list, run against the fake world. */
class BotTest {
    @TempDir Path dir;
    final FakeClock clock = new FakeClock();

    Bot bot(FakeWorld w, ZymbotConfig cfg, boolean headless) {
        return new Bot(cfg, dir.resolve("zymbot.json"), dir.resolve(w.name), w.id, headless, clock);
    }

    @Test
    void autostartsOnlyOnWhitelistedServers() {
        ZymbotConfig cfg = new ZymbotConfig();
        cfg.autostartServers.add("127.0.0.1:25565");
        FakeWorld w = new FakeWorld("Bot1");

        Bot a = bot(w, cfg, true);
        a.onJoin("127.0.0.1", "Bot1");                      // default port implied
        assertEquals(Phase.DISCOVERY, a.phase());

        Bot b = bot(w, cfg, true);
        b.onJoin("play.random-server.net", "Bot1");
        assertEquals(Phase.STOPPED, b.phase(), "must not autostart on a server that isn't whitelisted");
    }

    @Test
    void singleplayerAutostartsWhenListed() {
        ZymbotConfig cfg = new ZymbotConfig();
        cfg.autostartServers.add("singleplayer");
        Bot b = bot(new FakeWorld("Bot1"), cfg, false);
        b.onJoin("singleplayer", "singleplayer:New World", "Bot1");
        assertTrue(b.isRunning());
    }

    @Test
    void idlesHonestlyWithNoObjectives() {
        FakeWorld w = new FakeWorld("Bot1");
        Bot b = bot(w, new ZymbotConfig(), true);
        b.onJoin("127.0.0.1:25565", "Bot1");
        b.start("test");
        b.tick(w, w);
        assertTrue(b.status().get(1).contains("idle — no objectives"), b.status().toString());
    }

    @Test
    void stopStops() {
        FakeWorld w = new FakeWorld("Bot1");
        Bot b = bot(w, new ZymbotConfig(), true);
        b.onJoin("x", "Bot1");
        b.start("test");
        b.stop("test");
        assertEquals(Phase.STOPPED, b.phase());
        assertEquals("doing: nothing — stopped", b.status().get(1));
    }

    @Test
    void respawnsWhenRunning_andAlwaysWhenHeadless_butNotForAnIdleHuman() {
        FakeWorld w = new FakeWorld("Bot1");
        w.dead = true;
        Bot headlessIdle = bot(w, new ZymbotConfig(), true);
        headlessIdle.onJoin("x", "Bot1");
        headlessIdle.tick(w, w);
        assertEquals(1, w.respawns, "headless has nobody to press Respawn");

        FakeWorld human = new FakeWorld("Human");
        human.dead = true;
        Bot idle = bot(human, new ZymbotConfig(), false);
        idle.onJoin("x", "Human");
        idle.tick(human, human);
        assertEquals(0, human.respawns, "a stopped bot must not take over a human's death screen");
    }

    @Test
    void respawnRetriesAreSpacedOut() {
        FakeWorld w = new FakeWorld("Bot1");
        Bot b = bot(w, new ZymbotConfig(), true);
        b.onJoin("x", "Bot1");
        b.start("test");
        w.dead = true;
        b.tick(w, w);
        w.dead = true;                                       // server hasn't respawned us yet
        b.tick(w, w);
        assertEquals(1, w.respawns);
        clock.advance(Bot.RESPAWN_RETRY_MILLIS);
        b.tick(w, w);
        assertEquals(2, w.respawns);
    }

    @Test
    void pausesForHumanInput_warnsThenResumes() {
        FakeWorld w = new FakeWorld("Me");
        ZymbotConfig cfg = new ZymbotConfig();
        cfg.humanPauseSeconds = 10;
        Bot b = bot(w, cfg, false);
        b.onJoin("x", "Me");
        b.start("test");

        b.humanInput(w);
        assertTrue(b.status().get(0).contains("paused"));
        assertTrue(w.notices.get(0).contains("paused"));

        clock.advance(7_500);
        b.tick(w, w);
        assertTrue(w.notices.get(w.notices.size() - 1).contains("resumes in 3s"));

        clock.advance(3_000);
        b.tick(w, w);
        assertFalse(b.status().get(0).contains("paused"));
    }

    @Test
    void headlessIgnoresHumanInput() {
        FakeWorld w = new FakeWorld("Bot1");
        Bot b = bot(w, new ZymbotConfig(), true);
        b.onJoin("x", "Bot1");
        b.start("test");
        b.humanInput(w);
        assertFalse(b.status().get(0).contains("paused"));
    }

    @Test
    void twoBotsHearEachOthersHello() {
        FakeBus net = new FakeBus();
        FakeWorld w1 = new FakeWorld("Bot1"), w3 = new FakeWorld("Bot3");
        w3.pos = new dev.yuliang.zymbot.core.api.Vec3(-338.5, 66, 244.5);
        Bot b1 = bot(w1, new ZymbotConfig(), true), b3 = bot(w3, new ZymbotConfig(), true);
        b1.attachTransport(net.endpoint());
        b3.attachTransport(net.endpoint());
        b1.onJoin("127.0.0.1:25565", "Bot1");
        b3.onJoin("127.0.0.1:25565", "Bot3");
        b1.start("test");
        b3.start("test");

        b1.tick(w1, w1);   // both say HELLO
        b3.tick(w3, w3);
        b1.tick(w1, w1);   // and hear each other
        b3.tick(w3, w3);

        assertEquals("Bot3", b1.memory().roster.get(w3.id.toString()).name);
        assertEquals(-339, b1.memory().roster.get(w3.id.toString()).x);
        assertEquals("Bot1", b3.memory().roster.get(w1.id.toString()).name);
        assertFalse(b1.memory().roster.containsKey(w1.id.toString()), "a bot doesn't list itself");
    }

    @Test
    void botsWithDifferentTeamKeysIgnoreEachOther() {
        FakeBus net = new FakeBus();
        ZymbotConfig k1 = new ZymbotConfig(), k2 = new ZymbotConfig();
        k1.teamKey = "ours";
        k2.teamKey = "theirs";
        FakeWorld w1 = new FakeWorld("Bot1"), w2 = new FakeWorld("Stranger");
        Bot b1 = bot(w1, k1, true), b2 = bot(w2, k2, true);
        b1.attachTransport(net.endpoint());
        b2.attachTransport(net.endpoint());
        b1.onJoin("s", "Bot1");
        b2.onJoin("s", "Stranger");
        b1.start("t");
        b2.start("t");
        b2.tick(w2, w2);
        b1.tick(w1, w1);
        assertTrue(b1.memory().roster.isEmpty());
        assertTrue(String.join("\n", b1.status()).contains("rejected"));
    }

    @Test
    void botsOnOtherServersAreIgnored() {
        FakeBus net = new FakeBus();
        FakeWorld w1 = new FakeWorld("Bot1"), w2 = new FakeWorld("Bot2");
        Bot b1 = bot(w1, new ZymbotConfig(), true), b2 = bot(w2, new ZymbotConfig(), true);
        b1.attachTransport(net.endpoint());
        b2.attachTransport(net.endpoint());
        b1.onJoin("server-a", "Bot1");
        b2.onJoin("server-b", "Bot2");
        b1.start("t");
        b2.start("t");
        b2.tick(w2, w2);
        b1.tick(w1, w1);
        assertTrue(b1.memory().roster.isEmpty());
    }

    @Test
    void rosterSurvivesARestart() {
        FakeBus net = new FakeBus();
        FakeWorld w1 = new FakeWorld("Bot1"), w3 = new FakeWorld("Bot3");
        Bot b1 = bot(w1, new ZymbotConfig(), true), b3 = bot(w3, new ZymbotConfig(), true);
        b1.attachTransport(net.endpoint());
        b3.attachTransport(net.endpoint());
        b1.onJoin("s", "Bot1");
        b3.onJoin("s", "Bot3");
        b3.start("t");
        b3.tick(w3, w3);
        b1.tick(w1, w1);
        b1.onLeave();

        Bot again = bot(w1, new ZymbotConfig(), true);
        again.onJoin("s", "Bot1");
        assertEquals("Bot3", again.memory().roster.get(w3.id.toString()).name);
    }

    @Test
    void autostartAddPersistsToConfig() throws Exception {
        ZymbotConfig cfg = new ZymbotConfig();
        FakeWorld w = new FakeWorld("Bot1");
        Bot b = bot(w, cfg, true);
        b.onJoin("Play.Example.NET", "Bot1");
        assertTrue(b.autostartAdd().startsWith("added play.example.net:25565"));
        assertTrue(Files.readString(dir.resolve("zymbot.json")).contains("play.example.net:25565"));
        assertEquals(List.of("play.example.net:25565"), b.autostartList());
        assertTrue(b.autostartRemove().startsWith("removed"));
    }

    @Test
    void pastingTheSameKeyLetsTwoBotsHearEachOther_withoutRestart() {
        FakeBus net = new FakeBus();
        ZymbotConfig k1 = new ZymbotConfig(), k2 = new ZymbotConfig();
        k1.teamKey = "bot-rig-key-123";
        k2.teamKey = "my-own-random-key";
        FakeWorld w1 = new FakeWorld("Bot1"), w2 = new FakeWorld("Me");
        Bot b1 = bot(w1, k1, true), b2 = bot(w2, k2, false);
        b1.attachTransport(net.endpoint());
        b2.attachTransport(net.endpoint());
        b1.onJoin("s", "Bot1");
        b2.onJoin("s", "Me");
        b1.start("t");
        b2.start("t");
        b1.tick(w1, w1);
        b2.tick(w2, w2);
        assertTrue(b2.memory().roster.isEmpty(), "different keys: deaf to each other");

        assertNotNull(b2.setTeamKey("short"), "a bad key is refused");
        assertNull(b2.setTeamKey("bot-rig-key-123"));
        clock.advance(Bot.HELLO_EVERY_MILLIS);
        b1.tick(w1, w1);
        b2.tick(w2, w2);
        assertEquals("Bot1", b2.memory().roster.get(w1.id.toString()).name);
        assertEquals(ZymbotConfig.fingerprint("bot-rig-key-123"), ZymbotConfig.fingerprint(k2.teamKey));
        assertFalse(String.join("\n", b2.status()).contains("bot-rig-key-123"), "status never shows the key itself");
    }
}
