package dev.yuliang.zymbot.fabric.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The client's own re-use cooldown. While the bot eats it keeps this above zero: otherwise the
 * held use key starts a second bite the moment the first ends, before the bot gets its tick.
 */
@Mixin(Minecraft.class)
public interface MinecraftAccessor {
    @Accessor("rightClickDelay")
    void zymbot$setRightClickDelay(int ticks);
}
