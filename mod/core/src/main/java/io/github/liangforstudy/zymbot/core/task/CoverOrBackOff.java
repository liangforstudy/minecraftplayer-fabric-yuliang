package io.github.liangforstudy.zymbot.core.task;

import io.github.liangforstudy.zymbot.core.api.WorldView;

/** Cover first; with none in reach, back off instead (rather than standing in the open). */
public final class CoverOrBackOff implements Task {
    private final CoverTask cover;
    private final Task backOff;
    private boolean backingOff;

    public CoverOrBackOff(CoverTask cover, Task backOff) {
        this.cover = cover;
        this.backOff = backOff;
    }

    @Override
    public Status tick(WorldView world) {
        if (!backingOff) {
            Status s = cover.tick(world);
            if (s != Status.FAILED) return s;
            backingOff = true;
        }
        return backOff.tick(world);
    }

    @Override public void cancel() { cover.cancel(); backOff.cancel(); }
    @Override public String failure() { return backingOff ? backOff.failure() : cover.failure(); }
    @Override public String describe() {
        return backingOff ? backOff.describe() + " (" + cover.failure() + ")" : cover.describe();
    }
}
