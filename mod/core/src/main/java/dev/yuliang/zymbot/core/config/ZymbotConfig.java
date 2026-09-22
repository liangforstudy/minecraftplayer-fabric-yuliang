package dev.yuliang.zymbot.core.config;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * config/zymbot.json. Field names map to snake_case in the file. Every field has a safe default,
 * so a missing or partial file still works; {@link #normalize()} repairs bad values.
 */
public final class ZymbotConfig {
    public static final int SCHEMA_VERSION = 1;
    private static final Pattern COMMAND = Pattern.compile("[a-z0-9_]{1,16}");

    public int schemaVersion = SCHEMA_VERSION;

    /** Client command name — configurable so it can't clash with another mod or a server plugin. Needs a restart. */
    public String commandRoot = "zbot";
    public List<String> commandAliases = new ArrayList<>();

    /** Servers where the bot starts by itself on join. "singleplayer" opts singleplayer in. */
    public List<String> autostartServers = new ArrayList<>();

    /** Chat prefixes for players without the mod. */
    public String humanForecastPrefix = "!lf";
    public String wherePrefix = "!where";
    public String helpKeyword = "help";

    /** off | relative | exact — exact only where the server owner allows it (FOUNDATION.md → !where). */
    public String shareCoordsWithHumans = "relative";

    /** Shared by the team's bots to sign bus messages. Empty = unsigned (anyone on the LAN could spoof). */
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
