package io.github.liangforstudy.zymbot.core.body;

/**
 * When hostile mobs can spawn on the surface because it's dark (/zbot daybreak). The game's own rule:
 * a monster may spawn where the light is at most a random 0–7 and there's no block light, so on open
 * ground that's a sky light of 7 or less. During a thunderstorm the check counts the sky as darkened by
 * 10, so they may spawn at any time of day.
 * <p>
 * The sky light comes from the time of day and the weather, worked out as each version does it:
 * <ul>
 * <li>{@link Model#TIMELINE} (1.21.11 and 26.x): the {@code minecraft:day} timeline's
 *     {@code gameplay/sky_light_level} track, 15 × a factor that is 1 by day and 4/15 by night with
 *     straight-line dusk and dawn; rain blends it toward 4 by 5/16, thunder by 0.527 (WeatherAttributes).</li>
 * <li>{@link Model#COSINE} (1.21.1): {@code Level.updateSkyBrightness}'s cosine of the sun's angle.</li>
 * </ul>
 * Checked against the 26.2, 1.21.11 and 1.21.1 jars on 2026-10-29: in clear weather spawning is possible
 * from day time 13179 to 22821 (TIMELINE; 13188 to 22812 with COSINE, as the wiki gives).
 */
public final class Daylight {
    private Daylight() {}

    public enum Model { TIMELINE, COSINE }

    public static final int DAY = 24000;
    /** The day timeline's sky light keyframes (ticks, factor of 15), repeating every day. */
    private static final int[] KEY_TICKS = {133, 11867, 13670, 22330};
    private static final float[] KEY_FACTOR = {1f, 1f, 4f / 15f, 4f / 15f};

    /** The sky light on open ground, 0–15, as the game rounds it. */
    public static int skyLight(long dayTime, float rain, float thunder, Model model) {
        int darken;
        if (model == Model.TIMELINE) {
            float level = 15f * factor(Math.floorMod(dayTime, DAY));
            level += (4f - level) * 0.3125f * rain;          // FloatModifier.ALPHA_BLEND toward 4
            level += (4f - level) * 0.52734375f * thunder;
            darken = (int) (15f - level);
        } else {
            double d = 1.0 - rain * 5f / 16.0;
            double e = 1.0 - thunder * 5f / 16.0;
            double f = 0.5 + 2.0 * Math.max(-0.25, Math.min(0.25, Math.cos(sunAngle(dayTime) * (Math.PI * 2))));
            darken = (int) ((1.0 - f * d * e) * 11.0);
        }
        return 15 - darken;
    }

    /** May hostile mobs spawn on open ground now? */
    public static boolean monstersMaySpawn(long dayTime, float rain, float thunder, boolean thundering, Model model) {
        return thundering || skyLight(dayTime, rain, thunder, model) <= 7;
    }

    /**
     * Whether they may spawn now, and how many ticks until that changes (with today's weather held as it
     * is); -1 when it doesn't change (a thunderstorm).
     */
    public record Outlook(boolean spawning, long ticksToChange) {}

    public static Outlook outlook(long dayTime, float rain, float thunder, boolean thundering, Model model) {
        boolean now = monstersMaySpawn(dayTime, rain, thunder, thundering, model);
        if (thundering) return new Outlook(true, -1);
        for (long t = 1; t <= DAY; t++)
            if (monstersMaySpawn(dayTime + t, rain, thunder, false, model) != now) return new Outlook(now, t);
        return new Outlook(now, -1);                          // e.g. heavy rain all day
    }

    /** The timeline factor at a time of day: straight lines between keyframes, wrapping round midnight. */
    private static float factor(int t) {
        int n = KEY_TICKS.length;
        for (int i = 0; i < n; i++) {
            int a = KEY_TICKS[i], b = i + 1 < n ? KEY_TICKS[i + 1] : KEY_TICKS[0] + DAY;
            int tt = t < KEY_TICKS[0] ? t + DAY : t;
            if (tt >= a && tt <= b) {
                float fb = KEY_FACTOR[(i + 1) % n];
                return KEY_FACTOR[i] + (fb - KEY_FACTOR[i]) * (tt - a) / (float) (b - a);
            }
        }
        return 1f;
    }

    /** DimensionType.timeOfDay: the sun's angle through the day, 0–1 (0.25 at noon's start). */
    private static double sunAngle(long dayTime) {
        double d = frac(dayTime / 24000.0 - 0.25);
        double e = 0.5 - Math.cos(d * Math.PI) / 2.0;
        return (float) (d * 2.0 + e) / 3.0f;
    }

    private static double frac(double v) { return v - Math.floor(v); }
}
