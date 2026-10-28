package io.github.liangforstudy.zymbot.core.body;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * /zbot daybreak's lines: the clock time hostile mobs stop (or start) spawning in the open, counted at
 * the day clock's measured pace, and what a thunderstorm does to that. Plain text, so it's testable.
 */
public final class DaybreakReport {
    private DaybreakReport() {}

    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** What the game shows us now. */
    public record Sky(long dayTime, float rain, float thunder, boolean thundering, Daylight.Model model) {}

    /**
     * The clock's pace: measured ticks per second when there's enough history ({@code rate}, over
     * {@code rateSeconds}), else the TPS meter's reading.
     */
    public record Pace(OptionalDouble rate, double rateSeconds, double tps) {
        double perSecond() { return Math.max(0.1, rate.orElse(tps)); }

        String describe() {
            return rate.isPresent()
                    ? String.format(Locale.ROOT, "the day clock ran at %.1f ticks/s over the last %s (20 is normal)",
                            rate.getAsDouble(), span(rateSeconds))
                    : String.format(Locale.ROOT, "pace from the TPS meter (%.1f) until the clock has 30 s of history", tps);
        }
    }

    public static List<String> lines(Sky sky, Pace pace, Optional<StormWatch.Estimate> storm, long calmFor, LocalTime now) {
        List<String> out = new ArrayList<>();
        double ps = pace.perSecond();
        String weather = sky.thundering() ? ", thunderstorm" : sky.rain() > 0.05f ? ", raining (nights run longer)" : ", clear";
        out.add("time of day " + Math.floorMod(sky.dayTime(), Daylight.DAY) + " of 24000" + weather);

        if (sky.thundering()) {
            out.add("thunderstorm: hostile mobs may spawn in the open at any hour until it ends");
            storm.ifPresent(s -> out.add(s.startSeen()
                    ? "  it ends between " + at(now, s.earliest(), ps) + " and " + at(now, s.latest(), ps)
                            + " (storms last " + StormWatch.MIN_TICKS + "–" + StormWatch.MAX_TICKS + " ticks; we saw this one start)"
                    : "  it ends by " + at(now, s.latest(), ps) + " at the latest (it was on when we joined; storms last up to "
                            + StormWatch.MAX_TICKS + " ticks)"));
            // after the storm: the clock's own schedule, in clear weather
            var after = Daylight.outlook(sky.dayTime(), 0, 0, false, sky.model());
            out.add("  once it clears: " + schedule(after, now, ps));
        } else {
            var o = Daylight.outlook(sky.dayTime(), sky.rain(), sky.thunder(), false, sky.model());
            out.add(o.ticksToChange() < 0 ? "no change ahead with this weather" : schedule(o, now, ps));
            if (calmFor > 0) out.add("  no thunderstorm before " + at(now, calmFor, ps) + " (another waits at least "
                    + StormWatch.NEXT_AFTER_TICKS + " ticks after the last)");
        }
        out.add("  " + pace.describe());
        return out;
    }

    private static String schedule(Daylight.Outlook o, LocalTime now, double ps) {
        return (o.spawning() ? "night: hostile mobs stop spawning in the open at " : "day: hostile mobs start spawning in the open at ")
                + at(now, o.ticksToChange(), ps);
    }

    /** "01:23:40 (in 6 min 20 s)". */
    static String at(LocalTime now, long ticks, double perSecond) {
        double secs = ticks / perSecond;
        return now.plusNanos((long) (secs * 1e9)).format(HMS) + " (in " + span(secs) + ")";
    }

    static String span(double secs) {
        long s = Math.round(Math.max(0, secs));
        if (s < 60) return s + " s";
        if (s < 3600) return (s / 60) + " min " + (s % 60) + " s";
        return (s / 3600) + " h " + (s % 3600 / 60) + " min";
    }
}
