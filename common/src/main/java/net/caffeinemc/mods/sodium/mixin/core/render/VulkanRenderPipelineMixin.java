package net.caffeinemc.mods.sodium.mixin.core.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vulkan.VulkanRenderPipeline;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.nio.LongBuffer;

@Mixin(VulkanRenderPipeline.class)
public abstract class VulkanRenderPipelineMixin {
    @WrapOperation(
            method = "compile",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/vulkan/VK12;vkCreatePipelineLayout(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkPipelineLayoutCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"
            )
    )
    private static int addPushConstantsToPipelineLayout(VkDevice device,
                                                       VkPipelineLayoutCreateInfo pCreateInfo,
                                                       VkAllocationCallbacks pAllocator,
                                                       LongBuffer pPipelineLayout,
                                                       Operation<Integer> original,
                                                       @Local(argsOnly = true) RenderPipeline pipeline) {
        if (pipeline != null && pipeline.getLocation() != null && "sodium".equals(pipeline.getLocation().getNamespace())) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkPushConstantRange.Buffer ranges = VkPushConstantRange.calloc(1, stack);
                ranges.stageFlags(VK10.VK_SHADER_STAGE_ALL_GRAPHICS);
                ranges.offset(0);
                ranges.size(DefaultChunkRenderer.PUSH_CONSTANT_RANGE);
                pCreateInfo.pPushConstantRanges(ranges);
                return original.call(device, pCreateInfo, pAllocator, pPipelineLayout);
            }
        }
        return original.call(device, pCreateInfo, pAllocator, pPipelineLayout);
    }
}
