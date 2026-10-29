package io.github.liangforstudy.zymbot.core.task;

import io.github.liangforstudy.zymbot.core.api.BlockPos;
import io.github.liangforstudy.zymbot.core.api.Hands;
import io.github.liangforstudy.zymbot.core.api.ItemView;
import io.github.liangforstudy.zymbot.core.api.Vec3;
import io.github.liangforstudy.zymbot.core.api.WorldView;
import java.util.Optional;

/**
 * Put the flames out with a water bucket: look straight down, pour it at our feet, and once the fire
 * is out scoop it back up, then take back the slot we had. Each use waits a tick after turning, since
 * the server reads the facing the use is aimed with.
 */
public final class PourWaterTask implements Task {
    public static final String WATER_BUCKET = "minecraft:water_bucket";
    public static final String BUCKET = "minecraft:bucket";
    /** Where it goes when it's in the main inventory. */
    static final int BUCKET_SLOT = 8;
    /** Out within this long of pouring, or it's done waiting. */
    static final int OUT_TICKS = 20;
    static final int TRIES = 5;

    private enum Step { BRING, AIM, POUR, WAIT_OUT, AIM_BACK, SCOOP, DONE }

    private final Hands hands;
    private Step step = Step.BRING;
    private int previousSlot = -1;
    private int slot;
    private int waited;
    private int tries;
    private BlockPos feet;
    private String failure = "";
    private String outcome = "";

    public PourWaterTask(Hands hands) {
        this.hands = hands;
    }

    @Override
    public Status tick(WorldView world) {
        switch (step) {
            case BRING -> {
                Optional<ItemView> b = world.inventory().stream().filter(i -> WATER_BUCKET.equals(i.id())).findFirst();
                if (b.isEmpty()) return fail("no water bucket");
                hands.paths().stop();
                previousSlot = world.selectedSlot();
                slot = b.get().inHotbar() ? b.get().slot() : BUCKET_SLOT;
                if (!b.get().inHotbar()) hands.swapToHotbar(b.get().slot(), BUCKET_SLOT);
                hands.selectSlot(slot);
                Vec3 p = world.position();
                feet = new BlockPos((int) Math.floor(p.x()), (int) Math.floor(p.y() + 0.01), (int) Math.floor(p.z()));
                step = Step.AIM;
            }
            case AIM -> {
                lookDown();
                step = Step.POUR;
                waited = 0;
            }
            case POUR -> {
                lookDown();
                if (holding(world, WATER_BUCKET)) {
                    if (++tries > TRIES) return fail("the water wouldn't pour");
                    hands.holdUse(true);
                    return Status.RUNNING;
                }
                hands.holdUse(false);
                step = Step.WAIT_OUT;
                waited = 0;
            }
            case WAIT_OUT -> {
                hands.holdUse(false);
                if (world.onFire() && ++waited < OUT_TICKS) return Status.RUNNING;
                outcome = world.onFire() ? "still burning" : "put out";
                step = Step.AIM_BACK;
                tries = 0;
            }
            case AIM_BACK -> {
                lookDown();
                step = Step.SCOOP;
            }
            case SCOOP -> {
                lookDown();
                if (holding(world, BUCKET)) {
                    if (++tries > TRIES) {
                        outcome += ", left the water";
                        return finish();
                    }
                    hands.holdUse(true);
                    return Status.RUNNING;
                }
                outcome += ", water back in the bucket";
                return finish();
            }
            case DONE -> { return Status.DONE; }
        }
        return Status.RUNNING;
    }

    /** At the block we stand in: from the eyes that's straight down. */
    private void lookDown() {
        hands.lookAt(new Vec3(feet.x() + 0.5, feet.y(), feet.z() + 0.5));
    }

    private boolean holding(WorldView world, String id) {
        int sel = world.selectedSlot();
        return world.inventory().stream().anyMatch(i -> i.slot() == sel && id.equals(i.id()));
    }

    private Status finish() {
        hands.holdUse(false);
        if (previousSlot >= 0 && previousSlot != slot) hands.selectSlot(previousSlot);
        step = Step.DONE;
        return Status.DONE;
    }

    private Status fail(String why) {
        hands.holdUse(false);
        failure = why;
        return Status.FAILED;
    }

    /** "put out, water back in the bucket", once done. */
    public String outcome() { return outcome; }

    @Override public void cancel() { hands.holdUse(false); }
    @Override public String failure() { return failure; }
    @Override public String describe() { return "putting the fire out with water"; }
}
