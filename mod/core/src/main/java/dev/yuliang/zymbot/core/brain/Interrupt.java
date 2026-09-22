package dev.yuliang.zymbot.core.brain;

import dev.yuliang.zymbot.core.api.Hands;
import dev.yuliang.zymbot.core.api.WorldView;
import dev.yuliang.zymbot.core.task.Task;

/** A survival rule that pre-empts whatever the bot is doing (eat, flee, shelter...). */
public interface Interrupt {
    String name();

    boolean triggered(WorldView world);

    Task respond(WorldView world, Hands hands);

    /** Human-readable reason, logged and shown by the status command. */
    String why(WorldView world);
}
