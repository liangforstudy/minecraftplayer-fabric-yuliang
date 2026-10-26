package io.github.liangforstudy.zymbot.core.body;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class TpsMeterTest {
    static final long S = 1_000_000_000L;

    @Test
    void aHealthyServer_is20_aSlowOne_isWhatItGains() {
        TpsMeter m = new TpsMeter();
        assertEquals(20, m.tps(0), "no data yet: assume healthy");
        for (int i = 0; i <= 5; i++) m.onServerTime(1000 + 20L * i, i * S);
        assertEquals(20, m.tps(5 * S), 0.01);
        TpsMeter slow = new TpsMeter();
        for (int i = 0; i <= 5; i++) slow.onServerTime(1000 + 10L * i, i * S);   // 20 ticks take 2 s
        assertEquals(10, slow.tps(5 * S), 0.01);
    }

    @Test
    void aServerThatStopsSending_isStalled_notStillHealthy() {
        TpsMeter m = new TpsMeter();
        for (int i = 0; i <= 3; i++) m.onServerTime(20L * i, i * S);
        assertEquals(20, m.tps(3 * S), 0.01);
        assertTrue(m.tps(9 * S) < 8, "6 s of silence: " + m.tps(9 * S));
    }

    @Test
    void onlyTheLast10Seconds_count_andTimeGoingBack_startsOver() {
        TpsMeter m = new TpsMeter();
        long t = 0, gt = 0;
        for (int i = 0; i < 20; i++) m.onServerTime(gt += 5, t += S);             // 5 TPS for 20 s
        for (int i = 0; i < 12; i++) m.onServerTime(gt += 20, t += S);            // then healthy for 12
        assertEquals(20, m.tps(t), 0.01);
        m.onServerTime(10, t += S);                                               // a new world
        assertEquals(20, m.tps(t), "one sample: unknown, so 20");
    }

    @Test
    void aGameTimeThatStaysPutThenJumps_countsUpdatesInstead() {
        // 9b9t, 2026-10-26: +0/2.3s +0/2.4s +0/2.0s +255/2.8s; updates every ~2.5 s = 8 TPS
        TpsMeter m = new TpsMeter();
        long t = 0;
        m.onServerTime(1000, t);
        m.onServerTime(1000, t += 2_300_000_000L);
        m.onServerTime(1000, t += 2_400_000_000L);
        m.onServerTime(1000, t += 2_000_000_000L);
        m.onServerTime(1255, t += 2_800_000_000L);
        assertTrue(m.countingUpdates());
        assertEquals(20.0 * 4 / 9.5, m.tps(t), 0.01);
        TpsMeter clock = new TpsMeter();
        for (int i = 0; i <= 5; i++) clock.onServerTime(20L * i, i * S);
        assertFalse(clock.countingUpdates(), "a clock-like game time is read as it is");
        // 9b9t, later: no update for 31.8 s, then +867 (27 ticks a second, faster than any clock)
        TpsMeter stall = new TpsMeter();
        stall.onServerTime(5000, 0);
        stall.onServerTime(5867, 31_800_000_000L);
        assertTrue(stall.countingUpdates());
        assertTrue(stall.tps(43_500_000_000L) < 1, "one update in 43.5 s: stalled, not 20");
    }
}
