package net.caffeinemc.mods.sodium.mixin.features.textures.animations.tracking;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.gui.SodiumOptions;
import net.caffeinemc.mods.sodium.client.render.texture.SpriteContentsExtension;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SpriteContents.AnimationState.class)
public class SpriteContentsTickerMixin {
    @Shadow
    @Final
    private SpriteContents.AnimatedTexture animationInfo;
    @Shadow
    private int frame;
    @Unique
    private SpriteContents parent;

    @Unique
    private boolean hasUploadedAllOnce = false;

    @Unique
    private boolean wasActiveThisTick = false;

    /**
     * @author IMS
     * @reason Replace fragile Shadow
     */
    @Inject(method = "<init>(Lnet/minecraft/client/renderer/texture/SpriteContents;Lnet/minecraft/client/renderer/texture/SpriteContents$AnimatedTexture;Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;[Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;)V", at = @At("RETURN"), require = 0)
    public void assignParent26_3(SpriteContents this$0,
                             SpriteContents.AnimatedTexture animationInfo,
                             Int2ObjectMap<GpuTextureView> frameTexturesByIndex,
                             GpuBufferSlice[] spriteUbosByMip,
                             CallbackInfo ci) {
        this.parent = this$0;
    }

    @Inject(method = "<init>(Lnet/minecraft/client/renderer/texture/SpriteContents$AnimatedTexture;Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;[Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;)V", at = @At("RETURN"), require = 0)
    public void assignParent26_4(SpriteContents.AnimatedTexture animationInfo,
                             Int2ObjectMap<GpuTextureView> frameTexturesByIndex,
                             GpuBufferSlice[] spriteUbosByMip,
                             CallbackInfo ci) {
        try {
            var field = animationInfo.getClass().getDeclaredField("this$0");
            field.setAccessible(true);
            this.parent = (SpriteContents) field.get(animationInfo);
        } catch (Throwable ignored) {
        }
    }

    // We need to copy the value from the parent to retain it for the whole tick, since if we reset it at the end of
    // needsToDraw it would be reset after the first animation frame is finished, but before processing the rest of the
    // frames.
    @Inject(method = "tick", at = @At("HEAD"))
    private void captureActiveState(CallbackInfo ci) {
        if (this.parent != null) {
            SpriteContentsExtension parent = (SpriteContentsExtension) this.parent;
            this.wasActiveThisTick = parent.sodium$isActive();
            parent.sodium$setActive(false);
        }
    }

    @Inject(method = "needsToDraw", at = @At("HEAD"), cancellable = true)
    private void preTick(CallbackInfoReturnable<Boolean> cir) {
        boolean onDemand = SodiumClientMod.options().performance.animateOnlyVisibleTextures;

        if (!this.hasUploadedAllOnce) {
            if (this.frame == this.animationInfo.frames.size() - 1) {
                this.hasUploadedAllOnce = true;
            } else {
                return;
            }
        }

        var anims = SodiumClientMod.options().animations;
        if (!anims.allAnimations || !isAnimationEnabled(anims)) {
            if (this.hasUploadedAllOnce) {
                cir.setReturnValue(false);
                return;
            }
        }

        if (onDemand && !this.wasActiveThisTick) {
            cir.setReturnValue(false);
        }
    }

    @Unique
    private boolean isAnimationEnabled(SodiumOptions.AnimationSettings anims) {
        if (this.parent == null) {
            return true;
        }
        var name = this.parent.name();
        if (name == null) {
            return true;
        }
        String path = name.getPath();
        if (!anims.animatedWater && path.contains("water")) {
            return false;
        }
        if (!anims.animatedLava && path.contains("lava")) {
            return false;
        }
        if (!anims.animatedFire && (path.contains("fire") || path.contains("soul_fire"))) {
            return false;
        }
        if (!anims.animatedPortal && path.contains("portal")) {
            return false;
        }
        if (!anims.animatedRedstone && (path.contains("redstone") || path.contains("sculk"))) {
            return false;
        }
        if (!anims.animatedExplosion && path.contains("explosion")) {
            return false;
        }
        if (!anims.animatedFlame && (path.contains("flame") || path.contains("campfire") || path.contains("lantern"))) {
            return false;
        }
        if (!anims.animatedSmoke && path.contains("smoke")) {
            return false;
        }
        return true;
    }
}
