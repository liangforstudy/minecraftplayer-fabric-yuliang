package io.github.liangforstudy.zymbot.fabric;

import net.minecraft.client.Minecraft;

/** The mixin's way in to the package-private {@link Tracers}. */
public final class TracerHooks {
    private TracerHooks() {}

    public static void emit(Minecraft mc, float partialTick) {
        try {
            Tracers.emit(mc, partialTick);
        } catch (RuntimeException e) {                      // a drawing bug must never take the frame down
            org.slf4j.LoggerFactory.getLogger("zymbot").warn("[zymbot] tracers: {}", e.toString());
        }
    }
}
