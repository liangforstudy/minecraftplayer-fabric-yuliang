package io.github.liangforstudy.zymbot.core.body;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * How fast the server is really ticking, from the world-time updates it sends about once a second:
 * game ticks gained ÷ real seconds passed, over the last {@link #WINDOW_NANOS}. 20 is healthy.
 * A server that stops sending (frozen) counts as slowing down, not as still at its last rate.
 * <p>
 * Some servers' game time doesn't move like a clock: on 9b9t (26.2, joined from 1.21.11) it stays put
 * for several updates and then jumps (+0, +0, +0, +255; seen 2026-10-26). Then each update counts as
 * the 20 ticks a server sends it after, as Meteor's TickRate does.
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

    /** The last few gaps between updates, oldest first, as "+ticks/seconds" (probe tps). */
    public synchronized java.util.List<String> recentGaps(int n) {
        java.util.List<String> out = new java.util.ArrayList<>();
        Sample prev = null;
        for (Sample s : samples) {
            if (prev != null) out.add(String.format(java.util.Locale.ROOT, "+%d/%.1fs", s.gameTime - prev.gameTime, (s.nanos - prev.nanos) / 1e9));
            prev = s;
        }
        return out.subList(Math.max(0, out.size() - n), out.size());
    }

    /** Seconds since the last update, or -1 before the first. */
    public synchronized double sinceLast(long now) {
        Sample last = samples.peekLast();
        return last == null ? -1 : (now - last.nanos) / 1e9;
    }

    /** Ticks per second, 0–20. */
    public synchronized double tps(long now) {
        if (samples.size() < 2) return 20;
        Sample first = samples.peekFirst(), last = samples.peekLast();
        long end = now - last.nanos > STALE_NANOS ? now : last.nanos;
        long span = end - first.nanos;
        if (span < MIN_SPAN_NANOS) return 20;
        double ticks = countingUpdates() ? TICKS_PER_UPDATE * (samples.size() - 1) : last.gameTime - first.gameTime;
        double tps = ticks * 1e9 / span;
        return Math.max(0, Math.min(20, tps));
    }

    /** A server sends its time every this many ticks. */
    static final int TICKS_PER_UPDATE = 20;

    /**
     * True when the game time in the window doesn't move like a clock (an update that gained nothing,
     * or far more than real time allows), so updates are counted instead.
     */
    public synchronized boolean countingUpdates() {
        Sample prev = null;
        for (Sample s : samples) {
            if (prev != null) {
                long gained = s.gameTime - prev.gameTime;
                double seconds = (s.nanos - prev.nanos) / 1e9;
                // faster than 20 TPS (with room for jitter) isn't a clock: +867 over a 31.8 s stall on 9b9t
                if (gained <= 0 || gained > 25 * seconds + TICKS_PER_UPDATE) return true;
            }
            prev = s;
        }
        return false;
    }
}
