package net.caffeinemc.mods.sodium.client.compat;

import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.textures.TextureFormat;

/**
 * Cross-version compatibility helper for RenderPipeline.Builder.
 *
 * In MC 26.1:
 *   - RenderPipeline.Builder.withSampler(String) / withUniform(String, UniformType) / withUniform(String, UniformType, TextureFormat) are used directly
 *   - withBindGroupLayout(BindGroupLayout) does NOT exist
 *
 * In MC 26.2+:
 *   - withSampler / withUniform do NOT exist on the builder
 *   - withBindGroupLayout(BindGroupLayout) is used instead
 *
 * This helper applies a BindGroupLayout to a RenderPipeline.Builder using whichever
 * API is appropriate for the current MC version.
 */
public final class RenderPipelineBuilderCompat {

    /**
     * Applies the given BindGroupLayout to a RenderPipeline.Builder.
     *
     * In 26.1: iterates over the layout's samplers and uniforms and calls
     *   withSampler/withUniform on the builder directly.
     * In 26.2+: calls withBindGroupLayout on the real builder.
     *
     * @param builder The pipeline builder to modify
     * @param layout The bind group layout containing samplers and uniforms
     * @return The modified builder (for chaining)
     */
    public static RenderPipeline.Builder applyLayout(RenderPipeline.Builder builder, BindGroupLayout layout) {
        // In MC 26.1, BindGroupLayout is our shim (this source file's package),
        // which stores samplers/uniforms as lists. We apply them with withSampler/withUniform.
        for (String sampler : layout.samplers) {
            builder = builder.withSampler(sampler);
        }
        for (BindGroupLayout.UniformEntry uniform : layout.uniforms) {
            if (uniform.format() != null) {
                builder = builder.withUniform(uniform.name(), (UniformType) uniform.type(), (TextureFormat) uniform.format());
            } else {
                builder = builder.withUniform(uniform.name(), (UniformType) uniform.type());
            }
        }
        return builder;
    }
}
