package io.github.liangforstudy.zymbot.core.brain;

import io.github.liangforstudy.zymbot.core.api.WorldView;
import java.util.Optional;

/** Picks the best objective right now. Phase 0 has none, so the bot idles — honestly. */
public interface Planner {
    Optional<Objective> best(WorldView world);

    Planner EMPTY = world -> Optional.empty();
}
