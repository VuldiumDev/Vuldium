package net.caffeinemc.mods.sodium.mixin.core.render.world;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.render.chunk.vulkan.upscale.SodkamBlitPass;
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
    private RenderTarget sodkam$worldTarget;

    @Unique
    private boolean sodkam$isWorldTargetActive = false;

    @Override
    public FogParameters sodium$getFogParameters() {
        return ((FogStorage) this.fogRenderer).sodium$getFogParameters();
    }

    @WrapOperation(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"))
    private GpuBufferSlice sodium$setProjection(ProjectionMatrixBuffer instance,
                                                Matrix4f projectionMatrix,
                                                Operation<GpuBufferSlice> original) {
        this.projection.set(projectionMatrix);
        return original.call(instance, projectionMatrix);
    }

    @Inject(method = "mainRenderTarget", at = @At("HEAD"), cancellable = true)
    private void sodkam$redirectMainRenderTarget(CallbackInfoReturnable<RenderTarget> cir) {
        if (this.sodkam$isWorldTargetActive && this.sodkam$worldTarget != null) {
            cir.setReturnValue(this.sodkam$worldTarget);
        }
    }

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel()V"))
    private void sodkam$beforeRenderLevel(CallbackInfo ci) {
        var quality = SodiumClientMod.options().sodkam.superResolution;
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

        this.sodkam$ensureWorldTarget(renderWidth, renderHeight);
        this.sodkam$isWorldTargetActive = true;
    }

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel()V",
                    shift = At.Shift.AFTER))
    private void sodkam$afterWorldRender(CallbackInfo ci) {
        if (!this.sodkam$isWorldTargetActive) {
            return;
        }

        this.sodkam$isWorldTargetActive = false;

        if (this.sodkam$worldTarget != null && this.mainRenderTarget != null) {
            SodkamBlitPass.blit(this.sodkam$worldTarget, this.mainRenderTarget);
        }
    }

    @Inject(method = "close", at = @At("RETURN"))
    private void sodkam$onClose(CallbackInfo ci) {
        if (this.sodkam$worldTarget != null) {
            this.sodkam$worldTarget.destroyBuffers();
            this.sodkam$worldTarget = null;
        }
    }

    @Unique
    private void sodkam$ensureWorldTarget(int renderWidth, int renderHeight) {
        GpuFormat colorFmt = this.mainRenderTarget.getColorTexture() != null
                ? this.mainRenderTarget.getColorTexture().getFormat()
                : GpuFormat.RGBA8_UNORM;
        GpuFormat depthFmt = this.mainRenderTarget.getDepthTexture() != null
                ? this.mainRenderTarget.getDepthTexture().getFormat()
                : GpuFormat.D32_FLOAT;

        if (this.sodkam$worldTarget == null) {
            this.sodkam$worldTarget = new TextureTarget(
                    "vuldium_world",
                    renderWidth,
                    renderHeight,
                    true,
                    colorFmt
            );
        } else if (this.sodkam$worldTarget.width != renderWidth || this.sodkam$worldTarget.height != renderHeight) {
            this.sodkam$worldTarget.resize(renderWidth, renderHeight);
        }
    }

    @Override
    public Matrix4fc sodium$getProjectionMatrix() {
        return this.projection;
    }
}
