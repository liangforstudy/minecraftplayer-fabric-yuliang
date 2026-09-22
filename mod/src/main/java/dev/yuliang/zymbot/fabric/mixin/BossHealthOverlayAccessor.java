package dev.yuliang.zymbot.fabric.mixin;

import java.util.Map;
import java.util.UUID;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The boss bars on screen — civfabric shows "Bleeding Out" and "Being Revived" there. */
@Mixin(BossHealthOverlay.class)
public interface BossHealthOverlayAccessor {
    @Accessor("events")
    Map<UUID, LerpingBossEvent> zymbot$events();
}
