package dev.yuliang.zymbot.core.team;

import static org.junit.jupiter.api.Assertions.*;

import dev.yuliang.zymbot.core.Bot;
import dev.yuliang.zymbot.core.FakeClock;
import dev.yuliang.zymbot.core.FakeWorld;
import dev.yuliang.zymbot.core.body.ChatOut;
import dev.yuliang.zymbot.core.config.ZymbotConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The chat check (roadmap3.md §4): an answer signed with our key is a team bot, silence a human. */
class HandshakeTest {
    final FakeClock clock = new FakeClock();
    final List<String> log = new ArrayList<>();
    @TempDir Path dir;

    /** One side of the check: a Handshake, the chat it sends, and a world to see players in. */
    final class Side {
        final String name;
        final ChatOut out = new ChatOut(clock);
        final Handshake hs;
        final FakeWorld world;
        Side(String name, String key) {
            this.name = name;
            this.world = new FakeWorld(name);
            this.hs = new Handshake(key, out, clock, (what, why) -> log.add(what + " — " + why));
        }
        /** Everything queued, as "/msg <to> <text>" lines. */
        List<String> sent() {
            int before = world.said.size();
            for (int i = 0; i < ChatOut.MAX_QUEUED && out.queued() > 0; i++) {
                clock.advance(ChatOut.MIN_GAP_MILLIS);
                out.flush(world);
            }
            return new ArrayList<>(world.said.subList(before, world.said.size()));
        }
    }

    static String text(String msgLine) { return msgLine.substring(msgLine.indexOf(' ', 5) + 1); }

    /** Bot1 sees `other` and asks it; returns the ask's text. */
    String ask(Side bot1, Side other) {
        bot1.world.player(other.name, 10, 0);
        bot1.hs.tick(bot1.name, bot1.world.nearby());
        List<String> sent = bot1.sent();
        assertEquals(1, sent.size(), sent.toString());
        assertTrue(sent.get(0).startsWith("/msg " + other.name + " zym? "), sent.get(0));
        return text(sent.get(0));
    }

    @Test
    void aBotOnOurTeam_answersWithOurKey() {
        Side bot1 = new Side("Bot1", "team-key"), bot3 = new Side("Bot3", "team-key");
        String ask = ask(bot1, bot3);
        assertTrue(ask.endsWith("(Zymbot bot check, no need to reply)"), "a human sees what it is");
        assertTrue(bot3.hs.onWhisper("Bot1", "Bot3", ask, true));
        List<String> answer = bot3.sent();
        assertEquals(1, answer.size());
        assertTrue(answer.get(0).startsWith("/msg Bot1 zym! "), answer.get(0));
        assertTrue(bot1.hs.onWhisper("Bot3", "Bot1", text(answer.get(0)), true));
        assertEquals(Handshake.Kind.TEAM_BOT, bot1.hs.verdict("Bot3").orElseThrow().kind());
        assertTrue(log.stream().anyMatch(l -> l.startsWith("Bot3 is a bot on our team")), log.toString());
        assertEquals(List.of("Bot3: a bot on our team"), bot1.hs.lines());
    }

    @Test
    void aBotWithAnotherKey_isAnotherTeamsBot() {
        Side bot1 = new Side("Bot1", "team-key"), stranger = new Side("Zed", "their-key");
        String ask = ask(bot1, stranger);
        stranger.hs.onWhisper("Bot1", "Zed", ask, true);
        bot1.hs.onWhisper("Zed", "Bot1", text(stranger.sent().get(0)), true);
        assertEquals(Handshake.Kind.OTHER_BOT, bot1.hs.verdict("Zed").orElseThrow().kind());
    }

    @Test
    void noAnswer_isAskedOnceMore_thenAHuman() {
        Side bot1 = new Side("Bot1", "team-key"), human = new Side("Bluetails", "team-key");
        ask(bot1, human);
        clock.advance(Handshake.ANSWER_WITHIN_MILLIS);
        bot1.hs.tick("Bot1", bot1.world.nearby());
        assertTrue(bot1.hs.verdict("Bluetails").isEmpty(), "not judged on one miss: it may have been loading");
        assertTrue(bot1.sent().isEmpty(), "and not asked again yet");
        clock.advance(Handshake.RETRY_AFTER_MILLIS);
        bot1.hs.tick("Bot1", bot1.world.nearby());
        List<String> again = bot1.sent();
        assertEquals(1, again.size(), "asked a second time");
        clock.advance(Handshake.ANSWER_WITHIN_MILLIS);
        bot1.hs.tick("Bot1", bot1.world.nearby());
        assertEquals(Handshake.Kind.HUMAN, bot1.hs.verdict("Bluetails").orElseThrow().kind());
        assertTrue(bot1.sent().isEmpty(), "a human isn't pestered");
    }

