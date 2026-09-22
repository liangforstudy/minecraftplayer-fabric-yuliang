package dev.yuliang.zymbot.core.config;

import java.util.List;
import java.util.Locale;

/**
 * Should the bot start by itself on this server? Only if the server is on the whitelist — a bot
 * that starts playing on a random public server is how players get banned.
 */
public final class AutostartPolicy {
    public static final String SINGLEPLAYER = "singleplayer";
    private static final int DEFAULT_PORT = 25565;

    private AutostartPolicy() {}

    /** @param address as the player joined it, or {@link #SINGLEPLAYER} */
    public static boolean shouldAutostart(List<String> whitelist, String address) {
        String key = normalize(address);
        return whitelist.stream().map(AutostartPolicy::normalize).anyMatch(key::equals);
    }

    /** "Play.Example.NET." → "play.example.net:25565"; "singleplayer" stays as is. */
    public static String normalize(String address) {
        if (address == null) return "";
        String a = address.trim().toLowerCase(Locale.ROOT);
        if (a.equals(SINGLEPLAYER)) return a;
        String host = a, port = String.valueOf(DEFAULT_PORT);
        if (a.startsWith("[")) {                       // [ipv6]:port
            int end = a.indexOf(']');
            host = a.substring(0, end + 1);
            if (a.length() > end + 2 && a.charAt(end + 1) == ':') port = a.substring(end + 2);
        } else if (a.chars().filter(c -> c == ':').count() == 1) {
            host = a.substring(0, a.indexOf(':'));
            port = a.substring(a.indexOf(':') + 1);
        }
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        return host + ":" + port;
    }
}
