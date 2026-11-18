package io.github.liangforstudy.zymbot.core.task;

import io.github.liangforstudy.zymbot.core.api.BlockPos;
import io.github.liangforstudy.zymbot.core.api.Hands;
import io.github.liangforstudy.zymbot.core.api.Vec3;
import io.github.liangforstudy.zymbot.core.api.WorldView;
import java.util.function.Supplier;

/**
 * Freecam's click on a container (issue #9): walk within reach, then open it — a chest, barrel or
 * shulker by its block, a chest minecart or donkey by its entity. The screen is left open for the player
 * (and for Chest Tracker, which records it). On 9b9t a chest sometimes gives no menu for a while (lag:
 * the Chest opener notes), so it waits {@link #WAIT_TICKS} and tries once more.
 */
public final class OpenTask implements Task {
    /** Opened from this far, by eye (vanilla reach is 4.5; leave room for lag). */
    static final int REACH = 3;
    static final int WAIT_TICKS = 15 * 20;
    static final int TRIES = 2;

    private final Hands hands;
    private final String what;
    private final BlockPos block;                    // a block container, or null
    private final int entityId;                      // an entity's, when block is null
    private final Supplier<Vec3> entityPos;          // where that entity is now (null once it's gone)
    private final boolean sneak;
    private WalkTask walk;
    private int waited, tries;
    private String failure = "";

    private OpenTask(Hands hands, String what, BlockPos block, int entityId, Supplier<Vec3> entityPos, boolean sneak) {
        this.hands = hands;
        this.what = what;
        this.block = block;
        this.entityId = entityId;
        this.entityPos = entityPos;
        this.sneak = sneak;
    }

    public static OpenTask block(Hands hands, String what, BlockPos block) {
        return new OpenTask(hands, what, block, -1, null, false);
    }

    /** {@code sneak}: a donkey or mule opens with sneak + use (plain use rides it). */
    public static OpenTask entity(Hands hands, String what, int entityId, Supplier<Vec3> pos, boolean sneak) {
        return new OpenTask(hands, what, null, entityId, pos, sneak);
    }

    private Vec3 target() {
        if (block != null) return new Vec3(block.x() + 0.5, block.y() + 0.5, block.z() + 0.5);
        return entityPos.get();
    }

    @Override
    public Status tick(WorldView world) {
        if (world.openContainerFilled() >= 0 && tries > 0) {    // it opened
            hands.paths().stop();
            return Status.DONE;
        }
        Vec3 at = target();
        if (at == null) return fail(what + " is gone");
        Vec3 me = world.position();
        double dx = at.x() - me.x(), dy = at.y() - (me.y() + 1.62), dz = at.z() - me.z();
        boolean inReach = Math.sqrt(dx * dx + dy * dy + dz * dz) <= REACH + 0.5;
        if (!inReach && tries == 0) {
            BlockPos goal = BlockPos.of(at);
            if (walk == null || (block == null && waited++ % 40 == 0)) walk = new WalkTask(hands.paths(), goal, false, REACH);   // re-aim at a moving entity
            Status s = walk.tick(world);
            if (s == Status.FAILED) return fail("couldn't walk to " + what + ": " + walk.failure());
            return Status.RUNNING;
        }
        if (tries == 0 || waited >= WAIT_TICKS) {
            if (tries >= TRIES) return fail(what + " didn't open (" + TRIES + " tries)");
            hands.paths().stop();
            hands.lookAt(at);
            if (block != null) hands.useOn(block);
            else hands.useOnEntity(entityId, sneak);
            tries++;
            waited = 0;
            return Status.RUNNING;
        }
        waited++;
        return Status.RUNNING;
    }

    private Status fail(String why) {
        failure = why;
        return Status.FAILED;
    }

    @Override public void cancel() { hands.paths().stop(); }
    @Override public String failure() { return failure; }
    @Override public String describe() { return "opening " + what; }
}
