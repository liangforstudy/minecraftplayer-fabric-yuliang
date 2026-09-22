package dev.yuliang.zymbot.fabric.mixin;

import dev.yuliang.zymbot.fabric.ZymbotClient;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The server sends its world time about once a second; how far it moved per real second is the
 * server's TPS (TpsMeter). TAIL: the first call, on the network thread, bails out before here to
 * re-run on the game thread.
 */
@Mixin(ClientPacketListener.class)
public abstract class ServerTimeMixin {
    @Inject(method = "handleSetTime", at = @At("TAIL"))
    private void zymbot$onServerTime(ClientboundSetTimePacket packet, CallbackInfo ci) {
        ZymbotClient.TPS.onServerTime(packet.getGameTime(), System.nanoTime());
    }
}
