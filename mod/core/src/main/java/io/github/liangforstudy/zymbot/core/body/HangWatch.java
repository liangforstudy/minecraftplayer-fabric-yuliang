package io.github.liangforstudy.zymbot.core.body;

/**
 * The hang guard's clock (issue #10; doc/9b9t/roadmap.md, "Issues #7-#11"). The game's main thread
 * beats once per client tick while we're on a server; a watchdog thread asks {@link #due} every half
 * second. No beat for the timeout: the client is frozen, and the watchdog closes the connection itself
 * so the server drops the player at once (pulling the internet would leave them standing there until
 * the server's own timeout).
 * <p>It watches the client only: server lag still lets client ticks run, so it can't trigger this.
 * Thread-safe: the beat and the check come from different threads.
 */
public final class HangWatch {
    private long lastBeat;
    private boolean armed, fired;

    /** Main thread, each tick on a server: still alive. Re-arms after a fire once we're back on one. */
    public synchronized void beat(long nowMs) {
        lastBeat = nowMs;
        armed = true;
        fired = false;
    }

    /** Main thread: not on a server (title screen, singleplayer, joining), so nothing to guard. */
    public synchronized void disarm() {
        armed = false;
    }

    /**
     * Watchdog thread: true once, when the client has missed beats for {@code timeoutMs} while armed.
     * {@code enabled} false (the setting off) never fires.
     */
    public synchronized boolean due(long nowMs, long timeoutMs, boolean enabled) {
        if (!enabled || !armed || fired || nowMs - lastBeat < timeoutMs) return false;
        fired = true;
        return true;
    }

    /** How long the main thread has been silent, for the log. */
    public synchronized long silentFor(long nowMs) {
        return nowMs - lastBeat;
    }
}
