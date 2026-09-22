package dev.yuliang.zymbot.core.task;

import dev.yuliang.zymbot.core.api.BlockPos;
import dev.yuliang.zymbot.core.api.Hands;
import dev.yuliang.zymbot.core.api.ItemView;
import dev.yuliang.zymbot.core.api.Vec3;
import dev.yuliang.zymbot.core.api.WorldView;

/**
 * Get our things back: civfabric puts everything into a grave at the death site, and gives it back
 * when its owner right-clicks it with an empty hand (BOT_DESIGN → civfabric grave). Walk up to the
 * nearest grave, free the hand, right-click, and check the grave is gone.
 */
public final class GraveTask implements Task {
    public static final String GRAVE = "civfabric:grave";
    public static final int SEARCH_RADIUS = 16;
    static final int REACH = 2;
    static final int SETTLE_TICKS = 20;

    private final Hands hands;
    private final BlockPos grave;
    private WalkTask walk;
    private int waited = -1;
    private String failure = "";

    public GraveTask(Hands hands, BlockPos grave) {
        this.hands = hands;
        this.grave = grave;
    }

    @Override
    public Status tick(WorldView world) {
        if (waited >= 0) {                                    // clicked: did it open for us?
            if (!GRAVE.equals(world.blockAt(grave))) return Status.DONE;
            if (++waited > SETTLE_TICKS) return fail("the grave at " + where() + " didn't open — not ours?");
            return Status.RUNNING;
        }
        Vec3 me = world.position();
        double d = Math.sqrt(Math.pow(me.x() - (grave.x() + 0.5), 2) + Math.pow(me.y() - grave.y(), 2)
                + Math.pow(me.z() - (grave.z() + 0.5), 2));
        if (d > REACH + 1) {
            if (walk == null) walk = new WalkTask(hands.paths(), grave, false, REACH);
            Status s = walk.tick(world);
            if (s == Status.FAILED) return fail("can't reach the grave: " + walk.failure());
            if (s == Status.RUNNING) return Status.RUNNING;
        }
        hands.paths().stop();
        int empty = emptyHotbarSlot(world);
        if (empty >= 0) hands.selectSlot(empty);
        hands.lookAt(new Vec3(grave.x() + 0.5, grave.y() + 0.5, grave.z() + 0.5));
        hands.useOn(grave);
        waited = 0;
        return Status.RUNNING;
    }

    private static int emptyHotbarSlot(WorldView world) {
        for (int slot = 0; slot < 9; slot++) {
            final int s = slot;
            if (world.inventory().stream().noneMatch((ItemView i) -> i.slot() == s)) return s;
        }
        return -1;
    }

    private String where() { return grave.x() + " " + grave.y() + " " + grave.z(); }

    private Status fail(String why) {
        failure = why;
        return Status.FAILED;
    }

    @Override public void cancel() { if (walk != null) walk.cancel(); }
    @Override public String failure() { return failure; }
    @Override public String describe() { return "picking up my grave at " + where(); }
}
