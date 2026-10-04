package net.caffeinemc.mods.sodium.mixin.features.gui.hooks.debug;

import com.llamalad7.mixinextras.sugar.Local;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.util.FrameTimeStatistics;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(DebugScreenOverlay.class)
public class DebugScreenOverlayInsertMixin {
    @Inject(
            method = "extractRenderState",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/DebugScreenOverlay;extractLines(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Ljava/util/List;Z)V",
                    ordinal = 0)
    )
    private void sodium$insertFpsPercentiles(GuiGraphicsExtractor graphics,
                                             CallbackInfo ci,
                                             @Local(ordinal = 0) List<String> leftLines) {
        int insertAt = 0;
        for (int i = 0; i < leftLines.size(); i++) {
            String line = leftLines.get(i);
            if (line != null && line.contains(" fps T:")) {
                insertAt = i + 1;
                break;
            }
        }

        var vuldiumOpts = SodiumClientMod.options().vuldium;
        StringBuilder vuldiumLine = new StringBuilder();
        vuldiumLine.append(ChatFormatting.GOLD).append("[Vuldium Vulkan] ").append(ChatFormatting.RESET);
        boolean hasActiveFeatures = false;

        if (vuldiumOpts.superResolution != net.caffeinemc.mods.sodium.client.render.chunk.vulkan.upscale.UpscaleQuality.NATIVE) {
            vuldiumLine.append(ChatFormatting.GRAY).append("Upscale: ").append(ChatFormatting.GREEN).append(vuldiumOpts.superResolution.getDisplayName())
                    .append(ChatFormatting.GRAY).append(" [").append(ChatFormatting.YELLOW).append(vuldiumOpts.upscaler.getDisplayName()).append(ChatFormatting.GRAY).append("] ");
            hasActiveFeatures = true;
        }
        if (vuldiumOpts.frameGen.isEnabled()) {
            vuldiumLine.append(ChatFormatting.GRAY).append("FrameGen: ").append(ChatFormatting.GOLD).append(vuldiumOpts.frameGen.getDisplayName()).append(" ");
            hasActiveFeatures = true;
        }
        if (vuldiumOpts.rayTracing != net.caffeinemc.mods.sodium.client.render.chunk.vulkan.rt.RayTracingMode.OFF) {
            vuldiumLine.append(ChatFormatting.GRAY).append("RT: ").append(ChatFormatting.LIGHT_PURPLE).append(vuldiumOpts.rayTracing.getDisplayName()).append(" ");
            hasActiveFeatures = true;
        }
        if (vuldiumOpts.meshShaders) {
            vuldiumLine.append(ChatFormatting.GRAY).append("Meshlets: ").append(ChatFormatting.AQUA).append("ON ");
            hasActiveFeatures = true;
        }
        if (vuldiumOpts.hiZOcclusionCulling) {
            vuldiumLine.append(ChatFormatting.GRAY).append("Hi-Z: ").append(ChatFormatting.GREEN).append("ON ");
            hasActiveFeatures = true;
        }
        if (vuldiumOpts.lowLatency.isEnabled()) {
            vuldiumLine.append(ChatFormatting.GRAY).append("Latency: ").append(ChatFormatting.YELLOW).append(vuldiumOpts.lowLatency.getDisplayName()).append(" ");
            hasActiveFeatures = true;
        }
        if (vuldiumOpts.vrsMode.isEnabled()) {
            vuldiumLine.append(ChatFormatting.GRAY).append("VRS: ").append(ChatFormatting.DARK_GREEN).append(vuldiumOpts.vrsMode.getDisplayName()).append(" ");
            hasActiveFeatures = true;
        }
        if (vuldiumOpts.asyncComputeParticles) {
            vuldiumLine.append(ChatFormatting.GRAY).append("Async: ").append(ChatFormatting.BLUE).append("ON ");
            hasActiveFeatures = true;
        }
        if (vuldiumOpts.virtualTexturing) {
            vuldiumLine.append(ChatFormatting.GRAY).append("Sparse: ").append(ChatFormatting.WHITE).append("ON ");
            hasActiveFeatures = true;
        }

        if (hasActiveFeatures) {
            leftLines.add(insertAt, vuldiumLine.toString().trim());
            insertAt++;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.debugEntries.isCurrentlyEnabled(SodiumClientMod.SODIUM_FPS_PERCENTILES)) {
            return;
        }
        var results = FrameTimeStatistics.INSTANCE.get();
        if (results == null || results.isEmpty()) {
            return;
        }

        var sb = new StringBuilder();
        for (var entry : results.reference2LongEntrySet()) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            long ns = entry.getLongValue();
            sb.append(ChatFormatting.GRAY)
                    .append(entry.getKey().name()).append('=')
                    .append(ChatFormatting.RESET)
                    .append(sodium$nanosToFps(ns));
        }

        sb.append(ChatFormatting.GRAY).append(" fps");

        leftLines.add(insertAt, sb.toString());
    }

    @Unique
    private static long sodium$nanosToFps(long ns) {
        return ns > 0L ? Math.round(1.0e9 / ns) : 0L;
    }
}
