package io.github.liangforstudy.zymbot.core.body;

/**
 * When the thunderstorm we're in will end, as far as a client can know. The server never says how long
 * weather lasts (not on joining either); it only shows rain and thunder rising and falling. But it picks
 * a storm's length from THUNDER_DURATION, 3600–15600 ticks, uniformly (ServerLevel, checked in 26.2 on
 * 2026-10-29), and after one the next waits at least THUNDER_DELAY's 12000. So a storm we saw start ends
 * within a known window of that start; one already on when we joined, within 15600 of joining.
 * Ticks here are day-clock ticks, so lag is already in them.
 */
public final class StormWatch {
    public static final int MIN_TICKS = 3600, MAX_TICKS = 15600, NEXT_AFTER_TICKS = 12000;

    private boolean watching;            // in a world, and ticked at least once
    private boolean thundering;
    private boolean startSeen;           // the storm began while we watched (else: it was on when we joined)
    private long since;                  // clock ticks: the storm's start, or our join if not seen
    private long endedAt = Long.MIN_VALUE;

    /** A new world or server: anything already going is "on when we joined". */
    public synchronized void reset() {
        watching = false;
        thundering = false;
        endedAt = Long.MIN_VALUE;
    }

    public synchronized void tick(boolean thunderingNow, long clockTicks) {
        if (!watching) {
            watching = true;
            thundering = thunderingNow;
            startSeen = false;
            since = clockTicks;
            return;
        }
        if (thunderingNow && !thundering) { startSeen = true; since = clockTicks; }
        if (!thunderingNow && thundering) endedAt = clockTicks;
        thundering = thunderingNow;
    }

    /** The storm's end, in clock ticks from now: earliest and latest. */
    public record Estimate(boolean startSeen, long earliest, long latest) {}

    /** Empty when there's no storm. */
    public synchronized java.util.Optional<Estimate> estimate(long clockTicks) {
        if (!thundering) return java.util.Optional.empty();
        long elapsed = Math.max(0, clockTicks - since);
        long latest = Math.max(0, MAX_TICKS - elapsed);
        long earliest = startSeen ? Math.max(0, MIN_TICKS - elapsed) : 0;
        return java.util.Optional.of(new Estimate(startSeen, earliest, latest));
    }

    /** Clock ticks until another thunderstorm can start, or 0 if we haven't seen one end. */
    public synchronized long calmFor(long clockTicks) {
        if (thundering || endedAt == Long.MIN_VALUE) return 0;
        return Math.max(0, NEXT_AFTER_TICKS - (clockTicks - endedAt));
    }
}
