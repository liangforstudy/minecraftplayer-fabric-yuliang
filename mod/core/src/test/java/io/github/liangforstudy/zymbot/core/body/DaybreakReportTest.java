package io.github.liangforstudy.zymbot.core.body;

import static io.github.liangforstudy.zymbot.core.body.Daylight.Model.TIMELINE;
import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

public class DaybreakReportTest {
    static final long S = 1_000_000_000L;
    static final LocalTime MIDNIGHT = LocalTime.of(0, 0, 0);

    @Test
    void night_givesTheClockTime_atTheMeasuredPace() {
        // 2822 ticks to go at 10 ticks/s = 282 s = 00:04:42
        List<String> l = DaybreakReport.lines(new DaybreakReport.Sky(20000, 0, 0, false, TIMELINE),
                new DaybreakReport.Pace(OptionalDouble.of(10), 300, 20), Optional.empty(), 0, MIDNIGHT);
        assertTrue(l.get(1).contains("stop spawning in the open at 00:04:42 (in 4 min 42 s)"), l.toString());
        assertTrue(l.get(2).contains("10.0 ticks/s"), l.toString());
    }

    @Test
    void day_saysWhenTheyStart() {
        List<String> l = DaybreakReport.lines(new DaybreakReport.Sky(6000, 0, 0, false, TIMELINE),
                new DaybreakReport.Pace(OptionalDouble.empty(), 0, 20), Optional.empty(), 0, MIDNIGHT);
        assertTrue(l.get(1).startsWith("day: hostile mobs start spawning in the open at 00:05:58"), l.toString());  // 7179 / 20
        assertTrue(l.get(2).contains("TPS meter"), l.toString());
    }

    @Test
    void storm_seenStarting_givesARange_thenTheClearSchedule() {
        StormWatch w = new StormWatch();
        w.tick(false, 1000);
        w.tick(true, 2000);                                  // seen starting
        var est = w.estimate(3000);                          // 1000 ticks in
        assertEquals(2600, est.get().earliest());
        assertEquals(14600, est.get().latest());
        List<String> l = DaybreakReport.lines(new DaybreakReport.Sky(20000, 1, 1, true, TIMELINE),
                new DaybreakReport.Pace(OptionalDouble.of(20), 300, 20), est, 0, MIDNIGHT);
        assertTrue(l.get(1).contains("at any hour"), l.toString());
        assertTrue(l.get(2).contains("between 00:02:10") && l.get(2).contains("and 00:12:10"), l.toString());
        assertTrue(l.get(3).contains("once it clears: night"), l.toString());
    }

    @Test
    void storm_onWhenWeJoined_givesOnlyTheLatest_andACalmAfter() {
        StormWatch w = new StormWatch();
        w.tick(true, 5000);                                  // first tick in the world: already thundering
        var est = w.estimate(5000).get();
        assertFalse(est.startSeen());
        assertEquals(0, est.earliest());
        assertEquals(15600, est.latest());
        w.tick(false, 9000);                                 // it ends
        assertEquals(8000, w.calmFor(13000));
        assertTrue(w.estimate(13000).isEmpty());
    }

    @Test
    void clockRate_ridesOverStallsAndJumps_butStartsOverOnBigOnes() {
        ClockRate r = new ClockRate();
        long t = 0;
        for (int i = 0; i <= 60; i++) r.onClock(i % 4 == 3 ? t += 40 : t, i * S);    // stalls, then +40: 10/s
        assertEquals(10, r.perSecond().getAsDouble(), 0.7);
        r.onClock(t + 6000, 61 * S);                          // everyone slept: a jump
        assertTrue(r.perSecond().isEmpty(), "starts over");
        assertTrue(new ClockRate().perSecond().isEmpty(), "no history yet");
    }
}
