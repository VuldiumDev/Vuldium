package net.caffeinemc.mods.sodium.mixin.core.render.world;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.render.chunk.vulkan.upscale.VuldiumBlitPass;
import net.caffeinemc.mods.sodium.client.render.chunk.vulkan.upscale.UpscaleQuality;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.caffeinemc.mods.sodium.client.util.FogStorage;
import net.caffeinemc.mods.sodium.client.util.GameRendererStorage;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public class GameRendererMixin implements GameRendererStorage {
    @Shadow
    @Final
    private FogRenderer fogRenderer;

    @Shadow
    @Final
    private RenderTarget mainRenderTarget;

    @Shadow
    @Final
    private GameRenderState gameRenderState;

    @Unique
    private final Matrix4f projection = new Matrix4f();

    @Unique
    private RenderTarget vuldium$worldTarget;

    @Unique
    private boolean vuldium$isWorldTargetActive = false;

    @Override
    public FogParameters sodium$getFogParameters() {
        return ((FogStorage) this.fogRenderer).sodium$getFogParameters();
    }

    @WrapOperation(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"))
    private GpuBufferSlice sodium$setProjection(ProjectionMatrixBuffer instance,
                                                Matrix4f projectionMatrix,
                                                Operation<GpuBufferSlice> original) {
        this.projection.set(projectionMatrix);
        return original.call(instance, projectionMatrix);
    }

    @Inject(method = "mainRenderTarget", at = @At("HEAD"), cancellable = true)
    private void vuldium$redirectMainRenderTarget(CallbackInfoReturnable<RenderTarget> cir) {
        if (this.vuldium$isWorldTargetActive && this.vuldium$worldTarget != null) {
            cir.setReturnValue(this.vuldium$worldTarget);
        }
    }

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel()V"))
    private void vuldium$beforeRenderLevel(CallbackInfo ci) {
        var quality = SodiumClientMod.options().vuldium.superResolution;
        if (quality == UpscaleQuality.NATIVE) {
            return;
        }

        int nativeWidth = this.mainRenderTarget.width;
        int nativeHeight = this.mainRenderTarget.height;
        if (nativeWidth <= 0 || nativeHeight <= 0) {
            return;
        }

        int renderWidth = quality.getRenderWidth(nativeWidth);
        int renderHeight = quality.getRenderHeight(nativeHeight);
        if (renderWidth <= 0 || renderHeight <= 0) {
            return;
        }

        this.vuldium$ensureWorldTarget(renderWidth, renderHeight);
        this.vuldium$isWorldTargetActive = true;
    }

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel()V",
                    shift = At.Shift.AFTER))
    private void vuldium$afterWorldRender(CallbackInfo ci) {
        if (!this.vuldium$isWorldTargetActive) {
            return;
        }

        this.vuldium$isWorldTargetActive = false;

        if (this.vuldium$worldTarget != null && this.mainRenderTarget != null) {
            VuldiumBlitPass.blit(this.vuldium$worldTarget, this.mainRenderTarget);
        }
    }

    @Inject(method = "close", at = @At("RETURN"))
    private void vuldium$onClose(CallbackInfo ci) {
        if (this.vuldium$worldTarget != null) {
            this.vuldium$worldTarget.destroyBuffers();
            this.vuldium$worldTarget = null;
        }
    }

    @Unique
    private void vuldium$ensureWorldTarget(int renderWidth, int renderHeight) {
        if (this.vuldium$worldTarget == null) {
            this.vuldium$worldTarget = new TextureTarget(
                    "vuldium_world",
                    renderWidth,
                    renderHeight,
                    true
            );
        } else if (this.vuldium$worldTarget.width != renderWidth || this.vuldium$worldTarget.height != renderHeight) {
            this.vuldium$worldTarget.resize(renderWidth, renderHeight);
        }
    }

    @Override
    public Matrix4fc sodium$getProjectionMatrix() {
        return this.projection;
    }
}
