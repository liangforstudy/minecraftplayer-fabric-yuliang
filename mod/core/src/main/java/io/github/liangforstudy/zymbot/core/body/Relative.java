package io.github.liangforstudy.zymbot.core.body;

import io.github.liangforstudy.zymbot.core.api.Vec3;

/**
 * Where something is from where we stand and face: "6 ahead, 3 left, 2 above". Absolute coordinates
 * say nothing to someone who logs back in and has to turn round at once (owner, 2026-10-29); the
 * facing is kept over a log-out, so ahead and left are still right then.
 */
public final class Relative {
    private Relative() {}

    /** Minecraft yaw: 0 faces south (+z), 90 west, 180 north, 270 east. */
    public static String facing(float yaw) {
        int i = Math.floorMod(Math.round(yaw / 90f), 4);
        return new String[]{"south", "west", "north", "east"}[i];
    }

    public static String of(Vec3 me, float yaw, Vec3 it) {
        double dx = it.x() - me.x(), dy = it.y() - me.y(), dz = it.z() - me.z();
        double r = Math.toRadians(yaw);
        double ahead = -dx * Math.sin(r) + dz * Math.cos(r);
        double right = -dx * Math.cos(r) - dz * Math.sin(r);
        StringBuilder s = new StringBuilder();
        part(s, ahead, "ahead", "behind");
        part(s, right, "right", "left");
        part(s, dy, "above", "below");
        return s.length() == 0 ? "right here" : s.toString();
    }

    private static void part(StringBuilder s, double v, String plus, String minus) {
        long n = Math.round(Math.abs(v));
        if (n == 0) return;
        if (s.length() > 0) s.append(", ");
        s.append(n).append(' ').append(v > 0 ? plus : minus);
    }
}
