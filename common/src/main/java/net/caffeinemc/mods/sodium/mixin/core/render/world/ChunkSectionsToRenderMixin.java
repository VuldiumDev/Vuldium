package net.caffeinemc.mods.sodium.mixin.core.render.world;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.util.SodiumChunkSection;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.OptionalDouble;

@Mixin(ChunkSectionsToRender.class)
public class ChunkSectionsToRenderMixin implements SodiumChunkSection {
    @Unique
    private SodiumWorldRenderer sodium$renderer;
    @Unique
    private ChunkRenderMatrices sodium$matrices;
    @Unique
    private double sodium$x, sodium$y, sodium$z;

    @Override
    public void sodium$setRendering(SodiumWorldRenderer renderer, ChunkRenderMatrices matrices, double x, double y, double z) {
        this.sodium$renderer = renderer;
        this.sodium$matrices = matrices;
        this.sodium$x = x;
        this.sodium$y = y;
        this.sodium$z = z;
    }

    @Inject(method = "renderGroup", at = @At("HEAD"), cancellable = true)
    private void onRenderGroup(ChunkSectionLayerGroup group, GpuSampler sampler, CallbackInfo ci) {
        if (this.sodium$renderer != null) {
            ci.cancel();
            RenderTarget target = group.outputTarget();
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            try (RenderPass pass = encoder.createRenderPass(
                    () -> "Terrain (" + group.name() + ")",
                    target.getColorTextureView(),
                    Optional.empty(),
                    target.getDepthTextureView(),
                    OptionalDouble.empty())) {
                RenderSystem.bindDefaultUniforms(pass);
                this.sodium$renderer.drawChunkLayer(pass, group, this.sodium$matrices, this.sodium$x, this.sodium$y, this.sodium$z, sampler, null);
            }
        }
    }
}
