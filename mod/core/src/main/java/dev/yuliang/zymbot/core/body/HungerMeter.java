package dev.yuliang.zymbot.core.body;

import dev.yuliang.zymbot.core.api.Vec3;
import dev.yuliang.zymbot.core.api.WorldView;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * R18 — measure, don't guess. Splits time into activities and charges each drop in food
 * (hunger + saturation, both synced to the client) to the activity at the time. Rough — drains
 * land in chunks — but over minutes it checks the SURVIVAL_EARLY_GAME §4a table.
 */
public final class HungerMeter {
    public enum Activity { IDLE, WALKING, SPRINTING, SWIMMING, EATING }

    /** Horizontal blocks per tick above which the bot counts as moving. */
    static final double MOVING = 0.02;
    /** After a join or respawn the server re-sends food and saturation — don't count that as drain. */
    static final int SETTLE_TICKS = 100;

    private final Map<Activity, long[]> ticks = new EnumMap<>(Activity.class);     // [ticks]
    private final Map<Activity, double[]> spent = new EnumMap<>(Activity.class);   // [food]
    private Vec3 lastPos;
    private double lastFood = -1;
    private int settle = SETTLE_TICKS;

    /** Joined or respawned: start a fresh baseline. Totals so far are kept. */
    public void settle() {
        settle = SETTLE_TICKS;
        lastFood = -1;
        lastPos = null;
    }

    public void sample(WorldView world) {
        double food = world.hunger() + world.saturation();
        if (settle > 0) {
            settle--;
            lastFood = food;
            lastPos = world.position();
            return;
        }
        Activity a = classify(world);
        ticks.computeIfAbsent(a, k -> new long[1])[0]++;
        if (lastFood >= 0 && food < lastFood) spent.computeIfAbsent(a, k -> new double[1])[0] += lastFood - food;
        lastFood = food;                   // a rise (eating) just resets the baseline
        lastPos = world.position();
    }

    Activity classify(WorldView world) {
        if (world.usingItem()) return Activity.EATING;
        if (world.inWater()) return Activity.SWIMMING;
        boolean moving = lastPos != null && world.position().horizontalDistance(lastPos) > MOVING;
        if (moving && world.sprinting()) return Activity.SPRINTING;
        return moving ? Activity.WALKING : Activity.IDLE;
    }

    public long ticks(Activity a) { return ticks.getOrDefault(a, new long[1])[0]; }

    /** "walking 12.0 min, 0.61 food/min" per activity seen so far. */
    public List<String> lines() {
        List<String> out = new ArrayList<>();
        for (Activity a : Activity.values()) {
            long t = ticks(a);
            if (t == 0) continue;
            double minutes = t / 1200.0;
            double food = spent.getOrDefault(a, new double[1])[0];
            out.add(String.format(Locale.ROOT, "%s %.1f min, %.2f food/min", a.name().toLowerCase(Locale.ROOT),
                    minutes, minutes > 0 ? food / minutes : 0));
        }
        return out;
    }
}
