package net.caffeinemc.mods.sodium.mixin.features.render;

import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FogRenderer.class)
public class FogRendererMixin {
    @Inject(method = "setupFog", at = @At("RETURN"))
    private void sodium$modifyFog(Camera camera, int viewDistance, DeltaTracker deltaTracker, float farPlaneDistance, ClientLevel level, CallbackInfoReturnable<FogData> cir) {
        FogData data = cir.getReturnValue();
        if (data == null) {
            return;
        }

        var rOpts = SodiumClientMod.options().render;
        if (!rOpts.fog) {
            data.environmentalStart = 100000.0f;
            data.environmentalEnd = 100000.0f;
            data.renderDistanceStart = 100000.0f;
            data.renderDistanceEnd = 100000.0f;
        } else if (rOpts.fogDistance != 100) {
            float ratio = (float) rOpts.fogDistance / 100.0f;
            data.renderDistanceStart *= ratio;
            data.renderDistanceEnd *= ratio;
        }
    }
}
