package dev.yuliang.zymbot.core;

import static org.junit.jupiter.api.Assertions.*;

import dev.yuliang.zymbot.core.config.ZymbotConfig;
import dev.yuliang.zymbot.core.protocol.SummonTarget;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** /zbot summon: a human in a world pulls bots waiting at their title screens. */
class SummonTest {
    @TempDir Path dir;
    final FakeClock clock = new FakeClock();
    final FakeBus net = new FakeBus();

    Bot bot(FakeWorld w, ZymbotConfig cfg, boolean headless) {
        Bot b = new Bot(cfg, dir.resolve(w.name + ".json"), dir.resolve(w.name), w.id, headless, clock);
        b.attachTransport(net.endpoint());
        return b;
    }

    ZymbotConfig team() {
        ZymbotConfig c = new ZymbotConfig();
        c.teamKey = "shared-team-key-123";
        return c;
    }

    @Test
    void aWaitingBotIsSummonedToTheHostsLanWorld() {
        FakeWorld human = new FakeWorld("Bot2"), bot1 = new FakeWorld("Bot1");
        Bot host = bot(human, team(), false);
        host.onJoin("singleplayer", "singleplayer:New World", "Bot2");
        host.tick(human, human);
        Bot waiting = bot(bot1, team(), true);                 // at the title screen: never joined

        String said = host.summon(SummonTarget.lan(25565, SummonTarget.localIps()));
        assertTrue(said.contains("LAN port 25565"), said);
        waiting.tickOutsideWorld();
        assertEquals(Optional.of("127.0.0.1:25565"), waiting.takeSummon(), "same machine → loopback");
        assertEquals(Optional.empty(), waiting.takeSummon(), "only once");
    }

    @Test
    void humansAndBotsAlreadyPlayingAreNeverPulled() {
        FakeWorld human = new FakeWorld("Bot2"), mate = new FakeWorld("Mate"), busy = new FakeWorld("Bot3");
        Bot host = bot(human, team(), false);
        host.onJoin("singleplayer", "singleplayer:w", "Bot2");
        ZymbotConfig mateCfg = team();
        mateCfg.setRole(mate.id, "Mate", ZymbotConfig.Role.TEAMMATE);
        Bot teammate = bot(mate, mateCfg, false);
        Bot playing = bot(busy, team(), true);
        playing.onJoin("play.example.net", "Bot3");

        host.summon(SummonTarget.lan(25565, List.of()));
        teammate.tickOutsideWorld();
        playing.tick(busy, busy);
        assertEquals(Optional.empty(), teammate.takeSummon(), "a Teammate's client is a human's — never pulled");
        assertEquals(Optional.empty(), playing.takeSummon());
        assertTrue(playing.decisions().latest(5).stream().anyMatch(d -> d.toString().contains("already in a world")));
    }

    @Test
    void anotherTeamsSummonIsIgnored() {
        FakeWorld stranger = new FakeWorld("Evil"), bot1 = new FakeWorld("Bot1");
        ZymbotConfig other = new ZymbotConfig();
        other.teamKey = "some-other-teams-key";
        Bot evil = bot(stranger, other, false);
        evil.onJoin("singleplayer", "singleplayer:x", "Evil");
        Bot waiting = bot(bot1, team(), true);
        evil.summon(SummonTarget.server("evil.example.net"));
        waiting.tickOutsideWorld();
        assertEquals(Optional.empty(), waiting.takeSummon());
    }

    @Test
    void summonToAServer_andTargetsAreValidated() {
        assertEquals("server:play.example.net:25570", SummonTarget.server("play.example.net:25570").encode());
        assertEquals(Optional.empty(), SummonTarget.decode("server:bad host; rm -rf"));
        assertEquals(Optional.empty(), SummonTarget.decode("lan:99999:1.2.3.4"));
        assertEquals(Optional.empty(), SummonTarget.decode("lan:25565:not-an-ip"));
        SummonTarget t = SummonTarget.decode("lan:25565:10.0.0.7,192.168.1.20").orElseThrow();
        assertEquals("192.168.1.20:25565", t.addressFrom(Set.of("192.168.1.33")), "picks the host address on our subnet");
        assertEquals("127.0.0.1:25565", t.addressFrom(Set.of("10.0.0.7")), "same machine");
        assertEquals("10.0.0.7:25565", t.addressFrom(Set.of("172.16.0.2")), "no shared subnet: first address");
    }

    @Test
    void autoSummonRepeats_soBotsStillBootingCatchIt() {
        FakeWorld human = new FakeWorld("Bot2"), bot1 = new FakeWorld("Bot1");
        Bot host = bot(human, team(), false);
        host.onJoin("singleplayer", "singleplayer:w", "Bot2");
        host.setAutoSummon(true);
        host.lanOpened(25565, List.of());
        Bot late = bot(bot1, team(), true);                    // boots after the first summon
        late.tickOutsideWorld();
        assertEquals(Optional.empty(), late.takeSummon(), "missed the first one");
        clock.now += Bot.SUMMON_REPEAT_EVERY_MILLIS;
        host.tick(human, human);
        late.tickOutsideWorld();
        assertEquals(Optional.of("127.0.0.1:25565"), late.takeSummon(), "caught the repeat");

        clock.now += Bot.SUMMON_REPEAT_FOR_MILLIS;             // after five minutes it stops
        host.tick(human, human);
        clock.now += Bot.SUMMON_REPEAT_EVERY_MILLIS;
        int before = net.wire.size();
        host.tick(human, human);
        assertEquals(before, net.wire.size());
    }

