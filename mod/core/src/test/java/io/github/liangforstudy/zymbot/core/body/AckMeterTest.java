package io.github.liangforstudy.zymbot.core.body;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class AckMeterTest {
    static final long MS = 1_000_000L;

    @Test
    void oneAck_answersEverythingUpToIt() {
        AckMeter m = new AckMeter();
        m.sent(1, 0);
        m.sent(2, 10 * MS);
        m.sent(3, 20 * MS);
        m.acked(2, 110 * MS);                      // 1 took 110 ms, 2 took 100 ms; 3 still waiting
        AckMeter.Summary s = m.summary(150 * MS);
        assertEquals(2, s.count());
        assertEquals(105, s.avgMs(), 0.01);
        assertEquals(110, s.worstMs(), 0.01);
        assertEquals(1, s.pending());
        assertEquals(130, s.oldestPendingMs(), 0.01);
    }

    @Test
    void anAckForNothingPending_changesNothing() {
        AckMeter m = new AckMeter();
        m.acked(5, 100 * MS);
        assertEquals(0, m.summary(100 * MS).count());
    }

    @Test
    void keepsOnlyTheLatestSamples_andCapsPending() {
        AckMeter m = new AckMeter();
        for (int i = 0; i < AckMeter.KEEP + 10; i++) {
            m.sent(i, i * MS);
            m.acked(i, i * MS + (i < 10 ? 1000 : 50) * MS);   // the first ten were slow
        }
        AckMeter.Summary s = m.summary(0);
        assertEquals(AckMeter.KEEP, s.count());
        assertEquals(50, s.worstMs(), 0.01, "the slow ones fell out");
        for (int i = 0; i < AckMeter.MAX_PENDING + 5; i++) m.sent(1000 + i, 0);
        assertEquals(AckMeter.MAX_PENDING, m.summary(0).pending());
    }

    @Test
    void reset_clearsAll() {
        AckMeter m = new AckMeter();
        m.sent(1, 0);
        m.acked(1, 10 * MS);
        m.sent(2, 0);
        m.reset();
        AckMeter.Summary s = m.summary(0);
        assertEquals(0, s.count());
        assertEquals(0, s.pending());
    }
}
