package com.mojang.blaze3d.vulkan;

import com.mojang.blaze3d.systems.GpuDeviceBackend;
import org.lwjgl.vulkan.VkDevice;

public abstract class VulkanDevice implements GpuDeviceBackend {
    public VkDevice vkDevice() {
        return null;
    }

    public VulkanQueue graphicsQueue() {
        return null;
    }

    public VulkanQueue transferQueue() {
        return null;
    }
}
