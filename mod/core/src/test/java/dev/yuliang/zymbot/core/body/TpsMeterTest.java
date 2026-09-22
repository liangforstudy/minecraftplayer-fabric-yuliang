package dev.yuliang.zymbot.core.body;

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
}
