package dev.yuliang.zymbot.core.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ProtocolTest {
    final UUID a = UUID.randomUUID(), b = UUID.randomUUID();

    @Test
    void roundTripWithAwkwardFields() {
        Signer s = new Signer("key");
        Envelope e = new Envelope("id1", a, "abcd1234", 42, -338, 244, "GUESS",
                List.of("blood moon", "81%", "", "#hash", "~tilde", "ünïcode"));
        Optional<Envelope> back = Envelope.decode(e.encode(s), s);
        assertEquals(Optional.of(e), back);
    }

    @Test
    void tamperedOrWrongKeyIsRejected() {
        Signer s = new Signer("key");
        String line = new Envelope("id1", a, "srv", 1, 0, 0, "READ", List.of("blood", "0.7")).encode(s);
        assertTrue(Envelope.decode(line.replace("0.7", "0.9"), s).isEmpty(), "tampered body");
        assertTrue(Envelope.decode(line, new Signer("other")).isEmpty(), "wrong team key");
        assertTrue(Envelope.decode("<Bot2> hello there", s).isEmpty(), "ordinary chat isn't ours");
    }

    @Test
    void busDedupesAcrossTransportsAndDropsOwnMessages() {
        List<Consumer<String>> ears = new ArrayList<>();
        Transport net1 = fake(ears), net2 = fake(ears);
        Signer s = new Signer("");
        Bus sender = new Bus(a, s), receiver = new Bus(b, s);
        sender.addTransport(fake(ears));
        receiver.addTransport(net1);
        receiver.addTransport(net2);       // same message arrives twice, like LAN + chat

        List<Envelope> got = new ArrayList<>();
        receiver.subscribe(got::add);
        List<Envelope> own = new ArrayList<>();
        sender.subscribe(own::add);

        sender.publish("srv", 1, 0, 0, "HELLO", List.of("Bot1"));
        receiver.drain();
        sender.drain();
        assertEquals(1, got.size());
        assertTrue(own.isEmpty());
    }

    @Test
    void responderQueueIsDeterministicAndSpreadsTheJob() {
        Set<UUID> bots = Set.of(a, b, UUID.randomUUID());
        assertEquals(ResponderQueue.order("req-1", bots), ResponderQueue.order("req-1", bots), "same order on every bot");
        int firstIsA = 0;
        for (int i = 0; i < 300; i++) if (ResponderQueue.order("req-" + i, bots).get(0).equals(a)) firstIsA++;
        assertTrue(firstIsA > 50 && firstIsA < 150, "each bot answers roughly a third: " + firstIsA);
        long first = ResponderQueue.delayFor(ResponderQueue.order("x", bots).get(0), "x", bots);
        long second = ResponderQueue.delayFor(ResponderQueue.order("x", bots).get(1), "x", bots);
        assertEquals(0, first);
        assertEquals(ResponderQueue.STEP_MILLIS, second);
    }

    private static Transport fake(List<Consumer<String>> ears) {
        return new Transport() {
            public String name() { return "fake"; }
            public void send(String line) { new ArrayList<>(ears).forEach(e -> e.accept(line)); }
            public void onReceive(Consumer<String> r) { ears.add(r); }
        };
    }
}
