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
import java.util.function.LongSupplier;

/**
 * Fans each message out over every transport, and funnels everything received back into one
 * stream. Outgoing lines are signed, stamped with the send time, and sealed (encrypted) on every
 * transport that leaves the process. Incoming lines are opened, verified, checked for age (replays
 * of old messages are dropped), de-duplicated by message id (the same message may arrive by LAN
 * *and* chat), and own messages dropped. Receiving is thread-safe; delivery happens on
 * {@link #drain()}, which the bot calls on the game thread (FOUNDATION.md decision 4).
 */
public final class Bus {
    /** Older (or further in the future, for clock skew) than this and a message is dropped as a replay. */
    public static final long MAX_AGE_MILLIS = 60_000;
    private static final SecureRandom RNG = new SecureRandom();
    private final UUID self;
    private volatile Signer signer;
    private volatile Sealer sealer;
    private final LongSupplier clock;
    private final List<Transport> transports = new ArrayList<>();
    private final Queue<String> inbox = new ConcurrentLinkedQueue<>();
    private final Map<String, Boolean> seen = new LinkedHashMap<>(256, 0.75f, false) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) { return size() > 2048; }
    };
    private final List<Consumer<Envelope>> listeners = new ArrayList<>();
    private int rejected;
    private int stale;

    public Bus(UUID self, String teamKey, LongSupplier clock) {
        this.self = self;
        this.signer = new Signer(teamKey);
        this.sealer = new Sealer(teamKey);
        this.clock = clock;
    }

    /** Switch to a new team key (e.g. pasted in the settings screen). Takes effect for the next message. */
    public void rekey(String teamKey) {
        this.signer = new Signer(teamKey);
        this.sealer = new Sealer(teamKey);
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
        Envelope e = new Envelope(newId(), self, serverId, clock.getAsLong(), day, x, z, type, fields);
        seen.put(e.msgId(), Boolean.TRUE);
        String line = e.encode(signer);
        for (Transport t : transports) t.send(t.confidential() ? sealer.seal(line) : line);
        return e;
    }

    /** Delivers everything received since the last call. Game thread only. */
    public int drain() {
        int delivered = 0;
        String line;
        long now = clock.getAsLong();
        while ((line = inbox.poll()) != null) {
            if (line.startsWith(Sealer.PREFIX)) {
                Optional<String> opened = sealer.open(line);
                if (opened.isEmpty()) { rejected++; continue; }            // another team's key, or altered
                line = opened.get();
            }
            Optional<Envelope> parsed = Envelope.decode(line, signer);
            if (parsed.isEmpty()) {
                if (line.startsWith(Envelope.VERSION + " ")) rejected++;   // ours, but bad signature/format
                continue;
            }
            Envelope e = parsed.get();
            if (e.sender().equals(self)) continue;
            if (Math.abs(now - e.sentAt()) > MAX_AGE_MILLIS) { stale++; continue; }   // a replay, or a clock far off
            if (seen.put(e.msgId(), Boolean.TRUE) != null) continue;
            for (Consumer<Envelope> l : listeners) l.accept(e);
            delivered++;
        }
        return delivered;
    }

    /** Messages that looked like ours but failed verification — a wrong team key shows up here. */
    public int rejectedCount() {
        return rejected;
    }

    /** Validly signed but too old (or dated in the future) — replays, or a machine whose clock is wrong. */
    public int staleCount() {
        return stale;
    }

    public void close() {
        transports.forEach(Transport::close);
        transports.clear();
    }

    static String newId() {
        return Long.toString(RNG.nextLong() & 0xFFFFFFFFFFFFL, 36);
    }
}
