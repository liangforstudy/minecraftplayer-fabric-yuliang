package io.github.liangforstudy.zymbot.fabric.mixin;

import io.github.liangforstudy.zymbot.fabric.ZymbotClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Each block action (break, place, use) takes a new sequence number here, and the server's block-change
 * ack ends them "up to n" here, on the game thread: the gap is the action ping (AckMeter, probe ack).
 */
@Mixin(BlockStatePredictionHandler.class)
public abstract class BlockAckMixin {
    @Inject(method = "startPredicting", at = @At("RETURN"))
    private void zymbot$sent(CallbackInfoReturnable<BlockStatePredictionHandler> cir) {
        ZymbotClient.ACKS.sent(((BlockStatePredictionHandler) (Object) this).currentSequence(), System.nanoTime());
    }

    @Inject(method = "endPredictionsUpTo", at = @At("HEAD"))
    private void zymbot$acked(int sequence, ClientLevel level, CallbackInfo ci) {
        ZymbotClient.ACKS.acked(sequence, System.nanoTime());
    }
}
