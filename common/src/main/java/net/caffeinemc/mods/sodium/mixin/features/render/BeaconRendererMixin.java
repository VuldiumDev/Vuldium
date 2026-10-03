package net.caffeinemc.mods.sodium.mixin.features.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BeaconRenderer.class)
public class BeaconRendererMixin {
    @Inject(method = "submitBeaconBeam(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/resources/Identifier;FFIIIFF)V", at = @At("HEAD"), cancellable = true, require = 0)
    private static void sodium$cancelBeaconBeam(PoseStack poseStack, SubmitNodeCollector submitNodeCollector, Identifier identifier, float f, float g, int i, int j, int k, float h, float l, CallbackInfo ci) {
        if (!SodiumClientMod.options().render.beaconBeams) {
            ci.cancel();
        }
    }
}
