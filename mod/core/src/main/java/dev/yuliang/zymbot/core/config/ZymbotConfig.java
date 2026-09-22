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
    public static final int SCHEMA_VERSION = 2;   // 2: accounts + roles
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

    /** Press Respawn automatically when the bot is running (or always, on a headless client). */
    public boolean autoRespawn = true;

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
        return warnings;
    }
}
