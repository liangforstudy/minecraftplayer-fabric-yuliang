package dev.yuliang.zymbot.core.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * config/zymbot.json. Field names map to snake_case in the file. Every field has a safe default,
 * so a missing or partial file still works; {@link #normalize()} repairs bad values.
 */
public final class ZymbotConfig {
    public static final int SCHEMA_VERSION = 3;   // 2: accounts + roles; 3: body thresholds
    private static final Pattern COMMAND = Pattern.compile("[a-z0-9_]{1,16}");

    public int schemaVersion = SCHEMA_VERSION;

    /** Client command name — configurable so it can't clash with another mod or a server plugin. Needs a restart. */
    public String commandRoot = "zbot";
    public List<String> commandAliases = new ArrayList<>();

    /**
     * Servers where Zymbot is active: a Bot account takes control there, a Teammate account
     * announces itself. "singleplayer" opts singleplayer in. Everywhere else it stays silent.
     */
    public List<String> autostartServers = new ArrayList<>();

    /** Which accounts are what. Matched by UUID (names can change); the name is for display. */
    public List<Account> accounts = new ArrayList<>();

    public enum Role {
        /** The bot plays this account: takes control on whitelisted servers. */
        BOT,
        /** A human on the team: announces itself and shares readings, never takes control. */
        TEAMMATE,
        /** Not listed: Zymbot stays silent. */
        NONE;

        public String label() { return this == BOT ? "Bot" : this == TEAMMATE ? "Teammate" : "not listed"; }
    }

    public static final class Account {
        public String uuid = "";
        public String name = "";
        public String role = "teammate";

        public Account() {}

        public Account(UUID uuid, String name, Role role) {
            this.uuid = uuid.toString();
            this.name = name;
            this.role = role.name().toLowerCase(Locale.ROOT);
        }

        public Role parsedRole() {
            return "bot".equals(role) ? Role.BOT : "teammate".equals(role) ? Role.TEAMMATE : Role.NONE;
        }
    }

    public Role roleOf(UUID id) {
        for (Account a : accounts) if (a.uuid.equalsIgnoreCase(id.toString())) return a.parsedRole();
        return Role.NONE;
    }

    /** Sets (or with NONE, removes) an account's role. */
    public void setRole(UUID id, String name, Role role) {
        accounts.removeIf(a -> a.uuid.equalsIgnoreCase(id.toString()));
        if (role != Role.NONE) accounts.add(new Account(id, name, role));
    }

    /** Chat prefixes for players without the mod. */
    public String humanForecastPrefix = "!lf";
    public String wherePrefix = "!where";
    public String helpKeyword = "help";

    /** off | relative | exact — exact only where the server owner allows it (FOUNDATION.md → !where). */
    public String shareCoordsWithHumans = "relative";

    /**
     * Shared by the team's bots: signs and encrypts every bus message. Generated at random on first
     * load if empty — never leave it empty (anyone on the network could read and fake messages).
     * Keep it secret; it lives here, outside git.
     */
    public String teamKey = "";

    public Transports transports = new Transports();

    /** Seconds the bot stays paused after a human touches the movement keys. */
    public int humanPauseSeconds = 10;

    /** Opening your world to LAN summons your bots (as if you'd run /zbot summon). */
    public boolean autoSummonOnLan = false;

    /**
     * Singleplayer worlds (by name) that open to LAN by themselves once loaded — on
     * {@link #lanPort}, with online mode off so offline bot accounts can join.
     */
    public List<String> autoOpenLanWorlds = new ArrayList<>();
    public int lanPort = 25565;

    /**
     * Press "I know what I'm doing!" on Minecraft's "Worlds using Experimental Settings are not
     * supported" prompt, so a world can load unattended (play.sh). Only that prompt — never the
     * backup questions for older or customised worlds.
     */
    public boolean skipExperimentalWorldWarning = true;

    /** Press Respawn automatically when the bot is running (or always, on a headless client). */
    public boolean autoRespawn = true;

    // ------------------------------------------------------------------ body (PHASE1.md)

    /** While working on its own objective, stay within this many blocks of the nearest human (R2). */
    public int leashBlocks = 100;
    /** Eat when hunger falls to this (of 20). */
    public int eatBelowHunger = 14;
    /** Health (of 20) at or below which the bot heals or retreats. */
    public int criticalHealth = 8;
    /**
     * Never eaten, on top of anything with a harmful effect. Dried kelp: the server adds a 10%
     * Poison II chance; chorus fruit teleports (SURVIVAL_EARLY_GAME §1.4).
     */
    public List<String> neverEat = new ArrayList<>(List.of(
            "minecraft:dried_kelp", "minecraft:chorus_fruit", "minecraft:rotten_flesh", "minecraft:spider_eye",
            "minecraft:pufferfish", "minecraft:poisonous_potato", "farm_and_charm:rotten_tomato", "vinery:rotten_cherry"));

    /**
     * Knocked out with no medic bot near: how long to give a human within 64 blocks to start a
     * revive before giving up (0 = give up at once). Bleeding out takes 60 s anyway.
     */
    public int downedWaitForHumansSeconds = 45;

    /**
     * Route planning: what one block of water costs, in blocks of land. Swimming drains ~85× more
     * hunger per block than walking (SURVIVAL_EARLY_GAME §4a) — tune from the hunger meter.
     */
    public int swimCostBlocks = 85;
    /** How far around the bot the route planner looks (blocks; bounded by what's loaded). */
    public int routeRadius = 96;

    /** The thresholds that /zbot set and the settings screen can change: name → [min, max]. */
    public static final java.util.Map<String, int[]> TUNABLES = java.util.Map.of(
            "leash", new int[]{8, 1000}, "eat", new int[]{1, 19}, "critical", new int[]{1, 19},
            "downed", new int[]{0, 55});

    public int tunable(String name) {
        return switch (name) {
            case "leash" -> leashBlocks;
            case "eat" -> eatBelowHunger;
            case "critical" -> criticalHealth;
            case "downed" -> downedWaitForHumansSeconds;
            default -> throw new IllegalArgumentException(name);
        };
    }

    /** Null when set, otherwise why not. */
    public String setTunable(String name, int value) {
        int[] range = TUNABLES.get(name);
        if (range == null) return "unknown setting '" + name + "' — leash, eat, critical or downed";
        if (value < range[0] || value > range[1]) return name + " must be " + range[0] + "–" + range[1];
        switch (name) {
            case "leash" -> leashBlocks = value;
            case "eat" -> eatBelowHunger = value;
            case "downed" -> downedWaitForHumansSeconds = value;
            default -> criticalHealth = value;
        }
        return null;
    }

    public static final class Transports {
        public boolean localBus = true;   // UDP multicast: this machine + LAN
        public boolean chat = false;      // server chat — phase 2
        public boolean relay = false;     // MQTT — later
    }

    /**
     * A short, non-secret fingerprint of the team key — compare it between games to check they
     * share a key without ever showing the key itself.
     */
    public static String fingerprint(String teamKey) {
        if (teamKey == null || teamKey.isEmpty()) return "none";
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(("zymbot-fp:" + teamKey).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(h, 0, 3);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Null if the key is usable, otherwise why not. */
    public static String validateTeamKey(String key) {
        if (key == null || key.isBlank()) return "the key can't be empty";
        if (key.chars().anyMatch(Character::isWhitespace)) return "the key can't contain spaces";
        if (key.length() < 8) return "too short — at least 8 characters";
        if (key.length() > 128) return "too long — at most 128 characters";
        return null;
    }

    private static boolean isUuid(String s) {
        try {
            UUID.fromString(s);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Fixes invalid values in place; returns warnings for the log. */
    public List<String> normalize() {
        List<String> warnings = new ArrayList<>();
        if (commandRoot == null || !COMMAND.matcher(commandRoot).matches()) {
            warnings.add("command_root '" + commandRoot + "' is not [a-z0-9_]{1,16}; using 'zbot'");
            commandRoot = "zbot";
        }
        if (commandAliases == null) commandAliases = new ArrayList<>();
        commandAliases.removeIf(a -> {
            boolean bad = a == null || !COMMAND.matcher(a).matches() || a.equals(commandRoot);
            if (bad) warnings.add("ignoring command alias '" + a + "'");
            return bad;
        });
        if (autostartServers == null) autostartServers = new ArrayList<>();
        if (accounts == null) accounts = new ArrayList<>();
        accounts.removeIf(a -> {
            if (a != null && a.role != null) a.role = a.role.trim().toLowerCase(Locale.ROOT);
            boolean bad = a == null || a.uuid == null || !isUuid(a.uuid) || a.parsedRole() == Role.NONE;
            if (bad) warnings.add("ignoring account entry " + (a == null ? "null" : a.name + " / " + a.uuid + " / " + a.role));
            else if (a.name == null) a.name = "?";
            return bad;
        });
        schemaVersion = SCHEMA_VERSION;
        if (!List.of("off", "relative", "exact").contains(shareCoordsWithHumans)) {
            warnings.add("share_coords_with_humans '" + shareCoordsWithHumans + "' unknown; using 'relative'");
            shareCoordsWithHumans = "relative";
        }
        if (humanForecastPrefix == null || humanForecastPrefix.isBlank()) humanForecastPrefix = "!lf";
        if (wherePrefix == null || wherePrefix.isBlank()) wherePrefix = "!where";
        if (helpKeyword == null || helpKeyword.isBlank()) helpKeyword = "help";
        if (teamKey == null) teamKey = "";
        if (transports == null) transports = new Transports();
        if (humanPauseSeconds < 1) humanPauseSeconds = 10;
        for (var t : TUNABLES.entrySet()) {
            int v = tunable(t.getKey()), lo = t.getValue()[0], hi = t.getValue()[1];
            if (v < lo || v > hi) {
                int fixed = Math.max(lo, Math.min(hi, v));
                warnings.add(t.getKey() + " " + v + " out of range " + lo + "–" + hi + "; using " + fixed);
                setTunable(t.getKey(), fixed);
            }
        }
        if (neverEat == null) neverEat = new ArrayList<>();
        if (autoOpenLanWorlds == null) autoOpenLanWorlds = new ArrayList<>();
        if (swimCostBlocks < 1) swimCostBlocks = 85;
        if (routeRadius < 16 || routeRadius > 256) routeRadius = 96;
        if (lanPort < 1024 || lanPort > 65535) {
            warnings.add("lan_port " + lanPort + " out of range; using 25565");
            lanPort = 25565;
        }
        return warnings;
    }
}
