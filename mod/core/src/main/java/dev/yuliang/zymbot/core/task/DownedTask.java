package dev.yuliang.zymbot.core.task;

import dev.yuliang.zymbot.core.api.EntityView;
import dev.yuliang.zymbot.core.api.Hands;
import dev.yuliang.zymbot.core.api.WorldView;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Knocked out: nothing works (moving, items, attacking are all refused), so let go of every key and
 * decide whether waiting is worth it. A revive costs nothing; bleeding out and giving up are the same
 * death, so waiting only helps if someone can come: someone already reviving, a medic bot near, or
 * a human near for {@code humanGraceTicks}. Otherwise give up at once (BOT_DESIGN → Knocked out).
 */
public final class DownedTask implements Task {
    public static final double HUMAN_RANGE = 64;
    /** No longer down this long after /giveup: the server refused (a player downed us). */
    static final int GIVE_UP_REFUSED_TICKS = 60;

    /** How the task reaches the rest of the bot. */
    public interface Help {
        /** Tell the team and the humans near us, once per knockout. */
        void callForHelp(WorldView world, List<EntityView> humansNear);
        /** A medic bot (with stitches) is close enough to come. */
        boolean medicNear(WorldView world);
    }

    private final Hands hands;
    private final Help help;
    private final Function<WorldView, List<EntityView>> humansNear;
    private final int humanGraceTicks;
    private final BiConsumer<String, String> log;
    private boolean started, gaveUp;
    private int ticks, gaveUpAt;
    private String state = "down";

    public DownedTask(Hands hands, Help help, Function<WorldView, List<EntityView>> humansNear, int humanGraceTicks,
                      BiConsumer<String, String> log) {
        this.hands = hands;
        this.help = help;
        this.humansNear = humansNear;
        this.humanGraceTicks = humanGraceTicks;
        this.log = log;
    }

    @Override
    public Status tick(WorldView world) {
        if (world.downedSecondsLeft() < 0) return Status.DONE;     // revived (or gone)
        if (!started) {
            started = true;
            hands.paths().stop();
            hands.holdKeys(false, false);
            hands.holdUse(false);
            help.callForHelp(world, humansNear.apply(world));
        }
        ticks++;
        if (world.beingRevived()) {
            state = "being revived";
        } else if (help.medicNear(world)) {
            state = "waiting for a medic bot";
        } else if (!gaveUp && ticks < humanGraceTicks && !humansNear.apply(world).isEmpty()) {
            state = "waiting " + (humanGraceTicks - ticks + 19) / 20 + "s for a human to revive me";
        } else if (!gaveUp) {
            gaveUp = true;
            gaveUpAt = ticks;
            state = "giving up";
            log.accept("giving up", humansNear.apply(world).isEmpty()
                    ? "knocked out, no medic bot near, no human within " + (int) HUMAN_RANGE + " blocks"
                    : "knocked out, nobody came to revive me in " + humanGraceTicks / 20 + "s");
            hands.command("giveup");
        } else if (ticks - gaveUpAt > GIVE_UP_REFUSED_TICKS) {
            state = "can't give up (a player downed me) — waiting it out, " + world.downedSecondsLeft() + "s";
        }
        return Status.RUNNING;
    }

    @Override public void cancel() {}
    @Override public String describe() { return "knocked out — " + state; }
}
