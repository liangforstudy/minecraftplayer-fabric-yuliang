package io.github.liangforstudy.zymbot.core.task;

import io.github.liangforstudy.zymbot.core.api.Hands;
import io.github.liangforstudy.zymbot.core.api.ItemView;
import io.github.liangforstudy.zymbot.core.api.WorldView;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * Put an armor piece on: bring it to the hotbar, use it (the game swaps it with the piece worn there,
 * which lands in that hotbar slot), check it's on, then take back the slot we had. The use is let go
 * the next tick: held, the game uses it again after 4 ticks and swaps the pieces back.
 */
public final class EquipTask implements Task {
    /** Where it goes when it's in the main inventory. */
    static final int SLOT = 8;
    static final int TRIES = 5;

    private enum Step { BRING, USE, CHECK }

    private final Hands hands;
    private final String id;
    private final BiConsumer<String, String> note;
    private Step step = Step.BRING;
    private int previousSlot = -1;
    private int slot;
    private int tries;
    private String failure = "";

    /** @param note the outcome goes here (the decision log) */
    public EquipTask(Hands hands, String id, BiConsumer<String, String> note) {
        this.hands = hands;
        this.id = id;
        this.note = note;
    }

    @Override
    public Status tick(WorldView world) {
        if (world.armor().contains(id)) {
            hands.holdUse(false);
            if (previousSlot >= 0 && previousSlot != slot) hands.selectSlot(previousSlot);
            note.accept("put " + name() + " on", tries + (tries == 1 ? " try" : " tries"));
            return Status.DONE;
        }
        switch (step) {
            case BRING -> {
                Optional<ItemView> it = world.inventory().stream().filter(i -> id.equals(i.id())).findFirst();
                if (it.isEmpty()) return fail("no " + name() + " carried");
                previousSlot = world.selectedSlot();
                slot = it.get().inHotbar() ? it.get().slot() : SLOT;
                if (!it.get().inHotbar()) hands.swapToHotbar(it.get().slot(), SLOT);
                hands.selectSlot(slot);
                step = Step.USE;
            }
            case USE -> {
                if (++tries > TRIES) return fail(name() + " wouldn't go on");
                hands.holdUse(true);
                step = Step.CHECK;
            }
            case CHECK -> {
                hands.holdUse(false);                                // the server's answer comes a tick or two later
                step = Step.USE;
            }
        }
        return Status.RUNNING;
    }

    private String name() {
        return id.replaceFirst("^minecraft:", "").replace('_', ' ');
    }

    private Status fail(String why) {
        hands.holdUse(false);
        if (previousSlot >= 0 && previousSlot != slot) hands.selectSlot(previousSlot);
        failure = why;
        return Status.FAILED;
    }

    @Override public void cancel() { hands.holdUse(false); }
    @Override public String failure() { return failure; }
    @Override public String describe() { return "putting " + name() + " on"; }
}
