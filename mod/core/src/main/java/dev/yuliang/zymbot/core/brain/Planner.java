package dev.yuliang.zymbot.core.brain;

import dev.yuliang.zymbot.core.api.WorldView;
import java.util.Optional;

/** Picks the best objective right now. Phase 0 has none, so the bot idles — honestly. */
public interface Planner {
    Optional<Objective> best(WorldView world);

    Planner EMPTY = world -> Optional.empty();
}
