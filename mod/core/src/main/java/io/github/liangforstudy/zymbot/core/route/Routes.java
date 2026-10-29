package io.github.liangforstudy.zymbot.core.route;

import io.github.liangforstudy.zymbot.core.api.Vec3;
import io.github.liangforstudy.zymbot.core.store.BotMemory;
import io.github.liangforstudy.zymbot.core.store.BotMemory.RouteProgress;
import io.github.liangforstudy.zymbot.core.store.BotMemory.Waypoint;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Waypoints and routes (doc/9b9t/roadmap.md §2): named points, saved per server, and named lists of
 * them walked A → E ({@code go}) or E → A ({@code back}). A walk keeps the point it's heading for in
 * memory, so after an interrupt, a death or a log-out the same order carries on from there. A first
 * point further than {@code routefar} blocks off (500) is refused once; the same order again within
 * {@link #CONFIRM_MILLIS} goes anyway (owner, 2026-10-29). No limit between points.
 */
public final class Routes {
    public static final long CONFIRM_MILLIS = 60_000;
    static final String NAME = "[A-Za-z0-9_-]{1,32}";

    private final Supplier<BotMemory> memory;
    private final Runnable changed;
    private final LongSupplier clock;
    private final IntSupplier farBlocks;
    private String pending;                     // the far-start order waiting to be typed again
    private long pendingUntil;

    /** @param farBlocks the far-start line ({@code routefar}; 0 never asks) */
    public Routes(Supplier<BotMemory> memory, Runnable changed, LongSupplier clock, IntSupplier farBlocks) {
        this.memory = memory;
        this.changed = changed;
        this.clock = clock;
        this.farBlocks = farBlocks;
    }

    // ------------------------------------------------------------------ waypoints

    public String addWaypoint(String name, int x, Integer y, int z, String dimension) {
        if (!name.matches(NAME)) return "a waypoint name is letters, digits, - and _ (up to 32)";
        boolean moved = memory.get().waypoints.containsKey(name);
        Waypoint w = new Waypoint(x, y, z, dimension);
        memory.get().waypoints.put(name, w);
        changed.run();
        return (moved ? "moved waypoint " : "waypoint ") + name + " to " + w.where() + " (" + shortDim(dimension) + ")";
    }

    public String removeWaypoint(String name) {
        if (!memory.get().waypoints.containsKey(name)) return "no waypoint '" + name + "'";
        List<String> using = memory.get().routes.entrySet().stream()
                .filter(e -> e.getValue().contains(name)).map(Map.Entry::getKey).toList();
        if (!using.isEmpty()) return "waypoint " + name + " is on route" + (using.size() > 1 ? "s " : " ")
                + String.join(", ", using) + " — remove " + (using.size() > 1 ? "those" : "that") + " first";
        memory.get().waypoints.remove(name);
        changed.run();
        return "removed waypoint " + name;
    }

    public List<String> listWaypoints() {
        if (memory.get().waypoints.isEmpty()) return List.of("no waypoints yet — wp add <name> <x> <y> <z>, or wp add <name> here");
        return memory.get().waypoints.entrySet().stream()
                .map(e -> e.getKey() + ": " + e.getValue().where() + " (" + shortDim(e.getValue().dimension) + ")").toList();
    }

    // ------------------------------------------------------------------ routes

    public String addRoute(String name, List<String> points) {
        if (!name.matches(NAME)) return "a route name is letters, digits, - and _ (up to 32)";
        if (points.isEmpty()) return "route add <name> <waypoint> <waypoint> …";
        String dim = null;
        for (String p : points) {
            Waypoint w = memory.get().waypoints.get(p);
            if (w == null) return "no waypoint '" + p + "' — wp list";
            if (dim != null && !dim.equals(w.dimension)) return "route " + name + " changes dimension at " + p
                    + " (" + shortDim(dim) + " → " + shortDim(w.dimension) + "): portals aren't handled yet";
            dim = w.dimension;
        }
        boolean replaced = memory.get().routes.containsKey(name);
        memory.get().routes.put(name, List.copyOf(points));
        RouteProgress p = memory.get().routeProgress;
        if (replaced && p != null && name.equals(p.route)) memory.get().routeProgress = null;   // its legs changed
        changed.run();
        return (replaced ? "changed route " : "route ") + name + ": " + String.join(" → ", points)
                + " (" + Math.round(length(name)) + " blocks)";
    }

    public String removeRoute(String name) {
        if (memory.get().routes.remove(name) == null) return "no route '" + name + "'";
        RouteProgress p = memory.get().routeProgress;
        if (p != null && name.equals(p.route)) memory.get().routeProgress = null;
        changed.run();
        return "removed route " + name;
    }

    public List<String> listRoutes() {
        if (memory.get().routes.isEmpty()) return List.of("no routes yet — route add <name> <waypoint> <waypoint> …");
        RouteProgress p = memory.get().routeProgress;
        return memory.get().routes.entrySet().stream().map(e -> e.getKey() + ": " + String.join(" → ", e.getValue())
                + " (" + Math.round(length(e.getKey())) + " blocks)"
                + (p != null && e.getKey().equals(p.route) ? " — " + (p.back ? "back" : "go") + " cut short before "
                        + points(e.getKey(), p.back).get(Math.min(p.leg, e.getValue().size() - 1)) : "")).toList();
    }

    /** Point to point, as the crow flies, ignoring height. */
    private double length(String route) {
        List<String> names = memory.get().routes.get(route);
        double d = 0;
        for (int i = 1; i < names.size(); i++) {
            Waypoint a = memory.get().waypoints.get(names.get(i - 1)), b = memory.get().waypoints.get(names.get(i));
            if (a != null && b != null) d += Math.hypot(b.x - a.x, b.z - a.z);
        }
        return d;
    }

    /** The waypoint names in walking order. */
    private List<String> points(String route, boolean back) {
        List<String> names = new ArrayList<>(memory.get().routes.get(route));
        if (back) Collections.reverse(names);
        return names;
    }

    // ------------------------------------------------------------------ walking

    /** A walk that may start: {@code refusal} null, else why not (and nothing else is set). */
    public record Start(String refusal, String route, boolean back, List<String> names, List<Waypoint> points, int leg, String reply) {
        static Start no(String why) { return new Start(why, null, false, List.of(), List.of(), 0, null); }
    }

    /**
     * Start (or carry on with) a walk along {@code route}: from the point it was heading for when cut
     * short, unless {@code restart}; refused once when that first point is far, or in another dimension.
     */
    public Start start(String route, boolean back, boolean restart, Vec3 at, String dimension) {
        if (!memory.get().routes.containsKey(route)) return Start.no("no route '" + route + "' — route list");
        List<String> names = points(route, back);
        List<Waypoint> pts = new ArrayList<>();
        for (String n : names) {
            Waypoint w = memory.get().waypoints.get(n);
            if (w == null) return Start.no("route " + route + " uses waypoint '" + n + "', which is gone");
            pts.add(w);
        }
        RouteProgress p = memory.get().routeProgress;
        boolean resume = !restart && p != null && route.equals(p.route) && p.back == back && p.leg > 0 && p.leg < pts.size();
        int leg = resume ? p.leg : 0;
        Waypoint first = pts.get(leg);
        if (!first.dimension.equals(dimension)) return Start.no(names.get(leg) + " is in " + shortDim(first.dimension)
                + ", we're in " + shortDim(dimension) + ": portals aren't handled yet");
        double far = Math.hypot(first.x + 0.5 - at.x(), first.z + 0.5 - at.z());
        String order = "route " + (back ? "back " : "go ") + route + (restart ? " restart" : "");
        String key = order + "@" + leg;
        long now = clock.getAsLong();
        if (farBlocks.getAsInt() > 0 && far > farBlocks.getAsInt() && !(key.equals(pending) && now < pendingUntil)) {
            pending = key;
            pendingUntil = now + CONFIRM_MILLIS;
            return Start.no(names.get(leg) + " (" + first.where() + ") is " + Math.round(far) + " blocks away, more than "
                    + farBlocks.getAsInt() + " — type " + order + " again within " + CONFIRM_MILLIS / 1000 + " s to go anyway");
        }
        pending = null;
        memory.get().routeProgress = new RouteProgress(route, back, leg);
        changed.run();
        String reply = "route " + route + (back ? " back" : "") + ": " + String.join(" → ", names);
        if (resume) reply += " — carrying on from " + names.get(leg) + " (point " + (leg + 1) + " of " + names.size()
                + "); " + order + " restart starts at " + names.get(0);
        return new Start(null, route, back, names, pts, leg, reply);
    }

    /** The walk reached point {@code leg}: the next one is where it carries on from. */
    public void reached(String route, boolean back, int leg, int of) {
        memory.get().routeProgress = leg + 1 >= of ? null : new RouteProgress(route, back, leg + 1);
        changed.run();
    }

    static String shortDim(String dimension) {
        if (dimension == null) return "?";
        return switch (dimension) {
            case "minecraft:overworld" -> "the overworld";
            case "minecraft:the_nether" -> "the Nether";
            case "minecraft:the_end" -> "the End";
            default -> dimension;
        };
    }
}
