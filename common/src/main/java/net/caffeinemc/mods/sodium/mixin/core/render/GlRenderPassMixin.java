package net.caffeinemc.mods.sodium.mixin.core.render;

import com.mojang.blaze3d.opengl.GlRenderPipeline;
import net.caffeinemc.mods.sodium.client.gpu.device.context.SodiumGlRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(targets = "com.mojang.blaze3d.opengl.GlRenderPass")
public abstract class GlRenderPassMixin implements SodiumGlRenderPass {
    @Shadow
    protected GlRenderPipeline pipeline;

    @Override
    public GlRenderPipeline sodium$getPipeline() {
        return this.pipeline;
    }
}
