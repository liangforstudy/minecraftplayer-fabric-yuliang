package io.github.liangforstudy.zymbot.fabric;

import io.github.liangforstudy.zymbot.core.body.AckMeter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.item.Items;

/**
 * /zbot probe … — numbers measured on the server we're on (9b9t roadmap, §Probes). Read-only and local:
 * nothing is sent. Meteor is read through reflection, so Zymbot runs the same without it.
 */
final class ZymbotProbes {
    private ZymbotProbes() {}

    static List<String> tps() {
        long now = System.nanoTime();
        List<String> out = new ArrayList<>();
        double since = ZymbotClient.TPS.sinceLast(now);
        out.add("TPS (Zymbot, " + (ZymbotClient.TPS.countingUpdates()
                        ? "counting time updates — this server's game time jumps" : "from the server's game time") + "): "
                + f1(ZymbotClient.TPS.tps(now))
                + (since < 0 ? " — no time update yet, assuming 20" : ", last update " + f1(since) + " s ago"));
        List<String> gaps = ZymbotClient.TPS.recentGaps(8);
        if (!gaps.isEmpty()) out.add("  game ticks gained / real time, per update: " + String.join(" ", gaps));
        Float meteor = meteorTickRate();
        if (meteor != null) out.add("TPS (Meteor's TickRate): " + f1(meteor));
        return out;
    }

    static List<String> ping() {
        Minecraft mc = Minecraft.getInstance();
        List<String> out = new ArrayList<>();
        PlayerInfo me = mc.getConnection() == null || mc.player == null ? null
                : mc.getConnection().getPlayerInfo(mc.player.getUUID());
        out.add(me == null ? "ping: not on a server" : "ping (as the server lists it in tab): " + me.getLatency() + " ms");
        out.add(ackLine(ZymbotClient.ACKS.summary(System.nanoTime())));
        return out;
    }

    static List<String> ack() {
        AckMeter.Summary s = ZymbotClient.ACKS.summary(System.nanoTime());
        List<String> out = new ArrayList<>();
        out.add(ackLine(s));
        if (s.count() > 0) out.add("  worst " + f0(s.worstMs()) + " ms, last " + f0(s.lastMs()) + " ms; PlaceTask waits 1000 ms");
        if (s.pending() > 0) out.add("  " + s.pending() + " still unanswered, the oldest for " + f0(s.oldestPendingMs()) + " ms");
        return out;
    }

    private static String ackLine(AckMeter.Summary s) {
        if (s.count() == 0) return "block ack: nothing measured yet — break or place a block";
        return "block ack (action → server's confirmation), last " + s.count() + ": average " + f0(s.avgMs()) + " ms";
    }

    /**
     * /zbot daybreak: the clock time hostile mobs stop spawning in the open for the night (by day: when
     * they start), at the day clock's measured pace, and what a thunderstorm does to it (core/body/DaybreakReport).
     */
    static List<String> daybreak() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return List.of("not in a world");
        var level = mc.level;
        if (level.dimensionType().hasFixedTime() || !level.dimensionType().hasSkyLight())
            return List.of("no day and night here (" + McCompat.id(level.dimension()) + "): mobs spawn by darkness alone");
        long now = McCompat.dayTime(level);
        var sky = new io.github.liangforstudy.zymbot.core.body.DaybreakReport.Sky(now, level.getRainLevel(1f),
                level.getThunderLevel(1f), level.isThundering(), McCompat.DAYLIGHT);
        var pace = new io.github.liangforstudy.zymbot.core.body.DaybreakReport.Pace(ZymbotClient.CLOCK.perSecond(),
                ZymbotClient.CLOCK.spanSeconds(), ZymbotClient.TPS.tps(System.nanoTime()));
        return io.github.liangforstudy.zymbot.core.body.DaybreakReport.lines(sky, pace, ZymbotClient.STORMS.estimate(now),
                ZymbotClient.STORMS.calmFor(now), java.time.LocalTime.now());
    }

    /** Meteor modules that matter to Zymbot, with a warning where they'd fight it. */
    static List<String> meteor() {
        Object modules = meteorModules();
        if (modules == null) return List.of("Meteor Client isn't loaded");
        List<String> out = new ArrayList<>();
        Object autoEat = module(modules, "auto-eat");
        if (autoEat != null) {
            boolean on = active(autoEat);
            Object blacklist = setting(autoEat, "blacklist");
            boolean fleshBanned = blacklist instanceof List<?> l && l.contains(Items.ROTTEN_FLESH);
            out.add("auto-eat: " + onOff(on) + (on ? ", pause-baritone " + onOff(Boolean.TRUE.equals(setting(autoEat, "pause-baritone"))) : "")
                    + (fleshBanned ? " — rotten flesh is on its blacklist (the ice miner eats it)" : ""));
        }
        line(out, modules, "auto-gap", false, "no reserve: it eats the last golden apple too");
        line(out, modules, "no-ghost-blocks", true, "a place the server rejects still looks done");
        line(out, modules, "auto-totem", false, null);
        for (String aim : List.of("kill-aura", "bow-aimbot", "crystal-aura", "anchor-aura", "bed-aura"))
            line(out, modules, aim, false, "turns the camera; fights Zymbot's aim");
        line(out, modules, "auto-walk", false, "holds W against Zymbot's walks");
        line(out, modules, "jesus", false, "doesn't work on 9b9t (owner, 2026-10-26)");
        line(out, modules, "timer", false, "speeds the client's ticks; Zymbot's timing assumes 20");
        line(out, modules, "highway-builder", false, "moves the player itself; leave Zymbot on standby");
        return out;
    }

    /** One module's line; the warning shows when it's on (or, with warnWhenOff, when it's off). */
    private static void line(List<String> out, Object modules, String name, boolean warnWhenOff, String warning) {
        Object m = module(modules, name);
        if (m == null) return;
        boolean on = active(m);
        out.add(name + ": " + onOff(on) + (warning != null && on != warnWhenOff ? " — " + warning : ""));
    }

    private static Object meteorModules() {
        try {
            return Class.forName("meteordevelopment.meteorclient.systems.modules.Modules").getMethod("get").invoke(null);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    private static Object module(Object modules, String name) {
        try {
            return modules.getClass().getMethod("get", String.class).invoke(modules, name);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    private static boolean active(Object module) {
        try {
            return (Boolean) module.getClass().getMethod("isActive").invoke(module);
        } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
            return false;
        }
    }

    private static Object setting(Object module, String name) {
        try {
            Object settings = module.getClass().getField("settings").get(module);
            Object s = settings.getClass().getMethod("get", String.class).invoke(settings, name);
            return s == null ? null : s.getClass().getMethod("get").invoke(s);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    private static Float meteorTickRate() {
        try {
            Class<?> c = Class.forName("meteordevelopment.meteorclient.utils.world.TickRate");
            Object rate = c.getField("INSTANCE").get(null);
            return (Float) c.getMethod("getTickRate").invoke(rate);
        } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
            return null;
        }
    }

    private static String onOff(boolean on) { return on ? "on" : "off"; }
    private static String f0(double v) { return String.format(Locale.ROOT, "%.0f", v); }
    private static String f1(double v) { return String.format(Locale.ROOT, "%.1f", v); }
}
