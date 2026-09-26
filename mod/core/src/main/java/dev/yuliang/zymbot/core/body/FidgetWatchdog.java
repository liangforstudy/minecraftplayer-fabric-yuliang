package dev.yuliang.zymbot.core.body;

import dev.yuliang.zymbot.core.api.Vec3;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * A safety net for "dancing": in the 2026-09-26 tests Bot1 sat in the water for minutes, bobbing up
 * and down and turning on the spot, getting nowhere (a reflex re-targeting its own spot, Baritone
 * re-pathing in place, a task holding jump with the look changing). Fed one sample per tick; over
 * the last {@link #WINDOW_TICKS} it says "fidgeting" when the bot stayed within
 * {@link #MAX_DRIFT_BLOCKS} of where the window began, and either
 * <ul>
 *   <li>turned more than {@link #SPIN_DEGREES} in total (two full turns — a /zbot look or a
 *       path's corners are one turn, not repeated circling), or</li>
 *   <li>something is driving it ({@code driven}: a task runs) and its height reversed more than
 *       {@link #BOB_REVERSALS} times — bobbing while a task holds jump. Bobbing with nothing running
 *       is just water physics, not ours to stop.</li>
 * </ul>
 * Walking or swimming anywhere moves it past the drift limit; eating or standing still doesn't turn
 * or bob. The caller decides what to stop; after a report the window starts over.
 */
public final class FidgetWatchdog {
    /** The window looked at: 10 s. Long enough that a turn or a hop never fills it. */
    public static final int WINDOW_TICKS = 10 * 20;
    /** Farther than this from the window's start at any point: it's getting somewhere. */
    public static final double MAX_DRIFT_BLOCKS = 1.5;
    /** Total turning (sum of |Δyaw|) that counts as spinning: two full circles in 10 s. */
    public static final double SPIN_DEGREES = 720;
    /** Up/down direction changes that count as bobbing: a jump is 1–2, water bobbing ~20 in 10 s. */
    public static final int BOB_REVERSALS = 6;
    /** Height changes smaller than this per tick are noise, not a direction. */
    static final double Y_EPSILON = 0.005;
    /** How long the brain holds still after a report: 60 s, so the same reflex can't restart it at once. */
    public static final int HOLD_TICKS = 60 * 20;

    private record Sample(Vec3 pos, float yaw) {}

    private final Deque<Sample> window = new ArrayDeque<>();

    /** Forget the window (paused, holding still, a survival reflex running). */
    public void reset() {
        window.clear();
    }

    /**
     * One tick's sample. Returns the reason when the last {@link #WINDOW_TICKS} were fidgeting (and
     * starts the window over); empty otherwise.
     */
    public Optional<String> sample(Vec3 pos, float yaw, boolean driven) {
        window.addLast(new Sample(pos, yaw));
        if (window.size() > WINDOW_TICKS) window.removeFirst();
        if (window.size() < WINDOW_TICKS) return Optional.empty();

        Sample first = window.peekFirst();
        double drift = 0;
        double turned = 0;
        int reversals = 0;
        int lastSign = 0;
        Sample prev = null;
        for (Sample s : window) {
            drift = Math.max(drift, s.pos().horizontalDistance(first.pos()));
            if (prev != null) {
                turned += Math.abs(wrap(s.yaw() - prev.yaw()));
                double dy = s.pos().y() - prev.pos().y();
                int sign = Math.abs(dy) < Y_EPSILON ? 0 : (dy > 0 ? 1 : -1);
                if (sign != 0) {
                    if (lastSign != 0 && sign != lastSign) reversals++;
                    lastSign = sign;
                }
            }
            prev = s;
        }
        if (drift >= MAX_DRIFT_BLOCKS) return Optional.empty();
        boolean spinning = turned > SPIN_DEGREES;
        boolean bobbing = driven && reversals > BOB_REVERSALS;
        if (!spinning && !bobbing) return Optional.empty();
        window.clear();
        return Optional.of("bobbing/spinning in place for " + WINDOW_TICKS / 20 + " s (turned "
                + Math.round(turned) + "°, moved " + String.format(java.util.Locale.ROOT, "%.1f", drift)
                + " blocks, " + reversals + " ups and downs)");
    }

    /** Degrees, into -180..180. */
    static double wrap(double d) {
        d = d % 360;
        if (d > 180) d -= 360;
        if (d < -180) d += 360;
        return d;
    }
}
