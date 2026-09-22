package dev.yuliang.zymbot.core.protocol;

import java.util.function.Consumer;

/**
 * A way to move bus lines between bots: local network, server chat, internet relay, or an
 * in-process fake for tests. All carry the same {@link Envelope} lines.
 */
public interface Transport extends AutoCloseable {
    String name();

    /** Sends one encoded line. Must not block the game thread for long. */
    void send(String line);

    /** Where received lines go. May be called from a network thread — the bus queues them. */
    void onReceive(Consumer<String> receiver);

    /**
     * Whether lines on this transport leave the process, and so must be encrypted. True for every
     * real network; only in-process test fakes may say false.
     */
    default boolean confidential() { return true; }

    @Override
    default void close() {}
}
