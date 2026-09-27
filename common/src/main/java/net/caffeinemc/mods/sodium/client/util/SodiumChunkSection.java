package net.caffeinemc.mods.sodium.client.util;

import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;

public class SodiumChunkSection {
    private final SodiumWorldRenderer renderer;
    private final ChunkRenderMatrices matrices;
    private final double x, y, z;

    public SodiumChunkSection(SodiumWorldRenderer renderer, ChunkRenderMatrices matrices, double x, double y, double z) {
        this.renderer = renderer;
        this.matrices = matrices;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public void renderGroup(ChunkSectionLayerGroup group, RenderPass renderPass, GpuSampler sampler, GpuTextureView atlas, boolean renderWireframeTerrain) {
        this.renderer.drawChunkLayer(renderPass, group, this.matrices, this.x, this.y, this.z, sampler, null);
    }
}
