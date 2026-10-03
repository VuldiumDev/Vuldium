package net.caffeinemc.mods.sodium.mixin.features.render.world.clouds;

import net.minecraft.client.renderer.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CloudRenderer.class)
public abstract class CloudRendererMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void sodium$cancelClouds(int color, net.minecraft.client.CloudStatus status, float cloudHeight, int cloudDistance, net.minecraft.world.phys.Vec3 cameraPos, long ticks, float partialTick, CallbackInfo ci) {
        if (!net.caffeinemc.mods.sodium.client.SodiumClientMod.options().extra.clouds) {
            ci.cancel();
        }
    }

    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private float sodium$modifyCloudHeight(float cloudHeight) {
        int customHeight = net.caffeinemc.mods.sodium.client.SodiumClientMod.options().extra.cloudHeight;
        if (customHeight != 192) {
            return (float) customHeight;
        }
        return cloudHeight;
    }
}
