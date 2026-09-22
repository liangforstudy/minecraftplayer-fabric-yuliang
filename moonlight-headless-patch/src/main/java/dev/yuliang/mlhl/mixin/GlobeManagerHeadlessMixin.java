package dev.yuliang.mlhl.mixin;

import dev.yuliang.mlhl.Headless;
import net.minecraft.resource.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Supplementaries' GlobeManager.refreshColorsAndTextures only fills DIMENSION_COLORS /
 * SEPIA_COLORS and refreshes globe textures — purely cosmetic. The SpriteUtils fix alone would
 * let it run on placeholder colours; skipping it outright avoids that pointless image work.
 */
@Mixin(targets = "net.mehvahdjukaar.supplementaries.client.GlobeManager", remap = false)
public abstract class GlobeManagerHeadlessMixin {

    @Inject(method = "refreshColorsAndTextures", at = @At("HEAD"), cancellable = true)
    private static void mlhl$skipGlobePaletteWhenHeadless(ResourceManager manager, CallbackInfo ci) {
        if (Headless.isHeadless()) ci.cancel();
    }
}
