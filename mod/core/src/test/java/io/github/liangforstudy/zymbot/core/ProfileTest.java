package io.github.liangforstudy.zymbot.core;

import static org.junit.jupiter.api.Assertions.*;

import io.github.liangforstudy.zymbot.core.brain.Phase;
import io.github.liangforstudy.zymbot.core.config.ZymbotConfig;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** doc/9b9t/roadmap.md §0, Profiles: the anarchy build never creates anything on the off-list. */
class ProfileTest {
    @TempDir Path dir;
    final FakeClock clock = new FakeClock();

    Bot bot(FakeWorld w, ZymbotConfig cfg, Profile profile) {
        return new Bot(cfg, dir.resolve("zymbot.json"), dir.resolve(w.name), w.id, false, clock, profile);
    }

    @Test
    void anarchyCreatesNothingOnTheOffList() {
        Bot b = bot(new FakeWorld("Bluetails"), new ZymbotConfig(), Profile.ANARCHY);
        Set<String> made = new HashSet<>(b.modules());
        made.retainAll(Profile.ZYMCIV_ONLY);
        assertEquals(Set.of(), made);
        assertEquals(List.of("log-out", "drowning", "incoming", "hazard", "critical-health", "fight-or-flee", "stranded", "wading"), b.reflexes(),
                "no knocked-out, leash or hunger reflex");
    }

    @Test
    void zymcivWithTheTeamParked_hasNoBusChatCheckOrRegroup() {
        Profile.parkTeam(true);
        try {
            Bot b = bot(new FakeWorld("Bot1"), new ZymbotConfig(), Profile.ZYMCIV);
            for (String t : Profile.TEAM) assertFalse(b.modules().contains(t), "parked: " + t);
            var rest = new java.util.HashSet<>(Profile.ZYMCIV_ONLY);
            rest.removeAll(Profile.TEAM);
            assertTrue(b.modules().containsAll(rest), "the rest of ZymCiv stays: " + b.modules());
            assertTrue(b.regroupNow().contains("parked"), b.regroupNow());
            assertTrue(b.roster().get(0).contains("parked"), b.roster().toString());
        } finally {
            Profile.parkTeam(false);
        }
    }

    @Test
    void zymcivStillHasEveryModule() {
        Bot b = bot(new FakeWorld("Bot1"), new ZymbotConfig(), Profile.ZYMCIV);
        assertTrue(b.modules().containsAll(Profile.ZYMCIV_ONLY), b.modules().toString());
        assertEquals(List.of("downed", "drowning", "hazard", "critical-health", "stranded", "leash", "hunger", "wading"),
                b.reflexes(), "the same reflexes, in the same order, as before profiles");
        assertSame(Profile.ZYMCIV, new Bot(new ZymbotConfig(), dir.resolve("z.json"), dir.resolve("m"),
                new FakeWorld("Bot2").id, true, clock).profile(), "the old constructor is the ZymCiv bot");
    }

    @Test
    void anarchyStandsByOnJoin_evenWhereZymcivWouldTakeOver() {
        FakeWorld w = new FakeWorld("Bluetails");
        ZymbotConfig cfg = new ZymbotConfig();
        cfg.autostartServers.add("singleplayer");
        cfg.setRole(w.id, "Bluetails", ZymbotConfig.Role.BOT);
        Bot b = bot(w, cfg, Profile.ANARCHY);
        b.onJoin("singleplayer", "singleplayer:New World", "Bluetails");
        assertEquals(Phase.STOPPED, b.phase(), "active means standby: never takes control on join");
        assertEquals("doing: standing by — ready for orders", b.status().get(1));

        Bot unlisted = bot(new FakeWorld("Someone"), new ZymbotConfig(), Profile.ANARCHY);
        unlisted.onJoin("9b9t.com", "Someone");
        assertEquals(ZymbotConfig.Role.BOT, unlisted.role(), "the playing account, with nothing listed");
    }

    @Test
    void anarchyOneShotJobsBorrowTheControls_andGiveThemBack() {
        FakeWorld w = new FakeWorld("Bluetails");
        Bot b = bot(w, new ZymbotConfig(), Profile.ANARCHY);
        b.onJoin("9b9t.com", "Bluetails");
        String reply = b.goTo(100, null, 100);
        assertTrue(reply.startsWith("walking to 100 100"), reply);
        assertTrue(b.isBorrowing());
        assertFalse(b.isRunning(), "borrowed for the one job, not started");
        b.humanInput(w);
        assertFalse(b.isBorrowing(), "a movement key takes them back");
        assertTrue(b.follow("Steve").contains("start first"), "standing jobs still need /zbot start");
    }

