package dev.yuliang.zymbot.core.task;

import dev.yuliang.zymbot.core.api.Hands;
import dev.yuliang.zymbot.core.api.ItemView;
import dev.yuliang.zymbot.core.api.WorldView;
import java.util.function.Consumer;

/**
 * Eat one item: bring it to the hand, hold "use" until the server says the bite is done, let go.
 * Reports the item as eaten only if one actually disappeared from the inventory.
 */
public final class EatTask implements Task {
    static final int START_TIMEOUT_TICKS = 10;
    /** Spice of Fabric makes repeats slower (×1.3 per repeat), so allow plenty. */
    static final int EAT_TIMEOUT_TICKS = 20 * 20;

    private enum Step { BRING, WAIT_HELD, START, EATING }

    private final Hands hands;
    private final ItemView item;
    private final Consumer<String> onEaten;
    private Step step = Step.BRING;
    private int waited;
    private int countBefore;
    private boolean holding;
    private String failure = "";

    public EatTask(Hands hands, ItemView item, Consumer<String> onEaten) {
        this.hands = hands;
        this.item = item;
        this.onEaten = onEaten;
    }

    @Override
    public Status tick(WorldView world) {
        switch (step) {
            case BRING -> {
                hands.paths().stop();
                countBefore = count(world);
                if (item.inHotbar()) hands.selectSlot(item.slot());
                else hands.swapToHotbar(item.slot(), world.selectedSlot());
                step = Step.WAIT_HELD;
                waited = 0;
            }
            case WAIT_HELD -> {
                if (held(world)) {
                    step = Step.START;
                } else if (++waited > START_TIMEOUT_TICKS) {
                    return fail("couldn't get " + item.id() + " into my hand");
                }
            }
            case START -> {
                hands.holdUse(true);
                holding = true;
                if (world.usingItem()) {
                    step = Step.EATING;
                    waited = 0;
                } else if (++waited > START_TIMEOUT_TICKS) {
                    return fail("couldn't start eating " + item.id() + " (full, or the server refused)");
                }
            }
            case EATING -> {
                if (world.usingItem()) {
                    hands.holdUse(true);                    // keeps the client from starting another bite
                    if (++waited > EAT_TIMEOUT_TICKS) return fail("still eating after " + EAT_TIMEOUT_TICKS / 20 + "s");
                    break;
                }
                release();
                if (count(world) < countBefore) {
                    onEaten.accept(item.id());
                    return Status.DONE;
                }
                return fail("stopped eating " + item.id() + " before finishing");
            }
        }
        return Status.RUNNING;
    }

    private boolean held(WorldView world) {
        int slot = world.selectedSlot();
        return world.inventory().stream().anyMatch(i -> i.slot() == slot && i.id().equals(item.id()));
    }

    private int count(WorldView world) {
        return world.inventory().stream().filter(i -> i.id().equals(item.id())).mapToInt(ItemView::count).sum();
    }

    private void release() {
        if (holding) hands.holdUse(false);
        holding = false;
    }

    private Status fail(String why) {
        release();
        failure = why;
        return Status.FAILED;
    }

    @Override public void cancel() { release(); }
    @Override public String failure() { return failure; }
    @Override public String describe() { return "eating " + item.id(); }
}
