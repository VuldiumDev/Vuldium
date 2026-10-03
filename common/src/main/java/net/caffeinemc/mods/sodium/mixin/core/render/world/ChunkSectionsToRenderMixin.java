package net.caffeinemc.mods.sodium.mixin.core.render.world;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.caffeinemc.mods.sodium.client.util.SodiumChunkSection;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkSectionsToRender.class)
public abstract class ChunkSectionsToRenderMixin {
    @Inject(method = "renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/textures/GpuSampler;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender$ImprovedFogTextures;Z)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void sodium$renderGroup264(ChunkSectionLayerGroup group, RenderPass renderPass, GpuSampler sampler, GpuTextureView atlas, @Coerce Object fogTextures, boolean renderWireframeTerrain, CallbackInfo ci) {
        if ((Object) this instanceof SodiumChunkSection sodiumChunkSection) {
            sodiumChunkSection.renderGroup(group, renderPass, sampler, atlas, renderWireframeTerrain);
            ci.cancel();
        }
    }

    @Inject(method = "renderLayers", at = @At("HEAD"), cancellable = true, require = 0)
    private void sodium$renderLayers(CallbackInfo ci) {
        if ((Object) this instanceof SodiumChunkSection) {
            ci.cancel();
        }
    }
}
