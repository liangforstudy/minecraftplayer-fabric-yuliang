package io.github.liangforstudy.zymbot.fabric.mixin;

import io.github.liangforstudy.zymbot.fabric.FreecamHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Freecam (issue #9): the mouse turns the camera, not the player. */
@Mixin(Entity.class)
public abstract class FreecamTurnMixin {
    @Inject(method = "turn", at = @At("HEAD"), cancellable = true)
    private void zymbot$freecamTurn(double dx, double dy, CallbackInfo ci) {
        if ((Object) this == Minecraft.getInstance().player && FreecamHooks.turn(dx, dy)) ci.cancel();
    }
}