    @Test
    void aLateFirstAnswer_stillCounts() {
        Side bot1 = new Side("Bot1", "team-key"), bot3 = new Side("Bot3", "team-key");
        String ask = ask(bot1, bot3);
        clock.advance(Handshake.ANSWER_WITHIN_MILLIS + 5_000);                // loading, but before the retry
        bot1.hs.tick("Bot1", bot1.world.nearby());
        bot3.hs.onWhisper("Bot1", "Bot3", ask, true);
        bot1.hs.onWhisper("Bot3", "Bot1", text(bot3.sent().get(0)), true);
        assertEquals(Handshake.Kind.TEAM_BOT, bot1.hs.verdict("Bot3").orElseThrow().kind());
    }

    @Test
    void aCopiedAnswer_isNeverOurTeam() {
        Side bot1 = new Side("Bot1", "team-key");
        String ask = ask(bot1, new Side("Mallory", "team-key"));
        String nonce = ask.split(" ")[1];
        bot1.hs.onWhisper("Mallory", "Bot1", "zym! " + nonce + " AAAAAAAAAAAAAAAA", true);
        assertNotEquals(Handshake.Kind.TEAM_BOT, bot1.hs.verdict("Mallory").orElseThrow().kind());
    }

    @Test
    void aSilentClient_answersNothing() {
        Side owner = new Side("Bluetails", "team-key");
        assertTrue(owner.hs.onWhisper("Bot1", "Bluetails", "zym? abc123 (Zymbot bot check, no need to reply)", false));
        assertTrue(owner.sent().isEmpty());
    }

    @Test
    void ordinaryWhispersAreLeftAlone() {
        Side bot1 = new Side("Bot1", "team-key");
        assertFalse(bot1.hs.onWhisper("Bluetails", "Bot1", "hey, zym? what's up", true));
        assertFalse(Handshake.isHandshake("zym? hi there"));
        assertTrue(Handshake.isHandshake("Bot1 whispers to you: zym? 4f2a9z (Zymbot bot check, no need to reply)"));
    }

    @Test
    void theOwnersClientStaysSilent_andABotAnswers() {             // owner, 2026-10-15
        FakeWorld me = new FakeWorld("Bluetails"), w3 = new FakeWorld("Bot3");
        ZymbotConfig mine = new ZymbotConfig();
        mine.setRole(me.id, "Bluetails", ZymbotConfig.Role.TEAMMATE);
        Bot owner = new Bot(mine, dir.resolve("o.json"), dir.resolve("o"), me.id, false, clock);
        owner.onJoin("127.0.0.1:25565", "Bluetails");
        Bot bot3 = new Bot(new ZymbotConfig(), dir.resolve("b.json"), dir.resolve("b"), w3.id, true, clock);
        bot3.onJoin("127.0.0.1:25565", "Bot3");
        String ask = "zym? abc123 (Zymbot bot check, no need to reply)";
        assertFalse(bot3.onChat("Bot1", ask, true), "stopped (not on the server list): silent, a human has it");
        bot3.start("test");
        assertFalse(owner.onChat("Bot1", ask, true), "the owner sees it, as any human would");
        assertTrue(bot3.onChat("Bot1", ask, true), "a bot keeps it off its screen");
        for (int i = 0; i < 3; i++) { clock.advance(ChatOut.MIN_GAP_MILLIS); owner.tick(me, me); bot3.tick(w3, w3); }
        assertTrue(me.said.stream().noneMatch(s -> s.contains("zym!")), me.said.toString());
        assertTrue(w3.said.stream().anyMatch(s -> s.startsWith("/msg Bot1 zym! abc123 ")), w3.said.toString());
    }
}
