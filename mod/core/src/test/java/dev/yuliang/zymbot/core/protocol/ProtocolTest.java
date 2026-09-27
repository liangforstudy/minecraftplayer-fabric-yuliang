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
        Envelope e = new Envelope("id1", a, "abcd1234", 1_700_000_000_000L, 42, -338, 244, "GUESS",
                List.of("blood moon", "81%", "", "#hash", "~tilde", "ünïcode"));
        Optional<Envelope> back = Envelope.decode(e.encode(s), s);
        assertEquals(Optional.of(e), back);
    }

    @Test
    void statusRequestAndLines_roundTrip_andALongLineStillFitsOnePacket() {
        Signer s = new Signer("key");
        Envelope ask = new Envelope("id2", a, "srv", 5, 1, 0, 0, MessageTypes.STATUS, List.of("Owner", "Bot1", "ab12cd34"));
        assertEquals(Optional.of(ask), Envelope.decode(ask.encode(s), s));
        String line = "body: health 20, hunger 18/20 (+4.5 saturation) — eats at ≤14, critical ≤8, leash 21, danger modpack "
                + "x".repeat(300 - 104);
        Envelope reply = new Envelope("id3", b, "srv", 5, 1, -338, 244, MessageTypes.STATUS_LINE,
                List.of("Bot1", "Owner", "ab12cd34", "3", "12", line));
        assertEquals(Optional.of(reply), Envelope.decode(reply.encode(s), s));
        String sealed = new Sealer("team-key").seal(reply.encode(s));
        assertTrue(sealed.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 1400,
                "a 300-char status line fits the local bus's packet: " + sealed.length());
    }

    @Test
    void tamperedOrWrongKeyIsRejected() {
        Signer s = new Signer("key");
        String line = new Envelope("id1", a, "srv", 5, 1, 0, 0, "READ", List.of("blood", "0.7")).encode(s);
        assertTrue(Envelope.decode(line.replace("0.7", "0.9"), s).isEmpty(), "tampered body");
        assertTrue(Envelope.decode(line, new Signer("other")).isEmpty(), "wrong team key");
        assertTrue(Envelope.decode("<Bot2> hello there", s).isEmpty(), "ordinary chat isn't ours");
    }

    @Test
    void busDedupesAcrossTransportsAndDropsOwnMessages() {
        List<Consumer<String>> ears = new ArrayList<>();
        Transport net1 = fake(ears), net2 = fake(ears);
        long[] now = {1_000_000};
        Bus sender = new Bus(a, "k", () -> now[0]), receiver = new Bus(b, "k", () -> now[0]);
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

    @Test
    void sealedLinesAreUnreadableAndTamperProof() {
        Sealer ours = new Sealer("team-key"), theirs = new Sealer("other-key");
        String plain = "zb1 id " + a + " srv 5 1 -338,244 HELLO Bot1 DISCOVERY #sig";
        String sealed = ours.seal(plain);
        assertTrue(sealed.startsWith(Sealer.PREFIX));
        assertFalse(sealed.contains("Bot1") || sealed.contains("-338"), "a listener must not see names or positions");
        assertEquals(Optional.of(plain), ours.open(sealed));
        assertTrue(theirs.open(sealed).isEmpty(), "another team's key can't open it");
        char[] c = sealed.toCharArray();
        c[c.length - 3] = c[c.length - 3] == 'A' ? 'B' : 'A';
        assertTrue(ours.open(new String(c)).isEmpty(), "any tampering is detected");
        assertNotEquals(ours.seal(plain), ours.seal(plain), "fresh nonce each time");
    }

    @Test
    void replaysOfOldMessagesAreDropped() {
        List<Consumer<String>> ears = new ArrayList<>();
        List<String> wire = new ArrayList<>();
        long[] now = {1_000_000};
        Bus sender = new Bus(a, "k", () -> now[0]), receiver = new Bus(b, "k", () -> now[0]);
        Transport recorder = new Transport() {
            public String name() { return "rec"; }
            public void send(String line) { wire.add(line); new ArrayList<>(ears).forEach(e -> e.accept(line)); }
            public void onReceive(Consumer<String> r) { ears.add(r); }
        };
        sender.addTransport(recorder);
        receiver.addTransport(fake(ears));
        List<Envelope> got = new ArrayList<>();
        receiver.subscribe(got::add);

        sender.publish("srv", 1, 0, 0, "READ", List.of("blood", "0.9"));
        receiver.drain();
        assertEquals(1, got.size());

        now[0] += Bus.MAX_AGE_MILLIS + 1_000;                 // an attacker replays the recorded line later,
        List<Envelope> got2 = new ArrayList<>();              // to a bot that never saw it (dedupe can't help)
        List<Consumer<String>> ears2 = new ArrayList<>();
        Bus late = new Bus(b, "k", () -> now[0]);
        late.addTransport(fake(ears2));
        late.subscribe(got2::add);
        ears2.forEach(e -> e.accept(wire.get(0)));
        late.drain();
        assertTrue(got2.isEmpty(), "a validly signed but old message is a replay");
        assertEquals(1, late.staleCount());
    }

    private static Transport fake(List<Consumer<String>> ears) {
        return new Transport() {
            public String name() { return "fake"; }
            public void send(String line) { new ArrayList<>(ears).forEach(e -> e.accept(line)); }
            public void onReceive(Consumer<String> r) { ears.add(r); }
        };
    }
}
