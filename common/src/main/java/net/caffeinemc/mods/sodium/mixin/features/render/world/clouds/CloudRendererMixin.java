package net.caffeinemc.mods.sodium.mixin.features.render.world.clouds;

import com.mojang.renderpearl.api.commands.RenderPass;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CloudRenderer.class)
public abstract class CloudRendererMixin {
    // 26.3 render method
    @Inject(method = "render(ILnet/minecraft/client/CloudStatus;FILnet/minecraft/world/phys/Vec3;JF)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void sodium$cancelClouds263(int color, CloudStatus status, float cloudHeight, int cloudDistance, Vec3 cameraPos, long ticks, float partialTick, CallbackInfo ci) {
        if (!SodiumClientMod.options().extra.clouds) {
            ci.cancel();
        }
    }

    // 26.4 render method
    @Inject(method = "render(Lnet/minecraft/client/CloudStatus;Lcom/mojang/renderpearl/api/commands/RenderPass;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void sodium$cancelClouds264(CloudStatus status, RenderPass renderPass, CallbackInfo ci) {
        if (!SodiumClientMod.options().extra.clouds) {
            ci.cancel();
        }
    }

    // 26.4 prepare method (cancel rebuilding mesh if clouds disabled)
    @Inject(method = "prepare(ILnet/minecraft/client/CloudStatus;FILnet/minecraft/world/phys/Vec3;JF)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void sodium$cancelPrepareClouds(int color, CloudStatus status, float cloudHeight, int cloudDistance, Vec3 cameraPos, long ticks, float partialTick, CallbackInfo ci) {
        if (!SodiumClientMod.options().extra.clouds) {
            ci.cancel();
        }
    }

    // 26.3 modify cloud height
    @ModifyVariable(method = "render(ILnet/minecraft/client/CloudStatus;FILnet/minecraft/world/phys/Vec3;JF)V", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private float sodium$modifyCloudHeight263(float cloudHeight) {
        int customHeight = SodiumClientMod.options().extra.cloudHeight;
        if (customHeight != 192) {
            return (float) customHeight;
        }
        return cloudHeight;
    }

    // 26.4 modify cloud height in prepare
    @ModifyVariable(method = "prepare(ILnet/minecraft/client/CloudStatus;FILnet/minecraft/world/phys/Vec3;JF)V", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private float sodium$modifyCloudHeight264(float cloudHeight) {
        int customHeight = SodiumClientMod.options().extra.cloudHeight;
        if (customHeight != 192) {
            return (float) customHeight;
        }
        return cloudHeight;
    }
}
