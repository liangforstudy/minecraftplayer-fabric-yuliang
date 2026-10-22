package io.github.liangforstudy.zymbot.core.brain;

import io.github.liangforstudy.zymbot.core.api.Hands;
import io.github.liangforstudy.zymbot.core.api.WorldView;
import io.github.liangforstudy.zymbot.core.task.Task;

/** A survival rule that pre-empts whatever the bot is doing (eat, flee, shelter...). */
public interface Interrupt {
    String name();

    boolean triggered(WorldView world);

    Task respond(WorldView world, Hands hands);

    /** Human-readable reason, logged and shown by the status command. */
    String why(WorldView world);
}
