package net.caffeinemc.mods.sodium.mixin.features.details;

import com.mojang.blaze3d.vertex.PoseStack;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.world.level.MoonPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SkyRenderer.class)
public class SkyRendererMixin {
    // NOTE: In Minecraft 26.2, cancelling renderSkyDisc leaves the framebuffer with the raw fog clear color
    // (solid red-brown during sunrise/sunset, solid dark slate at night), creating a severe visual glitch.
    // The sky disc is a lightweight 16-vertex mesh that must always be drawn.

    @Inject(method = "renderSunriseAndSunset", at = @At("HEAD"), cancellable = true, require = 0)
    private void sodium$cancelSunrise(PoseStack poseStack, float angle, int color, CallbackInfo ci) {
        if (!SodiumClientMod.options().details.sky) {
            ci.cancel();
        }
    }

    @Inject(method = "renderStars", at = @At("HEAD"), cancellable = true, require = 0)
    private void sodium$cancelStars(float starBrightness, PoseStack poseStack, CallbackInfo ci) {
        if (!SodiumClientMod.options().details.stars) {
            ci.cancel();
        }
    }

    @Inject(method = "renderSun", at = @At("HEAD"), cancellable = true, require = 0)
    private void sodium$cancelSun(float rainLevel, PoseStack poseStack, CallbackInfo ci) {
        if (!SodiumClientMod.options().details.sunMoon) {
            ci.cancel();
        }
    }

    @Inject(method = "renderMoon", at = @At("HEAD"), cancellable = true, require = 0)
    private void sodium$cancelMoon(MoonPhase moonPhase, float rainLevel, PoseStack poseStack, CallbackInfo ci) {
        if (!SodiumClientMod.options().details.sunMoon) {
            ci.cancel();
        }
    }
}
