package dev.yuliang.zymbot.core.brain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Every decision, with its reason (FOUNDATION.md decision 9). Kept in memory for the status
 * command and written to the game log, so a headless bot's log reads as a story.
 */
public final class DecisionLog {
    public record Entry(long atMillis, String what, String why) {
        @Override public String toString() { return what + " — because " + why; }
    }

    private static final Logger LOG = LoggerFactory.getLogger("zymbot");
    private final Deque<Entry> recent = new ArrayDeque<>();
    private final int capacity;
    private final LongSupplier clock;

    public DecisionLog(int capacity, LongSupplier clock) {
        this.capacity = capacity;
        this.clock = clock;
    }

    public void record(String what, String why) {
        Entry e = new Entry(clock.getAsLong(), what, why);
        if (recent.size() == capacity) recent.removeFirst();
        recent.addLast(e);
        LOG.info("[decision] {}", e);
    }

    /** Newest last. */
    public List<Entry> latest(int n) {
        List<Entry> all = new ArrayList<>(recent);
        return all.subList(Math.max(0, all.size() - n), all.size());
    }
}
