package io.github.liangforstudy.zymbot.fabric.mixin;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 {
/*import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.state.GameRenderState;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
*///?}

/** The game's walking bob and hurt shake, so tracer lines start where the crosshair really is (issue #7). */
@Mixin(GameRenderer.class)
public interface TracerRendererAccessor {
    //? if >=26.2 {
    /*@Accessor("gameRenderState")
    GameRenderState zymbot$gameRenderState();

    @Invoker("bobHurt")
    void zymbot$bobHurt(net.minecraft.client.renderer.state.level.CameraRenderState camera, PoseStack pose);

    @Invoker("bobView")
    void zymbot$bobView(net.minecraft.client.renderer.state.level.CameraRenderState camera, PoseStack pose);
    *///?}
}
