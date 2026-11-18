package io.github.liangforstudy.zymbot.fabric;

import io.github.liangforstudy.zymbot.core.body.HangWatch;
import io.github.liangforstudy.zymbot.core.config.ZymbotConfig;
import java.util.Arrays;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Issue #10: a watchdog thread that closes the server connection when the game's main thread stops
 * ticking for {@code hang_guard_seconds} (15 by default). The usual hang is the main thread alone
 * (rendering, a mod looping, chunk building); this thread keeps running and can still close the socket,
 * so the server removes the player at once. It can't help if the whole Java process freezes (a long
 * GC pause, the OS stalling the game).
 * <p>Always on (the setting aside), with or without {@code /zbot start}; only on a server, never in
 * singleplayer. The frozen thread's stack goes to the log, to tell later what froze it (Litematica's
 * rendering is still an open question, roadmap "Issues #7-#11").
 */
final class HangGuard {
    private static final Logger LOG = LoggerFactory.getLogger("zymbot");
    private static final long CHECK_EVERY_MS = 500;

    private final HangWatch watch = new HangWatch();
    private final ZymbotConfig config;
    private volatile Connection connection;
    private volatile Thread mainThread;
    /** Set by the watchdog when it fired; the main thread, back again, finishes up (Meteor, a notice). */
    private volatile String firedReason;

    HangGuard(ZymbotConfig config) {
        this.config = config;
        Thread t = new Thread(this::run, "Zymbot hang guard");
        t.setDaemon(true);
        t.start();
    }

    /** Main thread, every client tick. */
    void tick(Minecraft mc) {
        mainThread = Thread.currentThread();
        var listener = mc.getConnection();
        if (mc.player == null || listener == null || mc.hasSingleplayerServer()) {
            watch.disarm();
            connection = null;
        } else {
            connection = listener.getConnection();
            watch.beat(System.currentTimeMillis());
        }
        String reason = firedReason;
        if (reason != null) {                                  // the client came back: finish what the watchdog started
            firedReason = null;
            boolean stopped = ZymbotProbes.turnOffMeteor("auto-reconnect");   // or it puts us straight back
            LOG.info("[zymbot] hang guard: the game is responding again{}", stopped ? " (turned off Meteor's Auto Reconnect)" : "");
        }
    }

    private void run() {
        while (true) {
            try {
                Thread.sleep(CHECK_EVERY_MS);
                long now = System.currentTimeMillis();
                long timeout = Math.max(5, config.hangGuardSeconds) * 1000L;
                if (!watch.due(now, timeout, config.hangGuard)) continue;
                Connection c = connection;
                if (c == null) continue;
                long silent = watch.silentFor(now);
                String why = "Zymbot hang guard: the game stopped responding for " + silent / 1000 + " s, so it left the server";
                LOG.warn("[zymbot] {}; main thread was at:\n{}", why, stackOf(mainThread));
                firedReason = why;
                c.disconnect(Component.literal(why));             // closes the socket from this thread: safe in Netty
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {                                // never let the guard die
                LOG.warn("[zymbot] hang guard: {}", t.toString());
            }
        }
    }

    private static String stackOf(Thread t) {
        if (t == null) return "  (unknown)";
        return Arrays.stream(t.getStackTrace()).limit(40).map(f -> "  at " + f).collect(Collectors.joining("\n"));
    }
}
