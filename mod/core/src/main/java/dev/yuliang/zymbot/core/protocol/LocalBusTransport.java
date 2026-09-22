package dev.yuliang.zymbot.core.protocol;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.StandardSocketOptions;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The owner's own bots, on this machine or the home network: UDP multicast, TTL 1 so it never
 * leaves the LAN. Needs no server, no setup, no internet. Several bots on one machine share the
 * port (SO_REUSEADDR/SO_REUSEPORT) and all receive every packet.
 */
public final class LocalBusTransport implements Transport {
    public static final String GROUP = "239.255.90.66";
    public static final int PORT = 25590;
    private static final Logger LOG = LoggerFactory.getLogger("zymbot");
    private static final int MAX_PACKET = 1400;

    private final InetAddress group;
    private final int port;
    private final MulticastSocket socket;
    private final List<NetworkInterface> joined = new ArrayList<>();
    private volatile Consumer<String> receiver = line -> {};
    private volatile boolean open = true;

    public LocalBusTransport() throws IOException {
        this(GROUP, PORT);
    }

    public LocalBusTransport(String groupAddress, int port) throws IOException {
        this.group = InetAddress.getByName(groupAddress);
        this.port = port;
        this.socket = new MulticastSocket(null);
        socket.setReuseAddress(true);
        try {
            socket.setOption(StandardSocketOptions.SO_REUSEPORT, true);   // macOS/Linux: several bots, one port
        } catch (UnsupportedOperationException ignored) {
            // Windows: SO_REUSEADDR alone lets multicast listeners share the port
        }
        socket.bind(new InetSocketAddress(port));
        socket.setTimeToLive(1);
        socket.setOption(StandardSocketOptions.IP_MULTICAST_LOOP, true);   // bots on this same machine
        for (NetworkInterface nif : multicastInterfaces()) {
            try {
                socket.joinGroup(new InetSocketAddress(group, port), nif);
                joined.add(nif);
            } catch (IOException e) {
                LOG.debug("[zymbot] local bus: can't join on {}: {}", nif.getName(), e.getMessage());
            }
        }
        if (joined.isEmpty()) {
            socket.close();
            throw new IOException("no network interface accepted the multicast group");
        }
        Thread t = new Thread(this::receiveLoop, "zymbot-localbus");
        t.setDaemon(true);
        t.start();
        LOG.info("[zymbot] local bus on {}:{} via {}", groupAddress, port,
                joined.stream().map(NetworkInterface::getName).toList());
    }

    @Override public String name() { return "local"; }

    @Override
    public void send(String line) {
        byte[] data = line.getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_PACKET) {
            LOG.warn("[zymbot] local bus: dropping {}-byte message (max {})", data.length, MAX_PACKET);
            return;
        }
        DatagramPacket packet = new DatagramPacket(data, data.length, group, port);
        for (NetworkInterface nif : joined) {      // send on each joined interface, incl. loopback
            try {
                socket.setNetworkInterface(nif);
                socket.send(packet);
            } catch (IOException e) {
                LOG.debug("[zymbot] local bus: send on {} failed: {}", nif.getName(), e.getMessage());
            }
        }
    }

    @Override public void onReceive(Consumer<String> receiver) { this.receiver = receiver; }

    @Override
    public void close() {
        open = false;
        socket.close();
    }

    private void receiveLoop() {
        byte[] buf = new byte[MAX_PACKET + 100];
        while (open) {
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(p);
                receiver.accept(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                if (open) LOG.debug("[zymbot] local bus receive: {}", e.getMessage());
            }
        }
    }

    /** Up, multicast-capable interfaces; loopback first so same-machine bots always connect. */
    private static List<NetworkInterface> multicastInterfaces() throws SocketException {
        List<NetworkInterface> out = new ArrayList<>();
        for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (nif.isUp() && nif.supportsMulticast() && !nif.isVirtual()
                    && nif.inetAddresses().anyMatch(a -> a instanceof java.net.Inet4Address)) {
                if (nif.isLoopback()) out.add(0, nif); else out.add(nif);
            }
        }
        return out;
    }
}
