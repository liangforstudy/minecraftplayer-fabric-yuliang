package io.github.liangforstudy.zymbot.fabric;

/** The mixins' way in to the package-private {@link Freecam}. */
public final class FreecamHooks {
    private FreecamHooks() {}

    /** True when the freecam took the mouse turn (the player doesn't turn). */
    public static boolean turn(double dx, double dy) {
        return Freecam.turn(dx, dy);
    }
}
