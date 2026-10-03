package net.caffeinemc.mods.sodium.mixin.features.details;

import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WeatherEffectRenderer.class)
public class WeatherEffectRendererMixin {
    @Dynamic
    @Inject(method = "render(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/client/renderer/state/level/WeatherRenderState;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void sodium$cancelWeatherLegacy(Vec3 cameraPos, WeatherRenderState state, CallbackInfo ci) {
        if (!SodiumClientMod.options().details.weather) {
            ci.cancel();
        }
    }

    @Dynamic
    @Inject(method = "render(Lnet/minecraft/client/renderer/state/level/WeatherRenderState;Lcom/mojang/renderpearl/api/commands/RenderPass;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void sodium$cancelWeather26_4(WeatherRenderState state, com.mojang.renderpearl.api.commands.RenderPass renderPass, CallbackInfo ci) {
        if (!SodiumClientMod.options().details.weather) {
            ci.cancel();
        }
    }
}
