package net.caffeinemc.mods.sodium.client.gui;

import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.util.FrameTimeStatistics;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

// This entry is added so that the F3+F6 menu can toggle SODIUM_FPS_PERCENTILES and display Vuldium stats in 26.4+.
public class SodiumFpsPercentilesEntry implements DebugScreenEntry {
    @Override
    public void display(DebugScreenDisplayer displayer, @Nullable Level level, @Nullable LevelChunk clientChunk, @Nullable LevelChunk serverChunk) {
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
            displayer.addPriorityLine(vuldiumLine.toString().trim());
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
                    .append(ns > 0L ? Math.round(1.0e9 / ns) : 0L);
        }

        sb.append(ChatFormatting.GRAY).append(" fps");
        displayer.addPriorityLine(sb.toString());
    }

    @Override
    public boolean isAllowed(boolean reducedDebugInfo) {
        return true;
    }
}
