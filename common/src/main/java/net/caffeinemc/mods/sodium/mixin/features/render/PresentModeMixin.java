package net.caffeinemc.mods.sodium.mixin.features.render;

import com.mojang.renderpearl.api.device.GpuSurface;
import net.caffeinemc.mods.sodium.client.render.chunk.vulkan.pacing.SodkamFramePacing;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;

@Mixin(value = GpuSurface.PresentMode.class, remap = false)
public abstract class PresentModeMixin {
    /**
     * Переопределяет логику выбора режима вывода поверхности:
     * 1. VK_PRESENT_MODE_MAILBOX_KHR (тройная буферизация без тиринга)
     * 2. VK_PRESENT_MODE_FIFO_RELAXED_KHR
     * 3. VK_PRESENT_MODE_FIFO_KHR
     */
    @Inject(method = "getSupportedVsyncMode", at = @At("HEAD"), cancellable = true, remap = false)
    private static void selectOptimalPresentMode(
            Collection<GpuSurface.PresentMode> supported,
            boolean vsync,
            CallbackInfoReturnable<GpuSurface.PresentMode> cir
    ) {
        cir.setReturnValue(SodkamFramePacing.selectOptimalPresentMode(supported, vsync));
    }
}
