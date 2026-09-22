package dev.yuliang.zymbot.core.protocol;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Fans each message out over every transport, and funnels everything received back into one
 * stream: verified, de-duplicated by message id (the same message may arrive by LAN *and* chat),
 * own messages dropped. Receiving is thread-safe; delivery happens on {@link #drain()}, which the
 * bot calls on the game thread (FOUNDATION.md decision 4 — the brain stays single-threaded).
 */
public final class Bus {
    private static final SecureRandom RNG = new SecureRandom();
    private final UUID self;
    private final Signer signer;
    private final List<Transport> transports = new ArrayList<>();
    private final Queue<String> inbox = new ConcurrentLinkedQueue<>();
    private final Map<String, Boolean> seen = new LinkedHashMap<>(256, 0.75f, false) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) { return size() > 2048; }
    };
    private final List<Consumer<Envelope>> listeners = new ArrayList<>();
    private int rejected;

    public Bus(UUID self, Signer signer) {
        this.self = self;
        this.signer = signer;
    }

    public void addTransport(Transport t) {
        transports.add(t);
        t.onReceive(inbox::add);
    }

    public List<String> transportNames() {
        return transports.stream().map(Transport::name).toList();
    }

    public void subscribe(Consumer<Envelope> listener) {
        listeners.add(listener);
    }

    public Envelope publish(String serverId, long day, int x, int z, String type, List<String> fields) {
        Envelope e = new Envelope(newId(), self, serverId, day, x, z, type, fields);
        seen.put(e.msgId(), Boolean.TRUE);
        String line = e.encode(signer);
        for (Transport t : transports) t.send(line);
        return e;
    }

    /** Delivers everything received since the last call. Game thread only. */
    public int drain() {
        int delivered = 0;
        String line;
        while ((line = inbox.poll()) != null) {
            Optional<Envelope> parsed = Envelope.decode(line, signer);
            if (parsed.isEmpty()) {
                if (line.startsWith(Envelope.VERSION + " ")) rejected++;   // ours, but bad signature/format
                continue;
            }
            Envelope e = parsed.get();
            if (e.sender().equals(self) || seen.put(e.msgId(), Boolean.TRUE) != null) continue;
            for (Consumer<Envelope> l : listeners) l.accept(e);
            delivered++;
        }
        return delivered;
    }

    /** Messages that looked like ours but failed verification — a wrong team key shows up here. */
    public int rejectedCount() {
        return rejected;
    }

    public void close() {
        transports.forEach(Transport::close);
        transports.clear();
    }

    static String newId() {
        return Long.toString(RNG.nextLong() & 0xFFFFFFFFFFFFL, 36);
    }
}
