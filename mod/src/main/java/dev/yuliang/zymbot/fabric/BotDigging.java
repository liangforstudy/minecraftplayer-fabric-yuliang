package dev.yuliang.zymbot.fabric;

/**
 * Set while the bot's hands are digging a block (FabricHands.mine). The client's own key handling
 * calls {@code stopDestroyBlock} on every tick the attack key isn't held (Minecraft.continueAttack),
 * which would reset the bot's progress each tick; mixin.ContinueAttackMixin skips that one call
 * while this is set — and only when no human is holding attack.
 */
public final class BotDigging {
    private BotDigging() {}

    public static volatile boolean active;
}
