package net.caffeinemc.mods.sodium.mixin.core.render;

import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import com.mojang.blaze3d.vulkan.VulkanRenderPipeline;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(VulkanRenderPass.class)
public interface VulkanRenderPassAccessor {
    @Accessor("commandBuffer")
    VkCommandBuffer sodium$getCommandBuffer();

    @Accessor("pipeline")
    VulkanRenderPipeline sodium$getPipeline();
}
