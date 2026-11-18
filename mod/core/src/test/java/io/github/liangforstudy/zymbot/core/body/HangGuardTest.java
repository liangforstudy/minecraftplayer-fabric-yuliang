package io.github.liangforstudy.zymbot.core.body;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.liangforstudy.zymbot.core.api.Damage;
import io.github.liangforstudy.zymbot.core.api.Vec3;
import io.github.liangforstudy.zymbot.core.config.ZymbotConfig;
import org.junit.jupiter.api.Test;

/** Issue #10: the hang guard and the ghost-mob guard. */
class HangGuardTest {
    @Test
    void firesOnce_afterTheTimeout_andOnlyWhileArmedAndOn() {
        HangWatch w = new HangWatch();
        assertFalse(w.due(100_000, 15_000, true), "never beat: not on a server");
        w.beat(0);
        assertFalse(w.due(14_999, 15_000, true));
        assertFalse(w.due(20_000, 15_000, false), "setting off");
        assertTrue(w.due(15_000, 15_000, true));
        assertFalse(w.due(16_000, 15_000, true), "once");
        w.beat(30_000);                                     // back on a server
        assertTrue(w.due(45_000, 15_000, true));
        w.beat(50_000);
        w.disarm();                                         // left the server
        assertFalse(w.due(90_000, 15_000, true));
    }

    @Test
    void defaults_onAnd15Seconds_adjustable() {
        ZymbotConfig c = new ZymbotConfig();
        assertTrue(c.hangGuard);
        assertTrue(c.ghostGuard);
        assertEquals(15, c.tunable("hangwait"));
        assertEquals(null, c.setTunable("hangwait", 30));
        assertEquals(30, c.hangGuardSeconds);
        assertTrue(c.setTunable("hangwait", 2) != null, "too short would trip on a world load");
    }

    @Test
    void twoHitsFromAnUnseenMob_within10s_logOut() {
        GhostHitWatch g = new GhostHitWatch();
        Damage ghost = new Damage("minecraft:mob_attack", null, null);
        Damage seen = new Damage("minecraft:mob_attack", "Hoglin", new Vec3(1, 64, 1));
        Damage fall = new Damage("minecraft:fall", null, null);
        assertFalse(g.onHit(seen, 0));
        assertFalse(g.onHit(fall, 100));
        assertFalse(g.onHit(ghost, 1_000), "one could be a desync blip");
        assertTrue(g.onHit(ghost, 5_000));
        assertFalse(g.onHit(ghost, 20_000));
        assertFalse(g.onHit(ghost, 31_000), "11 s apart: not together");
    }
}
