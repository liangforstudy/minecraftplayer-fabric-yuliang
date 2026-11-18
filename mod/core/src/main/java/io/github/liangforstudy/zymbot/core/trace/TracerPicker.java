package io.github.liangforstudy.zymbot.core.trace;

import java.util.Comparator;
import java.util.List;

/**
 * Issue #7: which targets get a tracer line this frame. At most {@code limit} (20 by default), and
 * players fill the slots first (as Meteor's Tracers do; allowed by 9b9t's rules), then everything else,
 * nearest first.
 */
public final class TracerPicker {
    public enum Kind { PLAYER, ENTITY, ITEM, BLOCK }

    /** One candidate: what it is, how far, and whatever the renderer needs to draw it. */
    public record Target<T>(Kind kind, double distance, T what) {}

    private TracerPicker() {}

    public static <T> List<Target<T>> pick(List<Target<T>> candidates, int limit) {
        return candidates.stream()
                .sorted(Comparator.<Target<T>, Boolean>comparing(t -> t.kind() != Kind.PLAYER)
                        .thenComparingDouble(Target::distance))
                .limit(Math.max(0, limit))
                .toList();
    }
}
