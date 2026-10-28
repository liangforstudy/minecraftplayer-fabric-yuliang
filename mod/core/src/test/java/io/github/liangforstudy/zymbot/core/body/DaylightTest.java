package io.github.liangforstudy.zymbot.core.body;

import static io.github.liangforstudy.zymbot.core.body.Daylight.Model.COSINE;
import static io.github.liangforstudy.zymbot.core.body.Daylight.Model.TIMELINE;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class DaylightTest {
    @Test
    void timeline_clearWeather_spawnsFrom13179To22821() {
        assertEquals(15, Daylight.skyLight(6000, 0, 0, TIMELINE), "noon");
        assertEquals(4, Daylight.skyLight(18000, 0, 0, TIMELINE), "midnight");
        assertFalse(Daylight.monstersMaySpawn(13178, 0, 0, false, TIMELINE));
        assertTrue(Daylight.monstersMaySpawn(13179, 0, 0, false, TIMELINE));
        assertTrue(Daylight.monstersMaySpawn(22821, 0, 0, false, TIMELINE));
        assertFalse(Daylight.monstersMaySpawn(22822, 0, 0, false, TIMELINE));
        assertFalse(Daylight.monstersMaySpawn(24000 + 1000, 0, 0, false, TIMELINE), "next morning");
    }

    @Test
    void outlook_countsTicksToTheChange() {
        var night = Daylight.outlook(20000, 0, 0, false, TIMELINE);
        assertTrue(night.spawning());
        assertEquals(2822, night.ticksToChange(), "until 22822");
        var day = Daylight.outlook(6000, 0, 0, false, TIMELINE);
        assertFalse(day.spawning());
        assertEquals(7179, day.ticksToChange(), "until 13179");
        var thunder = Daylight.outlook(6000, 1, 1, true, TIMELINE);
        assertTrue(thunder.spawning(), "a thunderstorm: any time of day");
        assertEquals(-1, thunder.ticksToChange());
    }

    @Test
    void rain_makesTheNightLonger() {
        var clear = Daylight.outlook(20000, 0, 0, false, TIMELINE);
        var rain = Daylight.outlook(20000, 1, 0, false, TIMELINE);
        assertTrue(rain.ticksToChange() > clear.ticksToChange(), clear + " vs " + rain);
    }

    @Test
    void cosine_1_21_1_spawnsFrom13188To22812() {
        assertEquals(15, Daylight.skyLight(6000, 0, 0, COSINE));
        assertEquals(4, Daylight.skyLight(18000, 0, 0, COSINE));
        assertFalse(Daylight.monstersMaySpawn(13187, 0, 0, false, COSINE));
        assertTrue(Daylight.monstersMaySpawn(13188, 0, 0, false, COSINE));
        assertTrue(Daylight.monstersMaySpawn(22812, 0, 0, false, COSINE));
        assertFalse(Daylight.monstersMaySpawn(22813, 0, 0, false, COSINE));
    }
}
