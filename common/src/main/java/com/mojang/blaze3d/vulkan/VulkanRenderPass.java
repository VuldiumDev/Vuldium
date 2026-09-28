package com.mojang.blaze3d.vulkan;

import com.mojang.blaze3d.systems.RenderPassBackend;
import org.lwjgl.vulkan.VkCommandBuffer;

public abstract class VulkanRenderPass implements RenderPassBackend {
    private VkCommandBuffer commandBuffer;
}
