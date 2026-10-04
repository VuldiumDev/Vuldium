package net.caffeinemc.mods.sodium.mixin.core.render.world;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.caffeinemc.mods.sodium.client.gpu.device.vulkan.VulkanContextBridge;
import net.caffeinemc.mods.sodium.client.render.VuldiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.entity.VuldiumBlockEntityBatcher;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin внедрения для подавления ванильной диспетчеризации слоев чанков
 * и делегирования координации конвейера в VuldiumWorldRenderer.
 *
 * Архитектурные задачи:
 * 1. Перехват рендеринга слоев чанков (renderGroup: OPAQUE и TRANSLUCENT) с передачей управления в Vuldium.
 * 2. Интеграция Z-буфера: сброс батчера геометрии динамических блоков (VuldiumBlockEntityBatcher)
 *    сразу после завершения непрозрачных объектов (Opaque Pass) с включёнными depthTest и depthWrite.
 * 3. Сохранение полной совместимости с диспетчеризацией мобов, игроков и частиц (ParticleManager).
 */
@Mixin(LevelRenderer.class)
public abstract class MixinWorldRenderer {

    /**
     * Перехватывает вызов chunkSectionsToRender.renderGroup(...) и передает управление
     * в VuldiumWorldRenderer, гарантируя правильную последовательность фаз и барьеров Vulkan.
     */
    @WrapOperation(
            method = "lambda$addMainPass$0",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/textures/GpuSampler;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Z)V"
            ),
            require = 0
    )
    private void redirectChunkRenderGroup(
            ChunkSectionsToRender instance,
            ChunkSectionLayerGroup group,
            RenderPass renderPass,
            GpuSampler sampler,
            GpuTextureView atlas,
            boolean renderWireframeTerrain,
            Operation<Void> original
    ) {
        if (!VulkanContextBridge.isVulkanBackend()) {
            original.call(instance, group, renderPass, sampler, atlas, renderWireframeTerrain);
            return;
        }

        VuldiumWorldRenderer vuldium = VuldiumWorldRenderer.getInstanceNullable();
        if (vuldium != null) {
            // Маршрутизация отрисовки слоя в VuldiumWorldRenderer (Opaque / Translucent)
            if (group == ChunkSectionLayerGroup.OPAQUE) {
                vuldium.onRenderOpaqueGroup(renderPass, null, 0, 0, 0);
                return;
            } else if (group == ChunkSectionLayerGroup.TRANSLUCENT) {
                vuldium.onRenderTranslucentGroup(renderPass, null, 0, 0, 0);
                return;
            }
        }

        original.call(instance, group, renderPass, sampler, atlas, renderWireframeTerrain);
    }

    /**
     * Перехватывает завершение фазы твердых объектов (executeSolid: сущности, мобы)
     * и выполняет пакетный сброс буферизованных динамических блоков (VuldiumBlockEntityBatcher).
     */
    @Inject(
            method = "lambda$addMainPass$0",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/feature/FeatureFrame;executeSolid(Lcom/mojang/renderpearl/api/commands/RenderPass;)V",
                    shift = At.Shift.AFTER
            ),
            require = 0
    )
    private void onAfterSolidFeatures(CallbackInfo ci, @Local RenderPass renderPass) {
        if (!VulkanContextBridge.isVulkanBackend()) {
            return;
        }

        VuldiumWorldRenderer vuldium = VuldiumWorldRenderer.getInstanceNullable();
        if (vuldium != null) {
            VuldiumBlockEntityBatcher batcher = vuldium.getBlockEntityBatcher();
            if (batcher != null && batcher.getVertexCount() > 0) {
                // Сброс геометрии динамических блоков в Z-буфер
                VkCommandBuffer cmd = VulkanContextBridge.extractCommandBuffer(renderPass);
                if (cmd != null) {
                    batcher.end();
                    batcher.flush(cmd);
                    vuldium.getSync2().barrierEntitiesToTranslucent(cmd);
                }
            }
        }
    }
}
