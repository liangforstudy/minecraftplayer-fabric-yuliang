package io.github.liangforstudy.zymbot.core.body;

import io.github.liangforstudy.zymbot.core.api.WorldView;

/**
 * Anarchy, overworld routes at night: wait for day somewhere safe, then go on (owner, 2026-10-29,
 * doc/9b9t/roadmap.md). Unsafe from the stop time (a little before dark) until the resume time (first
 * light, 22822, by default), and whenever mobs can spawn in the open anyway: a thunderstorm, or rain heavy
 * enough to keep it dark. Only in the overworld (the Nether and the End have no day), and never in
 * creative or spectator.
 */
public final class Night {
    private Night() {}

    /** Only the overworld has a day to wait for. */
    public static boolean applies(WorldView world) {
        return "minecraft:overworld".equals(world.dimension()) && !world.untouchable();
    }

    public static boolean monstersMaySpawn(WorldView world) {
        return Daylight.monstersMaySpawn(world.timeOfDay(), world.rain(), world.thunder(), world.thundering(),
                world.timelineDaylight() ? Daylight.Model.TIMELINE : Daylight.Model.COSINE);
    }

    /** Between the stop time and the resume time (day times, 0–24000). */
    static boolean nightTime(long dayTime, int stopAt, int resumeAt) {
        long t = Math.floorMod(dayTime, Daylight.DAY);
        return t >= stopAt && t < resumeAt;
    }

    /** Should a route stop (or stay stopped) for the dark now? */
    public static boolean unsafe(WorldView world, int stopAt, int resumeAt) {
        if (!applies(world)) return false;
        return nightTime(world.timeOfDay(), stopAt, resumeAt) || monstersMaySpawn(world);
    }

    /** Why, for the decision log and the log-out reason. */
    public static String why(WorldView world, int stopAt, int resumeAt) {
        long t = Math.floorMod(world.timeOfDay(), Daylight.DAY);
        if (world.thundering()) return "a thunderstorm (mobs spawn at any time of day)";
        if (nightTime(t, stopAt, resumeAt)) return (monstersMaySpawn(world) ? "night" : "dusk") + " (day time " + t + ")";
        return "rain keeps it dark enough for mobs (day time " + t + ")";
    }
}
