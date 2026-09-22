package dev.yuliang.zymbot.core.protocol;

/** Bus message types (BOT_BEHAVIOUR.md → Bus messages). Unknown types are ignored by receivers. */
public final class MessageTypes {
    public static final String HELLO = "HELLO";          // <name> <phase>
    public static final String ROSTER = "ROSTER";
    public static final String READ = "READ";
    public static final String AMEND = "AMEND";
    public static final String GUESS = "GUESS";
    public static final String CLAIM = "CLAIM";
    public static final String DONE = "DONE";
    public static final String ANSWERED = "ANSWERED";    // <msgId of the public request>
    /** <sender name> <SummonTarget> — bots waiting at the title screen join the sender. */
    public static final String SUMMON = "SUMMON";
    /** <name> <seconds left> — knocked out at the envelope's x,z; a MEDIC with stitches can help. */
    public static final String DOWNED = "DOWNED";
    /** <watcher> <bot> <on|off> — that bot /msg's its decisions to the watcher. */
    public static final String WATCH = "WATCH";

    private MessageTypes() {}
}
