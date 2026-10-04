package net.caffeinemc.mods.sodium.client.gpu.device.vulkan.sync;

import net.caffeinemc.mods.sodium.client.gpu.device.vulkan.VuldiumDeviceContext;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkBufferMemoryBarrier2;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Высокопроизводительный диспетчер барьеров синхронизации на базе Vulkan 1.3 Synchronization2.
 * Исключает грубые глобальные блокировки конвейера (Pipeline Bubbles) за счёт точечных подстадий.
 */
public final class VuldiumSync2 {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vuldium/Sync2");

    private final VuldiumDeviceContext context;
    private final boolean supportsSync2;

    public VuldiumSync2(VuldiumDeviceContext context) {
        this.context = context;
        this.supportsSync2 = context.getDeviceCapabilities().apiVersion >= VK13.VK_API_VERSION_1_3 ||
                context.getDeviceCapabilities().VK_KHR_synchronization2;

        LOGGER.info("Vuldium Synchronization2 слой активен: Sync2Native={}", this.supportsSync2);
    }

    /**
     * Барьер памяти: завершение Compute Culling -> чтение косвенных команд Draw Indirect.
     *
     * @param cmdBuf         активный командный буфер
     * @param commandsBuffer буфер структур VkDrawIndexedIndirectCommand
     * @param countBuffer    буфер счетчика видимых вызовов
     */
    public void barrierComputeToIndirect(VkCommandBuffer cmdBuf, long commandsBuffer, long countBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (this.supportsSync2) {
                VkBufferMemoryBarrier2.Buffer barriers = VkBufferMemoryBarrier2.calloc(2, stack);

                // Commands buffer
                barriers.get(0)
                        .sType(VK13.VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER_2)
                        .srcStageMask(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                        .srcAccessMask(VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT)
                        .dstStageMask(VK13.VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT)
                        .dstAccessMask(VK13.VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT)
                        .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .buffer(commandsBuffer)
                        .offset(0L)
                        .size(VK10.VK_WHOLE_SIZE);

                // Count buffer
                barriers.get(1)
                        .sType(VK13.VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER_2)
                        .srcStageMask(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                        .srcAccessMask(VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT)
                        .dstStageMask(VK13.VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT)
                        .dstAccessMask(VK13.VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT)
                        .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .buffer(countBuffer)
                        .offset(0L)
                        .size(Integer.BYTES);

                VkDependencyInfo depInfo = VkDependencyInfo.calloc(stack)
                        .sType(VK13.VK_STRUCTURE_TYPE_DEPENDENCY_INFO)
                        .pBufferMemoryBarriers(barriers);

                VK13.vkCmdPipelineBarrier2(cmdBuf, depInfo);
            } else {
                VkBufferMemoryBarrier.Buffer barriers = VkBufferMemoryBarrier.calloc(2, stack);

                barriers.get(0)
                        .sType(VK10.VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                        .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                        .dstAccessMask(VK10.VK_ACCESS_INDIRECT_COMMAND_READ_BIT)
                        .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .buffer(commandsBuffer)
                        .offset(0L)
                        .size(VK10.VK_WHOLE_SIZE);

                barriers.get(1)
                        .sType(VK10.VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                        .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                        .dstAccessMask(VK10.VK_ACCESS_INDIRECT_COMMAND_READ_BIT)
                        .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .buffer(countBuffer)
                        .offset(0L)
                        .size(Integer.BYTES);

                VK10.vkCmdPipelineBarrier(
                        cmdBuf,
                        VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        VK10.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,
                        0,
                        null,
                        barriers,
                        null
                );
            }
        }
    }

    /**
     * Барьер памяти: завершение DMA-копирования -> чтение атрибутов вершин и индексов графическим пайплайном.
     *
     * @param cmdBuf       активный командный буфер
     * @param deviceBuffer буфер геометрии чанков
     */
    public void barrierTransferToVertexInput(VkCommandBuffer cmdBuf, long deviceBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (this.supportsSync2) {
                VkBufferMemoryBarrier2.Buffer barrier = VkBufferMemoryBarrier2.calloc(1, stack)
                        .sType(VK13.VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER_2)
                        .srcStageMask(VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT)
                        .srcAccessMask(VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT)
                        .dstStageMask(VK13.VK_PIPELINE_STAGE_2_VERTEX_ATTRIBUTE_INPUT_BIT | VK13.VK_PIPELINE_STAGE_2_INDEX_INPUT_BIT)
                        .dstAccessMask(VK13.VK_ACCESS_2_VERTEX_ATTRIBUTE_READ_BIT | VK13.VK_ACCESS_2_INDEX_READ_BIT)
                        .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .buffer(deviceBuffer)
                        .offset(0L)
                        .size(VK10.VK_WHOLE_SIZE);

                VkDependencyInfo depInfo = VkDependencyInfo.calloc(stack)
                        .sType(VK13.VK_STRUCTURE_TYPE_DEPENDENCY_INFO)
                        .pBufferMemoryBarriers(barrier);

                VK13.vkCmdPipelineBarrier2(cmdBuf, depInfo);
            } else {
                VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(1, stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                        .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                        .dstAccessMask(VK10.VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT | VK10.VK_ACCESS_INDEX_READ_BIT)
                        .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .buffer(deviceBuffer)
                        .offset(0L)
                        .size(VK10.VK_WHOLE_SIZE);

                VK10.vkCmdPipelineBarrier(
                        cmdBuf,
                        VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK10.VK_PIPELINE_STAGE_VERTEX_INPUT_BIT,
                        0,
                        null,
                        barrier,
                        null
                );
            }
        }
    }

    /**
     * Барьер памяти: завершение стадии Compute -> чтение следующей стадией Compute (например, EASU -> RCAS).
     */
    public void barrierComputeToCompute(VkCommandBuffer cmdBuf) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (this.supportsSync2) {
                org.lwjgl.vulkan.VkMemoryBarrier2.Buffer barrier = org.lwjgl.vulkan.VkMemoryBarrier2.calloc(1, stack)
                        .sType(VK13.VK_STRUCTURE_TYPE_MEMORY_BARRIER_2)
                        .srcStageMask(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                        .srcAccessMask(VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT)
                        .dstStageMask(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                        .dstAccessMask(VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);

                VkDependencyInfo depInfo = VkDependencyInfo.calloc(stack)
                        .sType(VK13.VK_STRUCTURE_TYPE_DEPENDENCY_INFO)
                        .pMemoryBarriers(barrier);

                VK13.vkCmdPipelineBarrier2(cmdBuf, depInfo);
            } else {
                org.lwjgl.vulkan.VkMemoryBarrier.Buffer barrier = org.lwjgl.vulkan.VkMemoryBarrier.calloc(1, stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                        .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                        .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT);

                VK10.vkCmdPipelineBarrier(
                        cmdBuf,
                        VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        0,
                        barrier,
                        null,
                        null
                );
            }
        }
    }

    /**
     * Барьер памяти: завершение работы апскейлера (Compute) -> подготовка буфера к отрисовке нативного UI (Color Attachment).
     */
    public void barrierComputeToColorAttachment(VkCommandBuffer cmdBuf, long image) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (this.supportsSync2) {
                org.lwjgl.vulkan.VkImageMemoryBarrier2.Buffer barrier = org.lwjgl.vulkan.VkImageMemoryBarrier2.calloc(1, stack)
                        .sType(VK13.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2)
                        .srcStageMask(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                        .srcAccessMask(VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT)
                        .dstStageMask(VK13.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT)
                        .dstAccessMask(VK13.VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT | VK13.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT)
                        .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                        .newLayout(VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                        .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .image(image);

                barrier.subresourceRange()
                        .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0)
                        .levelCount(1)
                        .baseArrayLayer(0)
                        .layerCount(1);

                VkDependencyInfo depInfo = VkDependencyInfo.calloc(stack)
                        .sType(VK13.VK_STRUCTURE_TYPE_DEPENDENCY_INFO)
                        .pImageMemoryBarriers(barrier);

                VK13.vkCmdPipelineBarrier2(cmdBuf, depInfo);
            } else {
                org.lwjgl.vulkan.VkImageMemoryBarrier.Buffer barrier = org.lwjgl.vulkan.VkImageMemoryBarrier.calloc(1, stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                        .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                        .dstAccessMask(VK10.VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                        .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                        .newLayout(VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                        .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .image(image);

                barrier.subresourceRange()
                        .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0)
                        .levelCount(1)
                        .baseArrayLayer(0)
                        .layerCount(1);

                VK10.vkCmdPipelineBarrier(
                        cmdBuf,
                        VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                        0,
                        null,
                        null,
                        barrier
                );
            }
        }
    }

    /**
     * Барьер памяти: завершение записи Opaque Pass -> подготовка к рендерингу сущностей и Block Entities.
     */
    public void barrierOpaqueToEntities(VkCommandBuffer cmdBuf) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (this.supportsSync2) {
                org.lwjgl.vulkan.VkMemoryBarrier2.Buffer barrier = org.lwjgl.vulkan.VkMemoryBarrier2.calloc(1, stack)
                        .sType(VK13.VK_STRUCTURE_TYPE_MEMORY_BARRIER_2)
                        .srcStageMask(VK13.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT | VK13.VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT)
                        .srcAccessMask(VK13.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT | VK13.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
                        .dstStageMask(VK13.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT | VK13.VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT)
                        .dstAccessMask(VK13.VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT | VK13.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT |
                                VK13.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK13.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);

                VkDependencyInfo depInfo = VkDependencyInfo.calloc(stack)
                        .sType(VK13.VK_STRUCTURE_TYPE_DEPENDENCY_INFO)
                        .pMemoryBarriers(barrier);

                VK13.vkCmdPipelineBarrier2(cmdBuf, depInfo);
            } else {
                org.lwjgl.vulkan.VkMemoryBarrier.Buffer barrier = org.lwjgl.vulkan.VkMemoryBarrier.calloc(1, stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                        .srcAccessMask(VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
                        .dstAccessMask(VK10.VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT |
                                VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);

                VK10.vkCmdPipelineBarrier(
                        cmdBuf,
                        VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK10.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                        VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK10.VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT,
                        0,
                        barrier,
                        null,
                        null
                );
            }
        }
    }

    /**
     * Барьер памяти: завершение записи сущностей и Block Entities -> начало Translucent Pass.
     */
    public void barrierEntitiesToTranslucent(VkCommandBuffer cmdBuf) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (this.supportsSync2) {
                org.lwjgl.vulkan.VkMemoryBarrier2.Buffer barrier = org.lwjgl.vulkan.VkMemoryBarrier2.calloc(1, stack)
                        .sType(VK13.VK_STRUCTURE_TYPE_MEMORY_BARRIER_2)
                        .srcStageMask(VK13.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT | VK13.VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT)
                        .srcAccessMask(VK13.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT | VK13.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
                        .dstStageMask(VK13.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT | VK13.VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT)
                        .dstAccessMask(VK13.VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT | VK13.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT |
                                VK13.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT);

                VkDependencyInfo depInfo = VkDependencyInfo.calloc(stack)
                        .sType(VK13.VK_STRUCTURE_TYPE_DEPENDENCY_INFO)
                        .pMemoryBarriers(barrier);

                VK13.vkCmdPipelineBarrier2(cmdBuf, depInfo);
            } else {
                org.lwjgl.vulkan.VkMemoryBarrier.Buffer barrier = org.lwjgl.vulkan.VkMemoryBarrier.calloc(1, stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                        .srcAccessMask(VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
                        .dstAccessMask(VK10.VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT |
                                VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT);

                VK10.vkCmdPipelineBarrier(
                        cmdBuf,
                        VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK10.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                        VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK10.VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT,
                        0,
                        barrier,
                        null,
                        null
                );
            }
        }
    }

    /**
     * Барьер памяти: завершение рендеринга мира (Color Attachment) -> чтение шейдерами апскейлера (Shader Read).
     */
    public void barrierTranslucentToUpscaler(VkCommandBuffer cmdBuf, long image) {
        if (image == VK10.VK_NULL_HANDLE) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (this.supportsSync2) {
                org.lwjgl.vulkan.VkImageMemoryBarrier2.Buffer barrier = org.lwjgl.vulkan.VkImageMemoryBarrier2.calloc(1, stack)
                        .sType(VK13.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2)
                        .srcStageMask(VK13.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT)
                        .srcAccessMask(VK13.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT)
                        .dstStageMask(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                        .dstAccessMask(VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT | VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT)
                        .oldLayout(VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                        .newLayout(VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                        .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .image(image);

                barrier.subresourceRange()
                        .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0)
                        .levelCount(1)
                        .baseArrayLayer(0)
                        .layerCount(1);

                VkDependencyInfo depInfo = VkDependencyInfo.calloc(stack)
                        .sType(VK13.VK_STRUCTURE_TYPE_DEPENDENCY_INFO)
                        .pImageMemoryBarriers(barrier);

                VK13.vkCmdPipelineBarrier2(cmdBuf, depInfo);
            } else {
                org.lwjgl.vulkan.VkImageMemoryBarrier.Buffer barrier = org.lwjgl.vulkan.VkImageMemoryBarrier.calloc(1, stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                        .srcAccessMask(VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                        .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT)
                        .oldLayout(VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                        .newLayout(VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                        .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .image(image);

                barrier.subresourceRange()
                        .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0)
                        .levelCount(1)
                        .baseArrayLayer(0)
                        .layerCount(1);

                VK10.vkCmdPipelineBarrier(
                        cmdBuf,
                        VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                        VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        0,
                        null,
                        null,
                        barrier
                );
            }
        }
    }
}
