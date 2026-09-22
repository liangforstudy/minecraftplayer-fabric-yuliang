package dev.yuliang.zymbot.core.body;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * How fast the server is really ticking, from the world-time updates it sends about once a second:
 * game ticks gained ÷ real seconds passed, over the last {@link #WINDOW_NANOS}. 20 is healthy.
 * A server that stops sending (frozen) counts as slowing down, not as still at its last rate.
 */
public final class TpsMeter {
    static final long WINDOW_NANOS = 10_000_000_000L;
    /** Need this much history before trusting a number; until then, assume 20. */
    static final long MIN_SPAN_NANOS = 2_000_000_000L;
    /** No update for this long: the server is stalled — measure up to now. */
    static final long STALE_NANOS = 3_000_000_000L;

    private record Sample(long gameTime, long nanos) {}

    private final Deque<Sample> samples = new ArrayDeque<>();

    /** Call on each world-time update from the server. */
    public synchronized void onServerTime(long gameTime, long nanos) {
        Sample last = samples.peekLast();
        if (last != null && gameTime < last.gameTime) samples.clear();   // new world, or /time jumped back
        samples.addLast(new Sample(gameTime, nanos));
        while (samples.size() > 2 && nanos - samples.peekFirst().nanos > WINDOW_NANOS) samples.removeFirst();
    }

    public synchronized void reset() { samples.clear(); }

    /** Ticks per second, 0–20. */
    public synchronized double tps(long now) {
        if (samples.size() < 2) return 20;
        Sample first = samples.peekFirst(), last = samples.peekLast();
        long end = now - last.nanos > STALE_NANOS ? now : last.nanos;
        long span = end - first.nanos;
        if (span < MIN_SPAN_NANOS) return 20;
        double tps = (last.gameTime - first.gameTime) * 1e9 / span;
        return Math.max(0, Math.min(20, tps));
    }
}
