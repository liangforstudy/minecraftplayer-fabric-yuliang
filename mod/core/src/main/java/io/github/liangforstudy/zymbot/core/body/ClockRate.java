package io.github.liangforstudy.zymbot.core.body;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.OptionalDouble;

/**
 * How fast the day clock really moves, in ticks per real second, over the last {@link #WINDOW_NANOS}:
 * what a countdown to daybreak needs on a lagging server. A long window rides over 9b9t's clock that
 * stalls and then jumps (roadmap §Probes); a big jump (/time set, everyone slept) starts over.
 */
public final class ClockRate {
    static final long WINDOW_NANOS = 5 * 60 * 1_000_000_000L;
    /** Less history than this and the number isn't trusted yet. */
    static final long MIN_SPAN_NANOS = 30 * 1_000_000_000L;
    static final long SAMPLE_NANOS = 1_000_000_000L;
    /** More than this beyond 20 ticks a second (or this far back) is a jump, not lag. */
    static final long JUMP_TICKS = 1200;

    private record Sample(long ticks, long nanos) {}

    private final Deque<Sample> samples = new ArrayDeque<>();

    /** Call often (every client tick is fine); keeps about one sample a second. */
    public synchronized void onClock(long ticks, long nanos) {
        Sample last = samples.peekLast();
        if (last != null) {
            if (nanos - last.nanos < SAMPLE_NANOS) return;
            long expected = (nanos - last.nanos) * 20 / 1_000_000_000L;
            long moved = ticks - last.ticks;
            if (moved < -JUMP_TICKS || moved > expected + JUMP_TICKS) samples.clear();
        }
        samples.addLast(new Sample(ticks, nanos));
        while (samples.size() > 2 && nanos - samples.peekFirst().nanos > WINDOW_NANOS) samples.removeFirst();
    }

    public synchronized void reset() { samples.clear(); }

    /** Ticks per real second over the window, once there's enough of it. */
    public synchronized OptionalDouble perSecond() {
        if (samples.size() < 2) return OptionalDouble.empty();
        Sample first = samples.peekFirst(), last = samples.peekLast();
        long span = last.nanos - first.nanos;
        if (span < MIN_SPAN_NANOS) return OptionalDouble.empty();
        return OptionalDouble.of(Math.max(0, (last.ticks - first.ticks) * 1e9 / span));
    }

    /** Seconds of history behind {@link #perSecond()}. */
    public synchronized double spanSeconds() {
        return samples.size() < 2 ? 0 : (samples.peekLast().nanos - samples.peekFirst().nanos) / 1e9;
    }
}
