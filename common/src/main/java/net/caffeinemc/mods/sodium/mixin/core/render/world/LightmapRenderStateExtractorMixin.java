package net.caffeinemc.mods.sodium.mixin.core.render.world;

import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Objects;

@Mixin(LightmapRenderStateExtractor.class)
public class LightmapRenderStateExtractorMixin {
    @Unique
    private float vuldium$prevBlockFactor = -1.0F;
    @Unique
    private float vuldium$prevSkyFactor = -1.0F;
    @Unique
    private float vuldium$prevBrightness = -1.0F;
    @Unique
    private float vuldium$prevNightVision = -1.0F;
    @Unique
    private float vuldium$prevDarkness = -1.0F;
    @Unique
    private Vector3fc vuldium$prevBlockLightTint;
    @Unique
    private Vector3fc vuldium$prevSkyLightColor;
    @Unique
    private Vector3fc vuldium$prevAmbientColor;

    @Inject(method = "extract", at = @At("TAIL"))
    private void vuldium$skipRedundantLightmapUpdates(LightmapRenderState state, float partialTicks, CallbackInfo ci) {
        float delta = Math.abs(state.blockFactor - this.vuldium$prevBlockFactor)
                + Math.abs(state.skyFactor - this.vuldium$prevSkyFactor)
                + Math.abs(state.brightness - this.vuldium$prevBrightness)
                + Math.abs(state.nightVisionEffectIntensity - this.vuldium$prevNightVision)
                + Math.abs(state.darknessEffectScale - this.vuldium$prevDarkness);

        if (delta < 1e-4F
                && Objects.equals(state.blockLightTint, this.vuldium$prevBlockLightTint)
                && Objects.equals(state.skyLightColor, this.vuldium$prevSkyLightColor)
                && Objects.equals(state.ambientColor, this.vuldium$prevAmbientColor)) {
            state.needsUpdate = false;
        } else {
            state.needsUpdate = true;
            this.vuldium$prevBlockFactor = state.blockFactor;
            this.vuldium$prevSkyFactor = state.skyFactor;
            this.vuldium$prevBrightness = state.brightness;
            this.vuldium$prevNightVision = state.nightVisionEffectIntensity;
            this.vuldium$prevDarkness = state.darknessEffectScale;
            this.vuldium$prevBlockLightTint = state.blockLightTint;
            this.vuldium$prevSkyLightColor = state.skyLightColor;
            this.vuldium$prevAmbientColor = state.ambientColor;
        }
    }
}
