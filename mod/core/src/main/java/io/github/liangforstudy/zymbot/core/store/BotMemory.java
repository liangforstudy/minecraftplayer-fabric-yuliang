package io.github.liangforstudy.zymbot.core.store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What one bot remembers about one server/world between sessions. Grows each phase (known beds,
 * farms, graves, the explored map...) — each addition is a schema bump with a migration.
 */
public final class BotMemory {
    public static final int SCHEMA_VERSION = 4;   // 2: recent_foods; 3: roster role; 4: waypoints, routes

    public int schemaVersion = SCHEMA_VERSION;

    /** Other bots heard on the bus, by uuid. */
    public Map<String, RosterEntry> roster = new LinkedHashMap<>();

    /** The last 11 foods eaten, oldest first — Spice of Fabric's memory, mirrored. */
    public List<String> recentFoods = new ArrayList<>();

    /** Named points on this server (doc/9b9t/roadmap.md §2), by name. */
    public Map<String, Waypoint> waypoints = new LinkedHashMap<>();

    /** Named routes: waypoint names in order, by route name. Routes can share waypoints. */
    public Map<String, List<String>> routes = new LinkedHashMap<>();

    /** The route walk under way or cut short (an interrupt, a death, a log-out), or null. */
    public RouteProgress routeProgress;

    /** {@code y} null: any height there. {@code dimension} as the game names it, e.g. "minecraft:the_nether". */
    public static final class Waypoint {
        public int x;
        public Integer y;
        public int z;
        public String dimension;

        public Waypoint() {}

        public Waypoint(int x, Integer y, int z, String dimension) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.dimension = dimension;
        }

        public String where() { return x + (y == null ? "" : " " + y) + " " + z; }
    }

    /** Walking {@code route} ({@code back}: E to A), heading for its point number {@code leg} (0 = the first). */
    public static final class RouteProgress {
        public String route;
        public boolean back;
        public int leg;

        public RouteProgress() {}

        public RouteProgress(String route, boolean back, int leg) {
            this.route = route;
            this.back = back;
            this.leg = leg;
        }
    }

    public static final class RosterEntry {
        public String name;
        public long lastSeenMillis;
        public int x;
        public int z;
        /** What it is doing: STOPPED / TEAMMATE (announcing only) / DISCOVERY… (the bot is driving). */
        public String phase;
        /**
         * What the account is — BOT / TEAMMATE / NONE — separate from the phase: the owner ran
         * /zbot start on their Teammate and it announced DISCOVERY, so it listed as a Bot and Bot1
         * found no Teammate to regroup with (2026-09-27). Null from a client too old to send it.
         */
        public String role;

        public RosterEntry() {}

        public RosterEntry(String name, long lastSeenMillis, int x, int z, String phase) {
            this(name, lastSeenMillis, x, z, phase, null);
        }

        public RosterEntry(String name, long lastSeenMillis, int x, int z, String phase, String role) {
            this.name = name;
            this.lastSeenMillis = lastSeenMillis;
            this.x = x;
            this.z = z;
            this.phase = phase;
            this.role = role;
        }

        /** A Teammate account (a human's); an old client sent only its phase, TEAMMATE meaning just that. */
        public boolean teammate() {
            return role != null && !role.isEmpty() ? "TEAMMATE".equals(role) : "TEAMMATE".equals(phase);
        }

        /** The bot is driving that player now. */
        public boolean botDriving() { return !"TEAMMATE".equals(phase) && !"STOPPED".equals(phase); }

        /** "Bot", "Teammate", or "Teammate (bot driving)". */
        public String label() {
            if (!teammate()) return "Bot";
            return botDriving() ? "Teammate (bot driving)" : "Teammate";
        }
    }
}
