package dev.yuliang.zymbot.core.brain;

import dev.yuliang.zymbot.core.api.Hands;
import dev.yuliang.zymbot.core.api.WorldView;
import dev.yuliang.zymbot.core.task.Task;
import java.util.function.BiFunction;

/**
 * A goal worth working toward when nothing urgent is happening — a rung of the milestone ladder,
 * or an order from a player. {@link #start} may be called again after an interrupt, so it builds a
 * fresh task each time.
 */
public interface Objective {
    String name();

    Task start(WorldView world, Hands hands);

    String why();

    static Objective of(String name, String why, BiFunction<WorldView, Hands, Task> start) {
        return new Objective() {
            public String name() { return name; }
            public Task start(WorldView world, Hands hands) { return start.apply(world, hands); }
            public String why() { return why; }
        };
    }
}
