package dev.yuliang.zymbot.core.brain;

/** Top-level lifecycle. DISCOVERY is where every start begins (FOUNDATION.md → autostart). */
public enum Phase {
    /** Silent: not announcing, not controlling. */
    STOPPED,
    /** A human's client on the team: announces itself on the bus, never takes control. */
    TEAMMATE,
    /** The bot is in control — first phase of every start. */
    DISCOVERY;

    public boolean controlling() { return this != STOPPED && this != TEAMMATE; }
}
