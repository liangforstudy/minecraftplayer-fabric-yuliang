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

    private MessageTypes() {}
}
