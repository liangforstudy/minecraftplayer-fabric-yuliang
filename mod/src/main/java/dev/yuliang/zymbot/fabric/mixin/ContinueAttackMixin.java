package dev.yuliang.zymbot.fabric.mixin;

import dev.yuliang.zymbot.fabric.BotDigging;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * With the attack key up, vanilla's continueAttack(false) aborts any block being dug, every tick.
 * While the bot digs (BotDigging.active) that call is skipped, so the bot's own
 * continueDestroyBlock — the same call a held mouse makes — carries on. A human pressing attack
 * gets vanilla behaviour as always.
 */
@Mixin(Minecraft.class)
public abstract class ContinueAttackMixin {
    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void zymbot$keepBotDigging(boolean leftClick, CallbackInfo ci) {
        if (!leftClick && BotDigging.active) ci.cancel();
    }
}
