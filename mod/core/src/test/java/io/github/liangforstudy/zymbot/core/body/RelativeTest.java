package io.github.liangforstudy.zymbot.core.body;

import static org.junit.jupiter.api.Assertions.*;

import io.github.liangforstudy.zymbot.core.api.Vec3;
import org.junit.jupiter.api.Test;

/** Where something is from where we face, for the log-out line. */
class RelativeTest {
    static final Vec3 ME = new Vec3(0, 64, 0);

    @Test
    void facingSouth_westIsRight() {
        assertEquals("south", Relative.facing(0));
        assertEquals("6 ahead", Relative.of(ME, 0, new Vec3(0, 64, 6)));
        assertEquals("3 right", Relative.of(ME, 0, new Vec3(-3, 64, 0)));
    }

    @Test
    void facingNorth_eastIsRight_andHeightToo() {
        assertEquals("north", Relative.facing(180));
        assertEquals("6 ahead, 3 right, 2 above", Relative.of(ME, 180, new Vec3(3, 66, -6)));
        assertEquals("4 behind, 2 left, 5 below", Relative.of(ME, -180, new Vec3(-2, 59, 4)));
    }

    @Test
    void facingEastAndWest_andUnboundedYaw() {
        assertEquals("east", Relative.facing(-90));
        assertEquals("east", Relative.facing(270 + 360));
        assertEquals("west", Relative.facing(90));
        assertEquals("5 ahead", Relative.of(ME, -90, new Vec3(5, 64, 0)));
        assertEquals("5 left", Relative.of(ME, 90, new Vec3(0, 64, 5)));
        assertEquals("right here", Relative.of(ME, 0, ME));
    }
}
