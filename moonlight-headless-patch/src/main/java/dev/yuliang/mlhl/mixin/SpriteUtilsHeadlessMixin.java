package dev.yuliang.mlhl.mixin;

import dev.yuliang.mlhl.Headless;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Moonlight's SpriteUtils.parsePaletteStrip reads pixel data out of a texture and throws when it
 * finds fewer colours than the caller asked for:
 *
 *     Image at <id> has too few colors! Expected at least N and got 0
 *
 * Under HeadlessMC, LWJGL and STB are stubbed, so every image decodes empty and this throws for
 * any mod that calls it during resource reload. Supplementaries hits it twice (globe palettes and
 * bubble block colours), and any other Moonlight-based mod doing palette work hits it too.
 *
 * When headless, return a grey ramp of exactly the requested length. Callers use these as ARGB
 * tint values; a headless client never renders, so the values only need to be well-formed and
 * distinct enough that anything interpolating over them behaves.
 */
@Mixin(targets = "net.mehvahdjukaar.moonlight.api.resources.textures.SpriteUtils", remap = false)
public abstract class SpriteUtilsHeadlessMixin {

    @Inject(method = "parsePaletteStrip", at = @At("HEAD"), cancellable = true)
    private static void mlhl$fallbackPaletteWhenHeadless(
            ResourceManager manager, Identifier texture, int expected,
            CallbackInfoReturnable<List<Integer>> cir) {
        if (!Headless.isHeadless()) return;

        int n = Math.max(expected, 1);
        List<Integer> ramp = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int v = (n == 1) ? 128 : 32 + (191 * i) / (n - 1);
            ramp.add(0xFF000000 | (v << 16) | (v << 8) | v);
        }
        cir.setReturnValue(ramp);
    }
}
