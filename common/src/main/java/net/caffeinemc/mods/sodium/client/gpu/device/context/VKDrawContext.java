package net.caffeinemc.mods.sodium.client.gpu.device.context;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import com.mojang.blaze3d.vulkan.VulkanRenderPipeline;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.mixin.core.render.RenderPassAccessor;
import net.caffeinemc.mods.sodium.mixin.core.render.VulkanRenderPassAccessor;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;

public abstract class VKDrawContext extends DrawContext {
    private VkCommandBuffer commandBuffer;
    private long pipelineLayout;
    private final ByteBuffer pushConstantBuffer = MemoryUtil.memAlloc(DefaultChunkRenderer.PUSH_CONSTANT_RANGE);
    private final long pushConstantAddr = MemoryUtil.memAddress(this.pushConstantBuffer);

    @Override
    public void setContext(RenderPass pass, RenderPipeline pipeline) {
        this.pass = pass;
        if (pass instanceof RenderPassAccessor accessor && accessor.sodium$getBackend() instanceof VulkanRenderPass vkPass) {
            this.commandBuffer = ((VulkanRenderPassAccessor) vkPass).sodium$getCommandBuffer();
            VulkanRenderPipeline vkPipeline = ((VulkanRenderPassAccessor) vkPass).sodium$getPipeline();
            this.pipelineLayout = vkPipeline != null ? vkPipeline.pipelineLayout() : 0L;
        } else {
            this.commandBuffer = null;
            this.pipelineLayout = 0L;
        }
    }

    @Override
    public void pushConstants(float x, float y, float z, int currentTime, int regionId) {
        if (this.commandBuffer != null && this.pipelineLayout != 0L) {
            long addr = this.pushConstantAddr;
            MemoryUtil.memPutFloat(addr, x);
            MemoryUtil.memPutFloat(addr + 4, y);
            MemoryUtil.memPutFloat(addr + 8, z);
            MemoryUtil.memPutInt(addr + 12, currentTime);
            MemoryUtil.memPutInt(addr + 16, regionId);

            VK10.vkCmdPushConstants(this.commandBuffer, this.pipelineLayout, VK10.VK_SHADER_STAGE_ALL_GRAPHICS, 0, this.pushConstantBuffer);
        }
    }

    @Override
    public void delete() {
        MemoryUtil.memFree(this.pushConstantBuffer);
    }
}
