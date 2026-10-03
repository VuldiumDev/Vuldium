package net.caffeinemc.mods.sodium.mixin.features.details;

import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WeatherEffectRenderer.class)
public class WeatherEffectRendererMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void sodium$cancelWeather(Vec3 cameraPos, WeatherRenderState state, CallbackInfo ci) {
        if (!SodiumClientMod.options().details.weather) {
            ci.cancel();
        }
    }
}