    @Test
    void anarchyDoesntEatByItself() {
        FakeWorld w = new FakeWorld("Bluetails").give(0, "minecraft:bread", 5, FakeWorld.food(5));
        w.hunger = 4;
        Bot b = bot(w, new ZymbotConfig(), Profile.ANARCHY);
        b.onJoin("9b9t.com", "Bluetails");
        b.start("test");
        for (int i = 0; i < 40; i++) {
            clock.advance(50);
            b.tick(w, w);
        }
        assertFalse(w.useHeld, "Meteor's AutoEat feeds the player; two eaters fight over the hotbar");
        assertTrue(b.decisions().latest(20).stream().noneMatch(d -> d.toString().contains("eating")),
                b.decisions().latest(20).toString());
    }

    @Test
    void anarchyRunsAtCriticalHealth_notAtTheFirstHit() {
        ZymbotConfig cfg = new ZymbotConfig();
        assertEquals("modpack", cfg.danger);
        Bot b = bot(new FakeWorld("Bluetails"), cfg, Profile.ANARCHY);
        assertEquals("hard", cfg.danger, "modpack is ZymCiv's mobs");
        assertTrue(b.setDanger("modpack").startsWith("danger must be one of easy, normal, hard"));
        assertTrue(b.setDanger("normal").startsWith("danger: normal"));

        ZymbotConfig mine = new ZymbotConfig();
        mine.danger = "easy";
        bot(new FakeWorld("Other"), mine, Profile.ANARCHY);
        assertEquals("easy", mine.danger, "a vanilla mode the owner chose is kept");
    }

    @Test
    void anarchyOffersOnlyItsOwnTunables() {
        ZymbotConfig cfg = new ZymbotConfig();
        Bot b = bot(new FakeWorld("Bluetails"), cfg, Profile.ANARCHY);
        assertEquals(List.of("critical", "plantime", "lagtps"), b.tunables());
        assertTrue(b.set("leash", 30).contains("ZymCiv feature"), b.set("leash", 30));
        assertEquals(21, cfg.leashBlocks);
        assertEquals("critical = 10", b.set("critical", 10));
        assertEquals(9, bot(new FakeWorld("Bot1"), new ZymbotConfig(), Profile.ZYMCIV).tunables().size());
    }

    @Test
    void anarchyRefusesTheTeamAndCivfabricCommands() {
        FakeWorld w = new FakeWorld("Bluetails");
        ZymbotConfig cfg = FakeBus.busOnConfig();
        cfg.autoOpenLanWorlds.add("New World");
        Bot b = bot(w, cfg, Profile.ANARCHY);
        b.onJoin("singleplayer", "singleplayer:New World", "Bluetails");
        for (String reply : List.of(b.regroupNow(), b.grave(), b.setOwnRole(ZymbotConfig.Role.TEAMMATE),
                b.serverAdd("9b9t.com"), b.setAutoSummon(true), b.roster().get(0), b.watch("Bot1", true),
                b.see("Bot1").get(0))) {
            assertTrue(reply.contains("ZymCiv feature"), reply);
        }
        assertTrue(cfg.accounts.isEmpty());
        assertTrue(cfg.autostartServers.isEmpty());
        assertFalse(b.autoOpensLan("New World"), "never opens a world to LAN by itself");
    }

    @Test
    void anarchySendsNothingOnTheBus_evenWithItSwitchedOn() {
        FakeWorld w = new FakeWorld("Bluetails");
        FakeBus net = new FakeBus();
        Bot b = bot(w, FakeBus.busOnConfig(), Profile.ANARCHY);
        b.attachTransport(net.endpoint());
        b.onJoin("9b9t.com", "Bluetails");
        b.start("test");
        for (int i = 0; i < 5; i++) {
            clock.advance(Bot.HELLO_EVERY_MILLIS);
            b.tick(w, w);
        }
        assertEquals(List.of(), net.wire, "no HELLO, no roster: public chat and team messages are off on 9b9t");
    }
}
