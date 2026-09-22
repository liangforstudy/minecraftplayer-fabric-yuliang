package dev.yuliang.zymbot.core.body;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Chat the bot has heard, newest last. Phase 1 only keeps it; the human syntax (!lf, !where)
 * reads from here in later phases.
 */
public final class ChatIn {
    public static final int KEEP = 50;

    /** {@code sender} is null for server/system messages; {@code whisper}: a /msg to us. */
    public record Line(String sender, String text, boolean whisper, long at) {}

    private final Deque<Line> lines = new ArrayDeque<>();

    public void heard(Line line) {
        if (lines.size() >= KEEP) lines.pollFirst();
        lines.addLast(line);
    }

    public List<Line> recent() { return List.copyOf(lines); }
}
