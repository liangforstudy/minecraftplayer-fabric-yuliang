package io.github.liangforstudy.zymbot.core.body;

import io.github.liangforstudy.zymbot.core.api.Hands;
import io.github.liangforstudy.zymbot.core.api.WorldView;
import io.github.liangforstudy.zymbot.core.brain.Interrupt;
import io.github.liangforstudy.zymbot.core.config.ZymbotConfig;
import io.github.liangforstudy.zymbot.core.task.DownedTask;
import io.github.liangforstudy.zymbot.core.task.Task;
import java.util.function.BiConsumer;

/** Interrupt −1, above everything: knocked out (civfabric dbno). */
public final class DownedInterrupt implements Interrupt {
    private final ZymbotConfig config;
    private final Body body;
    private final DownedTask.Help help;
    private final BiConsumer<String, String> log;

    public DownedInterrupt(ZymbotConfig config, Body body, DownedTask.Help help, BiConsumer<String, String> log) {
        this.config = config;
        this.body = body;
        this.help = help;
        this.log = log;
    }

    @Override public String name() { return "downed"; }

    @Override
    public boolean triggered(WorldView world) {
        return world.downedSecondsLeft() >= 0;
    }

    @Override
    public Task respond(WorldView world, Hands hands) {
        return new DownedTask(hands, help, w -> body.humansWithin(w, DownedTask.HUMAN_RANGE),
                Math.max(0, config.downedWaitForHumansSeconds) * 20, log);
    }

    @Override
    public String why(WorldView world) {
        return "bleeding out, " + world.downedSecondsLeft() + "s left";
    }
}
