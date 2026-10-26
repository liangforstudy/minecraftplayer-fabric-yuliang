package io.github.liangforstudy.zymbot.core.body;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How long the server takes to answer a block action: each break, place or use carries a sequence
 * number, and the server acks "everything up to n" once it has dealt with them. The time from sending
 * to that ack is the action ping a place or break has to wait out (roadmap §Probes, `probe ack`).
 */
public final class AckMeter {
    /** Samples kept for the average and the worst. */
    static final int KEEP = 50;
    /** Unanswered actions kept; older ones are dropped (a server that never acks them). */
    static final int MAX_PENDING = 64;

    public record Summary(int count, double avgMs, double worstMs, double lastMs, int pending, double oldestPendingMs) {}

    private final Map<Integer, Long> pending = new LinkedHashMap<>();
    private final Deque<Double> samples = new ArrayDeque<>();
    private double last;

    /** A block action went out with this sequence number. */
    public synchronized void sent(int sequence, long nanos) {
        pending.put(sequence, nanos);
        while (pending.size() > MAX_PENDING) pending.remove(pending.keySet().iterator().next());
    }

    /** The server acked every action up to and including this sequence number. */
    public synchronized void acked(int upTo, long nanos) {
        for (Iterator<Map.Entry<Integer, Long>> it = pending.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Long> e = it.next();
            if (e.getKey() > upTo) continue;
            last = (nanos - e.getValue()) / 1e6;
            samples.addLast(last);
            while (samples.size() > KEEP) samples.removeFirst();
            it.remove();
        }
    }

    public synchronized void reset() {
        pending.clear();
        samples.clear();
        last = 0;
    }

    public synchronized Summary summary(long now) {
        double sum = 0, worst = 0;
        for (double s : samples) { sum += s; worst = Math.max(worst, s); }
        double oldest = 0;
        for (long t : pending.values()) oldest = Math.max(oldest, (now - t) / 1e6);
        return new Summary(samples.size(), samples.isEmpty() ? 0 : sum / samples.size(), worst, last,
                pending.size(), oldest);
    }
}
