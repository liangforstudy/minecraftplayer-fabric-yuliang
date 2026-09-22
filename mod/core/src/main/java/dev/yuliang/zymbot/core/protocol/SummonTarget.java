package dev.yuliang.zymbot.core.protocol;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Where a SUMMON asks bots to go: the sender's LAN world ({@code lan:<port>:<its IPv4s>}), or the
 * server the sender is on ({@code server:<address>}). A LAN host doesn't know which of its
 * addresses a bot can reach, so it sends them all and each bot picks: 127.0.0.1 on the same
 * machine, otherwise one on its own subnet.
 */
public record SummonTarget(boolean lan, int port, List<String> hostIps, String server) {
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern SERVER = Pattern.compile("[A-Za-z0-9._\\-:\\[\\]]{1,255}");

    public static SummonTarget lan(int port, List<String> hostIps) {
        return new SummonTarget(true, port, List.copyOf(hostIps), null);
    }

    public static SummonTarget server(String address) {
        return new SummonTarget(false, 0, List.of(), address);
    }

    public String encode() {
        return lan ? "lan:" + port + ":" + String.join(",", hostIps) : "server:" + server;
    }

    /** Parses and validates — a summon comes off the network, so anything odd is rejected. */
    public static Optional<SummonTarget> decode(String s) {
        if (s == null) return Optional.empty();
        if (s.startsWith("server:")) {
            String addr = s.substring(7);
            return SERVER.matcher(addr).matches() ? Optional.of(server(addr)) : Optional.empty();
        }
        if (!s.startsWith("lan:")) return Optional.empty();
        String[] parts = s.substring(4).split(":", 2);
        try {
            int port = Integer.parseInt(parts[0]);
            if (port < 1 || port > 65535) return Optional.empty();
            List<String> ips = new ArrayList<>();
            if (parts.length > 1 && !parts[1].isEmpty()) {
                for (String ip : parts[1].split(",")) {
                    if (!IPV4.matcher(ip).matches()) return Optional.empty();
                    ips.add(ip);
                }
            }
            return Optional.of(lan(port, ips));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** The address to connect to from a machine with these IPv4 addresses. */
    public String addressFrom(Set<String> myIps) {
        if (!lan) return server;
        if (hostIps.isEmpty() || hostIps.stream().anyMatch(myIps::contains)) return "127.0.0.1:" + port;
        for (String ip : hostIps) {
            String net = ip.substring(0, ip.lastIndexOf('.') + 1);        // same /24
            if (myIps.stream().anyMatch(m -> m.startsWith(net))) return ip + ":" + port;
        }
        return hostIps.get(0) + ":" + port;
    }

    public String describe() {
        return lan ? "LAN port " + port : server;
    }

    /** This machine's non-loopback IPv4 addresses. */
    public static List<String> localIps() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                nif.inetAddresses().filter(a -> a instanceof Inet4Address).forEach(a -> out.add(a.getHostAddress()));
            }
        } catch (SocketException ignored) {
            // no interfaces readable: bots fall back to 127.0.0.1
        }
        return out;
    }
}
