package dev.yuliang.zymbot.core.team;

import dev.yuliang.zymbot.core.api.EntityView;
import dev.yuliang.zymbot.core.body.ChatOut;
import dev.yuliang.zymbot.core.protocol.Signer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bot or human, told apart in game chat (roadmap3.md §4, owner 2026-10-15). A bot whispers each
 * player it meets in sight a fixed-format ask; a Zymbot bot whispers back an answer signed with the
 * team key. No answer in {@link #ANSWER_WITHIN_MILLIS}, asked twice, means a human. A good signature
 * is a bot on our team, a bad one a Zymbot bot from another team.
 * <p>
 * Only Bot accounts ask and answer: the owner's own client stays silent, so bots take them for the
 * human they are. Verdicts last the session.
 */
public final class Handshake {
    public enum Kind {
        TEAM_BOT("a bot on our team"), OTHER_BOT("a Zymbot bot from another team"), HUMAN("a human");

        public final String label;
        Kind(String label) { this.label = label; }
    }

    public record Verdict(Kind kind, long at, String why) {}

    /** How long an asked player has to answer. */
    public static final long ANSWER_WITHIN_MILLIS = 10_000;
    /** No answer the first time: ask once more this much later (it may have been loading or lagging). */
    public static final long RETRY_AFTER_MILLIS = 60_000;
    /** A human is asked again after this long: a bot that missed both asks gets another chance. */
    public static final long HUMAN_RECHECK_MILLIS = 30 * 60_000;
    /** At most one new ask this often, on top of ChatOut's own spacing. */
    public static final long ASK_GAP_MILLIS = 3_000;
    /** One answer per asker this often: an ask can't make us flood chat. */
    public static final long ANSWER_GAP_MILLIS = 5_000;

    static final String ASK = "zym?";
    static final String ANSWER = "zym!";
    private static final String ASK_NOTE = "(Zymbot bot check, no need to reply)";
    private static final Pattern ASK_RE = Pattern.compile("zym\\? ([a-z0-9]{6})\\b");
    private static final Pattern ANSWER_RE = Pattern.compile("zym! ([a-z0-9]{6}) ([A-Za-z0-9_-]{16})\\b");
    private static final String NONCE_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";

    private record Ask(String nonce, long at, int tries) {}

    private final ChatOut chatOut;
    private final LongSupplier clock;
    private final BiConsumer<String, String> log;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Ask> asked = new LinkedHashMap<>();          // by lower-case name
    private final Map<String, Verdict> verdicts = new LinkedHashMap<>();
    private final Map<String, Long> answeredAt = new LinkedHashMap<>();
    private final Map<String, String> names = new LinkedHashMap<>();      // lower-case → as seen
    private Signer signer;
    private long nextAsk;

    public Handshake(String teamKey, ChatOut chatOut, LongSupplier clock, BiConsumer<String, String> log) {
        this.signer = new Signer(teamKey);
        this.chatOut = chatOut;
        this.clock = clock;
        this.log = log;
    }

    /** The team key changed (pasted in the settings screen): sign with the new one. */
    public void teamKey(String teamKey) { this.signer = new Signer(teamKey); }

    /** A new world: everybody is a stranger again. */
    public void reset() {
        asked.clear();
        verdicts.clear();
        answeredAt.clear();
        names.clear();
        nextAsk = 0;
    }

    /** Whether this chat line is a handshake ask or answer: a bot's client keeps it off its chat screen. */
    public static boolean isHandshake(String text) {
        return text != null && (ASK_RE.matcher(text).find() || ANSWER_RE.matcher(text).find());
    }

    /**
     * Each tick, for a Bot that is playing: ask players newly in sight, and give up on asks that
     * timed out (retry once, then "human").
     */
    public void tick(String selfName, List<EntityView> nearby) {
        long now = clock.getAsLong();
        expire(now);
        for (EntityView e : nearby) {
            if (e.kind() != EntityView.Kind.PLAYER || e.name() == null || e.name().equalsIgnoreCase(selfName)) continue;
            String key = key(e.name());
            names.putIfAbsent(key, e.name());
            if (asked.containsKey(key)) continue;
            Verdict v = verdicts.get(key);
            if (v != null && !(v.kind() == Kind.HUMAN && now - v.at() >= HUMAN_RECHECK_MILLIS)) continue;
            if (now < nextAsk) return;
            ask(e.name(), 1, now, v == null ? "first seen in sight" : "seen again, last checked " + (now - v.at()) / 60_000 + " min ago");
        }
    }

    private void ask(String name, int tries, long now, String why) {
        String nonce = nonce();
        asked.put(key(name), new Ask(nonce, now, tries));
        nextAsk = now + ASK_GAP_MILLIS;
        chatOut.whisper(name, ASK + " " + nonce + " " + ASK_NOTE);
        log.accept("asked " + name + " in chat: bot or human?", why + (tries > 1 ? " (try " + tries + ")" : ""));
    }

    /** A first ask still counts if answered late, until the retry replaces it; a second unanswered one is a human. */
    private void expire(long now) {
        List<String> retry = new ArrayList<>(), human = new ArrayList<>();
        for (var en : asked.entrySet()) {
            Ask a = en.getValue();
            if (a.tries() >= 2 && now - a.at() >= ANSWER_WITHIN_MILLIS) human.add(en.getKey());
            else if (a.tries() < 2 && now - a.at() >= RETRY_AFTER_MILLIS) retry.add(en.getKey());
        }
        for (String k : human) {
            asked.remove(k);
            decide(k, Kind.HUMAN, now, "no answer to the chat check, asked twice");
        }
        for (String k : retry) {
            if (now < nextAsk) break;                                    // the next tick, then
            ask(names.getOrDefault(k, k), 2, now, "no answer the first time");
        }
    }

    /**
     * A whisper to us. Answers an ask (Bot accounts only) and judges an answer. Returns true if the
     * line was a handshake, so a bot's client can keep it off the chat screen.
     */
    public boolean onWhisper(String sender, String selfName, String text, boolean answerAsks) {
        if (sender == null || text == null) return false;
        Matcher ans = ANSWER_RE.matcher(text);
        if (ans.find()) {
            judge(sender, selfName, ans.group(1), ans.group(2));
            return true;
        }
        Matcher ask = ASK_RE.matcher(text);
        if (ask.find()) {
            if (answerAsks) answer(sender, selfName, ask.group(1));
            return true;
        }
        return false;
    }

    private void answer(String asker, String selfName, String nonce) {
        long now = clock.getAsLong();
        Long last = answeredAt.get(key(asker));
        if (last != null && now - last < ANSWER_GAP_MILLIS) return;
        answeredAt.put(key(asker), now);
        chatOut.whisper(asker, ANSWER + " " + nonce + " " + tag(nonce, asker, selfName));
    }

    private void judge(String answerer, String selfName, String nonce, String tag) {
        String key = key(answerer);
        Ask a = asked.get(key);
        if (a == null || !a.nonce().equals(nonce)) return;               // not our ask, or an old one
        asked.remove(key);
        long now = clock.getAsLong();
        String in = " in " + String.format(Locale.ROOT, "%.1f", (now - a.at()) / 1000.0) + " s";
        if (signer.verify(body(nonce, selfName, answerer), tag)) decide(key, Kind.TEAM_BOT, now, "answered the chat check, signed with our team key" + in);
        else decide(key, Kind.OTHER_BOT, now, "answered the chat check, but not with our team key" + in);
    }

    private void decide(String key, Kind kind, long now, String why) {
        Verdict before = verdicts.put(key, new Verdict(kind, now, why));
        if (before == null || before.kind() != kind) log.accept(names.getOrDefault(key, key) + " is " + kind.label, why);
    }

    String tag(String nonce, String asker, String answerer) { return signer.sign(body(nonce, asker, answerer)); }

    private static String body(String nonce, String asker, String answerer) {
        return "zym|" + nonce + "|" + key(asker) + "|" + key(answerer);
    }

    private String nonce() {
        StringBuilder b = new StringBuilder(6);
        for (int i = 0; i < 6; i++) b.append(NONCE_CHARS.charAt(random.nextInt(NONCE_CHARS.length())));
        return b.toString();
    }

    private static String key(String name) { return name.toLowerCase(Locale.ROOT); }

    public Optional<Verdict> verdict(String name) { return Optional.ofNullable(verdicts.get(key(name))); }

    /** For /zbot status: each player met and what they turned out to be, or that we're waiting. */
    public List<String> lines() {
        List<String> out = new ArrayList<>();
        for (var en : verdicts.entrySet()) out.add(names.getOrDefault(en.getKey(), en.getKey()) + ": " + en.getValue().kind().label);
        for (String k : asked.keySet()) if (!verdicts.containsKey(k)) out.add(names.getOrDefault(k, k) + ": asked, waiting");
        return out;
    }
}
