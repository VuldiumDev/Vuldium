package net.caffeinemc.mods.sodium.client.render.chunk.vulkan.upscale;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.gpu.device.vulkan.capabilities.UpscalerType;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Аппаратный проход масштабирования (Upscale Blit Pass) для вывода кадра 3D-мира
 * из пониженного разрешения рендера в нативное разрешение экрана.
 * Динамически применяет выбранный движок апскейлинга (FSR RCAS, DLSS Bicubic, XeSS Detail, Off).
 */
public class SodkamBlitPass {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vuldium/BlitPass");

    public static final RenderPipeline FSR_PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("sodium", "pipeline/sodkam_fsr_rcas"))
            .withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("sodium", "post/sodkam_fsr_rcas"))
            .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .build();

    public static final RenderPipeline DLSS_PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("sodium", "pipeline/sodkam_dlss"))
            .withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("sodium", "post/sodkam_dlss"))
            .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .build();

    public static final RenderPipeline XESS_PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("sodium", "pipeline/sodkam_xess"))
            .withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("sodium", "post/sodkam_xess"))
            .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .build();

    public static void blit(RenderTarget src, RenderTarget dst) {
        if (src == null || dst == null || src.getColorTextureView() == null || dst.getColorTextureView() == null) {
            return;
        }

        CompiledRenderPipeline compiled = null;
        var opts = SodiumClientMod.options().sodkam;
        RenderPipeline targetPipeline = switch (opts.upscaler) {
            case DLSS -> DLSS_PIPELINE;
            case XESS -> XESS_PIPELINE;
            default -> FSR_PIPELINE;
        };

        try {
            compiled = RenderSystem.getCompiledPipeline(targetPipeline);
        } catch (Throwable ignored) {
        }

        if (compiled == null) {
            try {
                compiled = RenderSystem.getCompiledPipeline(RenderPipelines.TRACY_BLIT);
            } catch (Exception e) {
                return;
            }
        }

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        try (RenderPass pass = encoder.createRenderPass(
                () -> "Vuldium Upscale Blit",
                dst.getColorTextureView(),
                Optional.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setPipeline(compiled);
            pass.setUniform("InSampler", src.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.draw(3, 1, 0, 0);
        }
    }
}
