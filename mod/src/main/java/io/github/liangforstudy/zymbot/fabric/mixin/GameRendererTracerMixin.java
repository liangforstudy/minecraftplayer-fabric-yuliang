package io.github.liangforstudy.zymbot.fabric.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tracers (issue #7): this frame's lines, added right after the camera is placed, so they start at
 * this frame's crosshair (as Chest Tracker does). 26.2 places the camera while it extracts the frame.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererTracerMixin {
    //? if >=26.2 {
    /*@Inject(method = "extract", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/client/renderer/GameRenderer;extractCamera(Lnet/minecraft/client/DeltaTracker;FF)V"))
    private void zymbot$tracers(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
    *///?} else {
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void zymbot$tracers(DeltaTracker deltaTracker, CallbackInfo ci) {
    //?}
        io.github.liangforstudy.zymbot.fabric.TracerHooks.emit(Minecraft.getInstance(), deltaTracker.getGameTimeDeltaPartialTick(false));
    }
}
