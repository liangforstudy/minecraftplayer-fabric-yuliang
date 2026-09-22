package dev.yuliang.zymbot.core.api;

/**
 * Walking, behind our own interface (PHASE1.md P1-4). Baritone is the first implementation; a
 * port or a custom scout walker can replace it without the brain noticing. Tasks poll
 * {@link #busy()} and check arrival themselves.
 */
public interface PathProvider {
    /** "Baritone 1.11.3", or why there's no pathfinder. */
    String name();

    boolean available();

    /**
     * Start walking. {@code ignoreY}: any height in that column will do. {@code within}: stop
     * once this close (0 = that exact block). {@code swim}: may enter water — only as a last
     * resort, since swimming costs ~85× walking's hunger per block here.
     */
    void goTo(BlockPos target, boolean ignoreY, int within, boolean swim);

    /** Keep near this player until {@link #stop()}. {@code swim} as for {@link #goTo}. */
    void follow(String playerName, boolean swim);

    void stop();

    /** Still computing or walking a path. */
    boolean busy();

    PathProvider NONE = new PathProvider() {
        public String name() { return "none — install Baritone to walk"; }
        public boolean available() { return false; }
        public void goTo(BlockPos target, boolean ignoreY, int within, boolean swim) {}
        public void follow(String playerName, boolean swim) {}
        public void stop() {}
        public boolean busy() { return false; }
    };
}