    @Test
    void switchingAutoSummonOff_stopsTheRepeats_andAPlayingBotSaysSoOnce() {
        FakeWorld human = new FakeWorld("Bot2"), bot1 = new FakeWorld("Bot1");
        Bot host = bot(human, team(), false);
        host.onJoin("singleplayer", "singleplayer:w", "Bot2");
        Bot playing = bot(bot1, team(), true);
        playing.onJoin("127.0.0.1:25565", "Bot1");
        FakeWorld.sameServer(human, bot1);
        host.setAutoSummon(true);
        host.lanOpened(25565, List.of());
        for (int i = 0; i < 3; i++) {
            clock.now += Bot.SUMMON_REPEAT_EVERY_MILLIS;
            host.tick(human, human);
            playing.tick(bot1, bot1);
        }
        assertEquals(1, playing.decisions().latest(50).stream().filter(d -> d.toString().contains("ignored a summon")).count());

        host.setAutoSummon(false);
        clock.now += Bot.SUMMON_REPEAT_EVERY_MILLIS;
        host.tick(human, human);
        int before = net.wire.size();
        clock.now += Bot.SUMMON_REPEAT_EVERY_MILLIS;
        host.tick(human, human);
        assertEquals(before, net.wire.size(), "no more summons once switched off");
    }

    @Test
    void autoOpenLanIsPerWorld() {
        FakeWorld human = new FakeWorld("Bot2");
        Bot host = bot(human, team(), false);
        assertFalse(host.autoOpensLan("New World"));
        host.setAutoOpenLan("New World", true);
        assertTrue(host.autoOpensLan("New World"));
        assertFalse(host.autoOpensLan("959582503"));
        host.setAutoOpenLan("New World", false);
        assertFalse(host.autoOpensLan("New World"));
    }

    @Test
    void autoSummonOnlyWhenTurnedOn() {
        FakeWorld human = new FakeWorld("Bot2"), bot1 = new FakeWorld("Bot1");
        Bot host = bot(human, team(), false);
        host.onJoin("singleplayer", "singleplayer:w", "Bot2");
        Bot waiting = bot(bot1, team(), true);
        host.lanOpened(25565, List.of());
        waiting.tickOutsideWorld();
        assertEquals(Optional.empty(), waiting.takeSummon(), "off by default");
        host.setAutoSummon(true);
        host.lanOpened(25565, List.of());
        waiting.tickOutsideWorld();
        assertEquals(Optional.of("127.0.0.1:25565"), waiting.takeSummon());
    }

    @Test
    void aRepeatDoesNotRestartAJoinInProgress() {
        FakeWorld human = new FakeWorld("Bot2"), bot1 = new FakeWorld("Bot1");
        Bot host = bot(human, team(), false);
        host.onJoin("singleplayer", "singleplayer:w", "Bot2");
        host.setAutoSummon(true);
        host.lanOpened(25565, List.of());
        Bot waiting = bot(bot1, team(), true);
        clock.now += Bot.SUMMON_REPEAT_EVERY_MILLIS;
        host.tick(human, human);
        waiting.tickOutsideWorld();
        assertEquals(Optional.of("127.0.0.1:25565"), waiting.takeSummon());
        clock.now += Bot.SUMMON_REPEAT_EVERY_MILLIS;           // 15 s later, still joining
        host.tick(human, human);
        waiting.tickOutsideWorld();
        assertEquals(Optional.empty(), waiting.takeSummon(), "a repeat mid-join would cancel the join");
        clock.now += Bot.SUMMON_JOIN_GRACE_MILLIS;             // the join failed after all: try again
        host.tick(human, human);
        waiting.tickOutsideWorld();
        assertEquals(Optional.of("127.0.0.1:25565"), waiting.takeSummon());
    }

    @Test
    void aFailedSummonedJoinIsRetriedOnce() {
        FakeWorld human = new FakeWorld("Bot2"), bot1 = new FakeWorld("Bot1");
        Bot host = bot(human, team(), false);
        host.onJoin("singleplayer", "singleplayer:w", "Bot2");
        Bot waiting = bot(bot1, team(), true);
        assertEquals(Optional.empty(), waiting.retryFailedSummon(), "never summoned: nothing to retry");
        host.summon(SummonTarget.lan(25565, SummonTarget.localIps()));
        waiting.tickOutsideWorld();
        assertEquals(Optional.of("127.0.0.1:25565"), waiting.takeSummon());
        assertEquals(Optional.of("127.0.0.1:25565"), waiting.retryFailedSummon(), "the join timed out: once more");
        assertEquals(Optional.empty(), waiting.retryFailedSummon(), "only once");
    }
}