package io.github.liangforstudy.zymbot.core.task;

import io.github.liangforstudy.zymbot.core.api.Hands;
import io.github.liangforstudy.zymbot.core.api.WorldView;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Anarchy, an overworld route at night: dig in and wait for day, then go on (owner, 2026-10-29,
 * doc/9b9t/roadmap.md). The hole is {@link HideTask}'s, held until it's day and nothing is after us,
 * at most {@link #MAX_WAIT_TICKS}. Where it can't dig in, or still dark after that, it logs out
 * instead (the anarchy way out, as for a wither or warden on the route), and the route carries on
 * from the same point next time.
 */
public final class NightWaitTask implements Task {
    /** A night is ~8 min at 20 TPS, longer when the server lags; a thunderstorm can outlast both. */
    public static final int MAX_WAIT_TICKS = 30 * 60 * 20;

    private final Hands hands;
    private final Predicate<WorldView> dark;
    private final Function<WorldView, String> why;
    private final HideTask hide;
    private boolean loggedOut;

    /**
     * @param dark     still dark (or stormy) enough to wait
     * @param after    something is still after us (a mob at the rim): keep waiting for that too
     * @param why      what's dark, for the log-out reason
     * @param hiding   told while we're down the hole (Body.hiding)
     */
    public NightWaitTask(Hands hands, Predicate<WorldView> dark, Predicate<WorldView> after,
                         Function<WorldView, String> why, Consumer<Boolean> hiding, BiConsumer<String, String> note) {
        this.hands = hands;
        this.dark = dark;
        this.why = why;
        this.hide = new HideTask(hands, w -> dark.test(w) || after.test(w), hiding).maxWait(MAX_WAIT_TICKS).noting(note);
    }

    @Override
    public Status tick(WorldView world) {
        if (loggedOut) return Status.DONE;
        Status s = hide.tick(world);
        if (s == Status.RUNNING) return Status.RUNNING;
        if (s == Status.FAILED) return logOut(why.apply(world) + ", and can't dig in here: " + hide.failure());
        if (dark.test(world)) return logOut("still " + why.apply(world) + " after " + MAX_WAIT_TICKS / 20 / 60 + " min in the hole");
        return Status.DONE;
    }

    private Status logOut(String reason) {
        loggedOut = true;
        hands.logOut("Zymbot logged out: an overworld route at night: " + reason);
        return Status.DONE;
    }

    /** Logged out rather than waited it out. */
    public boolean loggedOut() { return loggedOut; }

    @Override public void cancel() { hide.cancel(); }
    @Override public String failure() { return hide.failure(); }
    @Override public String describe() { return "waiting for day: " + hide.describe(); }
}
