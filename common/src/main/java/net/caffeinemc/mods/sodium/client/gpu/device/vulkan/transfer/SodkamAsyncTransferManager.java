package net.caffeinemc.mods.sodium.client.gpu.device.vulkan.transfer;

import net.caffeinemc.mods.sodium.client.gpu.device.vulkan.SodkamDeviceContext;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkTimelineSemaphoreSubmitInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;
import java.util.List;

/**
 * Асинхронный менеджер потоковой передачи геометрии (Dedicated Transfer Queue Engine).
 * Обеспечивает неблокирующую фоновую загрузку данных чанков на GPU без задержек основного цикла рендеринга.
 *
 * Архитектурные особенности:
 * - Использует выделенную очередь DMA-трансфера (Dedicated Transfer Queue).
 * - Синхронизирует графическую и трансферную очереди через Timeline Semaphores (Vulkan 1.2+).
 * - Реализует корректную передачу прав владения буферами (Queue Family Ownership Transfer)
 *   через парные барьеры Release/Acquire.
 */
public class SodkamAsyncTransferManager implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vuldium/AsyncTransfer");

    private final SodkamDeviceContext context;
    private final VkDevice device;
    private final VkQueue transferQueue;
    private final int transferQueueFamily;
    private final int graphicsQueueFamily;
    private final boolean isDedicatedTransferQueue;

    private VkCommandBuffer activeTransferCmd;
    private boolean isRecording = false;

    public record BufferTransferRegion(long srcOffset, long dstOffset, long size) {}

    public SodkamAsyncTransferManager(SodkamDeviceContext context) {
        this.context = context;
        this.device = context.getLogicalDevice();
        this.transferQueue = context.getTransferQueue();
        this.transferQueueFamily = context.getTransferQueueFamilyIndex();
        this.graphicsQueueFamily = context.getGraphicsQueueFamilyIndex();
        this.isDedicatedTransferQueue = (this.transferQueueFamily != this.graphicsQueueFamily);

        LOGGER.info("Vuldium Async Transfer Manager инициализирован: DedicatedQueue={}", this.isDedicatedTransferQueue);
    }

    /**
     * Открывает запись команд трансфера в командный буфер выделенной очереди.
     */
    public void beginTransfer() {
        if (this.isRecording) {
            return;
        }

        this.activeTransferCmd = this.context.allocateTransferCommandBuffer();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

            VK10.vkBeginCommandBuffer(this.activeTransferCmd, beginInfo);
        }

        this.isRecording = true;
    }

    /**
     * Записывает команды копирования из Staging в Device буфер с барьером передачи владения.
     *
     * @param srcStagingBuffer  буфер источника (Host-Visible)
     * @param dstDeviceBuffer   целевой буфер (Device-Local)
     * @param regions           список областей копирования
     */
    public void recordCopies(long srcStagingBuffer, long dstDeviceBuffer, List<BufferTransferRegion> regions) {
        if (!this.isRecording || regions.isEmpty()) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            int count = regions.size();
            VkBufferCopy.Buffer copyRegions = VkBufferCopy.calloc(count, stack);

            for (int i = 0; i < count; i++) {
                BufferTransferRegion r = regions.get(i);
                copyRegions.get(i)
                        .srcOffset(r.srcOffset())
                        .dstOffset(r.dstOffset())
                        .size(r.size());
            }

            VK10.vkCmdCopyBuffer(this.activeTransferCmd, srcStagingBuffer, dstDeviceBuffer, copyRegions);

            // Если используется выделенная трансферная очередь, записываем Release-барьер
            if (this.isDedicatedTransferQueue) {
                VkBufferMemoryBarrier.Buffer releaseBarrier = VkBufferMemoryBarrier.calloc(1, stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                        .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                        .dstAccessMask(0)
                        .srcQueueFamilyIndex(this.transferQueueFamily)
                        .dstQueueFamilyIndex(this.graphicsQueueFamily)
                        .buffer(dstDeviceBuffer)
                        .offset(0)
                        .size(VK10.VK_WHOLE_SIZE);

                VK10.vkCmdPipelineBarrier(
                        this.activeTransferCmd,
                        VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                        0,
                        null,
                        releaseBarrier,
                        null
                );
            }
        }
    }

    /**
     * Завершает запись и отправляет пакет на выполнение в Dedicated Transfer Queue,
     * сигнализируя Timeline Semaphore.
     *
     * @return сигнальное значение Timeline Semaphore, на которое должна ожидать графическая очередь
     */
    public long submitTransfer() {
        if (!this.isRecording) {
            return this.context.getLastSubmittedTransferTimelineValue();
        }

        VK10.vkEndCommandBuffer(this.activeTransferCmd);
        this.isRecording = false;

        long timelineValue = this.context.signalNextTransferTimelineValue();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkTimelineSemaphoreSubmitInfo timelineInfo = VkTimelineSemaphoreSubmitInfo.calloc(stack)
                    .sType(VK12.VK_STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO)
                    .pSignalSemaphoreValues(stack.longs(timelineValue));

            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pNext(timelineInfo.address())
                    .pCommandBuffers(stack.pointers(this.activeTransferCmd))
                    .pSignalSemaphores(stack.longs(this.context.getTransferTimelineSemaphore()));

            int res = VK10.vkQueueSubmit(this.transferQueue, submitInfo, VK10.VK_NULL_HANDLE);
            if (res != VK10.VK_SUCCESS) {
                throw new IllegalStateException("Ошибка отправки трансферных команд в очередь: " + res);
            }
        }

        return timelineValue;
    }

    /**
     * Записывает Acquire-барьер передачи владения в графический командный буфер перед отрисовкой.
     *
     * @param graphicsCmd      активный графический VkCommandBuffer
     * @param dstDeviceBuffer  целевой буфер геометрии
     */
    public void recordAcquireBarrier(VkCommandBuffer graphicsCmd, long dstDeviceBuffer) {
        if (!this.isDedicatedTransferQueue) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferMemoryBarrier.Buffer acquireBarrier = VkBufferMemoryBarrier.calloc(1, stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                    .srcAccessMask(0)
                    .dstAccessMask(VK10.VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT | VK10.VK_ACCESS_INDEX_READ_BIT | VK10.VK_ACCESS_INDIRECT_COMMAND_READ_BIT)
                    .srcQueueFamilyIndex(this.transferQueueFamily)
                    .dstQueueFamilyIndex(this.graphicsQueueFamily)
                    .buffer(dstDeviceBuffer)
                    .offset(0)
                    .size(VK10.VK_WHOLE_SIZE);

            VK10.vkCmdPipelineBarrier(
                    graphicsCmd,
                    VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                    VK10.VK_PIPELINE_STAGE_VERTEX_INPUT_BIT | VK10.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,
                    0,
                    null,
                    acquireBarrier,
                    null
            );
        }
    }

    @Override
    public void close() {
        if (this.isRecording && this.activeTransferCmd != null) {
            this.context.freeTransferCommandBuffer(this.activeTransferCmd);
            this.isRecording = false;
        }
    }
}
