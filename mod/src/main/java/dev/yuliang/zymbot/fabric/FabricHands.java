package dev.yuliang.zymbot.fabric;

import dev.yuliang.zymbot.core.api.Hands;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The brain's hands, done with the real client. Phase 1 adds walking (Baritone) here. */
final class FabricHands implements Hands {
    private static final Logger LOG = LoggerFactory.getLogger("zymbot");
    private final Minecraft mc;

    FabricHands(Minecraft mc) {
        this.mc = mc;
    }

    @Override
    public void respawn() {
        if (mc.player != null) mc.player.respawn();
    }

    @Override
    public void notifyLocal(String message) {
        LOG.info("[zymbot] {}", message);
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("§7[zymbot]§r " + message), false);
    }
}
