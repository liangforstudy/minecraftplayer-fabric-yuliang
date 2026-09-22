package dev.yuliang.zymbot.core.brain;

import dev.yuliang.zymbot.core.api.Hands;
import dev.yuliang.zymbot.core.api.WorldView;
import dev.yuliang.zymbot.core.task.Task;

/** A goal worth working toward when nothing urgent is happening — a rung of the milestone ladder. */
public interface Objective {
    String name();

    Task start(WorldView world, Hands hands);

    String why();
}
