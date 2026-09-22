package dev.yuliang.zymbot.core.protocol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Who answers a public request that needs one reply (FOUNDATION.md → one-answer rule). Every bot
 * computes the same order from the request id, so there's no leader and no vote; the first in line
 * answers, and if it missed the request the next one does {@link #STEP_MILLIS} later. The order is
 * reshuffled per request, spreading the job around.
 */
public final class ResponderQueue {
    public static final long STEP_MILLIS = 1500;

    private ResponderQueue() {}

    public static List<UUID> order(String requestId, Collection<UUID> bots) {
        List<UUID> list = new ArrayList<>(bots);
        list.sort(Comparator.comparing((UUID u) -> hash(requestId + "/" + u)).thenComparing(UUID::toString));
        return list;
    }

    /** How long this bot waits before answering, if nobody else has. */
    public static long delayFor(UUID self, String requestId, Collection<UUID> bots) {
        int i = order(requestId, bots).indexOf(self);
        return (i < 0 ? bots.size() : i) * STEP_MILLIS;
    }

    private static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
