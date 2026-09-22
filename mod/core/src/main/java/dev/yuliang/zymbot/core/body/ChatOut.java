package dev.yuliang.zymbot.core.body;

import dev.yuliang.zymbot.core.api.Hands;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

/**
 * Everything the bot says goes through here, at most one line per {@link #MIN_GAP_MILLIS} — well
 * under vanilla's spam kick, and polite on a shared server. Oldest lines drop past {@link #MAX_QUEUED}.
 */
public final class ChatOut {
    public static final long MIN_GAP_MILLIS = 1_500;
    public static final int MAX_QUEUED = 10;

    private record Line(String to, String text) {}

    private final Deque<Line> queue = new ArrayDeque<>();
    private final LongSupplier clock;
    private long lastSent = Long.MIN_VALUE / 2;
    private int dropped;

    public ChatOut(LongSupplier clock) {
        this.clock = clock;
    }

    public void say(String text) { add(new Line(null, text)); }

    /** A private message (/msg). */
    public void whisper(String player, String text) { add(new Line(player, text)); }

    private void add(Line line) {
        if (queue.size() >= MAX_QUEUED) {
            queue.pollFirst();
            dropped++;
        }
        queue.addLast(line);
    }

    public void flush(Hands hands) {
        long now = clock.getAsLong();
        if (queue.isEmpty() || now - lastSent < MIN_GAP_MILLIS) return;
        Line l = queue.pollFirst();
        lastSent = now;
        if (l.to() == null) hands.chat(l.text());
        else hands.command("msg " + l.to() + " " + l.text());
    }

    public int queued() { return queue.size(); }
    public int dropped() { return dropped; }
}
