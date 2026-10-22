package io.github.liangforstudy.zymbot.core;

import io.github.liangforstudy.zymbot.core.protocol.Transport;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** An in-process "network": every endpoint hears every line, including its own (like multicast loopback). */
public final class FakeBus {
    private final List<Consumer<String>> ears = new ArrayList<>();
    public final List<String> wire = new ArrayList<>();

    /** A config with the local bus sharing world knowledge (off by default since 2026-10-15). */
    public static io.github.liangforstudy.zymbot.core.config.ZymbotConfig busOnConfig() {
        var c = new io.github.liangforstudy.zymbot.core.config.ZymbotConfig();
        c.transports.localBus = true;
        return c;
    }

    public Transport endpoint() {
        return new Transport() {
            @Override public String name() { return "fake"; }
            @Override public void send(String line) { wire.add(line); ears.forEach(e -> e.accept(line)); }
            @Override public void onReceive(Consumer<String> receiver) { ears.add(receiver); }
        };
    }
}
