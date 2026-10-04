package net.caffeinemc.mods.sodium.client.render.chunk.vulkan.texture;

import net.caffeinemc.mods.sodium.client.gpu.device.vulkan.VuldiumDeviceContext;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.List;

/**
 * Высокопроизводительный асинхронный загрузчик анимированных текстур атласа блоков (Vulkan Texture Streaming).
 *
 * Архитектурные принципы:
 * 1. Staging Ring-Buffer: циклический буфер памяти в HOST_VISIBLE | HOST_COHERENT памяти.
 * 2. Zero-Copy на CPU: пиксели dirty-спрайтов копируются напрямую в постоянно отображённую VRAM память.
 * 3. Batched Buffer-to-Image Copies: все обновлённые спрайты текущего тика (вода, лава, огонь, порталы)
 *    передаются в текстурный атлас за ОДИН вызов vkCmdCopyBufferToImage.
 * 4. Synchronization2: атомарные барьеры VkImageMemoryBarrier2 переводят атлас
 *    SHADER_READ_ONLY_OPTIMAL -> TRANSFER_DST_OPTIMAL -> SHADER_READ_ONLY_OPTIMAL без Pipeline Bubbles.
 */
public class VuldiumAtlasTextureUploader implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vuldium/AtlasUploader");
    public static final int DEFAULT_RING_BUFFER_SIZE = 4 * 1024 * 1024; // 4 МБ

    private final VuldiumDeviceContext context;
    private final VkDevice device;
    private final boolean supportsSync2;
    private final int bufferCapacity;

    private long stagingBuffer = VK10.VK_NULL_HANDLE;
    private long stagingMemory = VK10.VK_NULL_HANDLE;
    private long mappedAddress = 0L;

    // Смещение в циклическом буфере для текущего кадра
    private int ringHead = 0;
    private boolean isClosed = false;

    /**
     * Описание обновления одного спрайта в атласе.
     *
     * @param x         координата X в атласе
     * @param y         координата Y в атласе
     * @param width     ширина спрайта
     * @param height    высота спрайта
     * @param pixelData пиксели RGBA8
     */
    public record SpriteUpdate(int x, int y, int width, int height, ByteBuffer pixelData) {
        public int getByteSize() {
            return this.width * this.height * 4;
        }
    }

    public VuldiumAtlasTextureUploader(VuldiumDeviceContext context) {
        this(context, DEFAULT_RING_BUFFER_SIZE);
    }

    public VuldiumAtlasTextureUploader(VuldiumDeviceContext context, int bufferCapacity) {
        this.context = context;
        this.device = context.getLogicalDevice();
        this.bufferCapacity = bufferCapacity;
        this.supportsSync2 = context.getDeviceCapabilities().apiVersion >= VK13.VK_API_VERSION_1_3 ||
                context.getDeviceCapabilities().VK_KHR_synchronization2;

        this.initStagingBuffer();
        LOGGER.info("Vuldium Atlas Texture Uploader инициализирован (Размер: {} КБ, Sync2={})",
                bufferCapacity / 1024, this.supportsSync2);
    }

    private void initStagingBuffer() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(this.bufferCapacity)
                    .usage(VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);

            LongBuffer pBuffer = stack.mallocLong(1);
            int res = VK10.vkCreateBuffer(this.device, bufferInfo, null, pBuffer);
            if (res != VK10.VK_SUCCESS) {
                throw new IllegalStateException("Ошибка создания Staging Buffer для атласа: " + res);
            }
            this.stagingBuffer = pBuffer.get(0);

            VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
            VK10.vkGetBufferMemoryRequirements(this.device, this.stagingBuffer, memReqs);

            int memTypeIndex = this.context.findMemoryTypeIndex(
                    memReqs.memoryTypeBits(),
                    VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
            );

            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(memReqs.size())
                    .memoryTypeIndex(memTypeIndex);

            LongBuffer pMem = stack.mallocLong(1);
            res = VK10.vkAllocateMemory(this.device, allocInfo, null, pMem);
            if (res != VK10.VK_SUCCESS) {
                throw new IllegalStateException("Ошибка выделения памяти для Staging Buffer атласа: " + res);
            }
            this.stagingMemory = pMem.get(0);

            VK10.vkBindBufferMemory(this.device, this.stagingBuffer, this.stagingMemory, 0);

            PointerBuffer pData = stack.mallocPointer(1);
            res = VK10.vkMapMemory(this.device, this.stagingMemory, 0, this.bufferCapacity, 0, pData);
            if (res != VK10.VK_SUCCESS) {
                throw new IllegalStateException("Ошибка отображения (Map) Staging памяти атласа: " + res);
            }
            this.mappedAddress = pData.get(0);
        }
    }

    public synchronized void uploadUpdates(VkCommandBuffer cmd, long atlasImage, List<SpriteUpdate> dirtySprites) {
        this.updateAnimatedSprites(cmd, atlasImage, dirtySprites);
    }

    /**
     * Пакетно загружает все изменённые анимированные спрайты в текстурный атлас за один командный вызов.
     *
     * @param cmd          активный командный буфер начальной инициализации кадра (Setup Command Buffer)
     * @param atlasImage   дескриптор VkImage текстурного атласа блоков
     * @param dirtySprites список спрайтов с новыми кадрами анимации
     */
    public synchronized void updateAnimatedSprites(VkCommandBuffer cmd, long atlasImage, List<SpriteUpdate> dirtySprites) {
        if (dirtySprites == null || dirtySprites.isEmpty() || atlasImage == VK10.VK_NULL_HANDLE) {
            return;
        }

        // 1. Подсчёт суммарного объёма с учётом 4-байтового выравнивания
        int totalBytesNeeded = 0;
        for (SpriteUpdate sprite : dirtySprites) {
            int size = sprite.getByteSize();
            totalBytesNeeded += (size + 3) & ~3;
        }

        if (totalBytesNeeded > this.bufferCapacity) {
            LOGGER.warn("Размер анимированных спрайтов ({} байт) превышает ёмкость Staging Buffer ({} байт)",
                    totalBytesNeeded, this.bufferCapacity);
            return;
        }

        // Проверка переноса кольцевого буфера
        if (this.ringHead + totalBytesNeeded > this.bufferCapacity) {
            this.ringHead = 0;
        }

        int startOffset = this.ringHead;
        int currentOffset = startOffset;
        int spriteCount = dirtySprites.size();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferImageCopy.Buffer copyRegions = VkBufferImageCopy.calloc(spriteCount, stack);

            // 2. Копирование пикселей в замапленный кольцевой буфер и заполнение регионов копирования
            for (int i = 0; i < spriteCount; i++) {
                SpriteUpdate sprite = dirtySprites.get(i);
                int byteSize = sprite.getByteSize();

                ByteBuffer src = sprite.pixelData();
                src.position(0);
                MemoryUtil.memCopy(MemoryUtil.memAddress(src), this.mappedAddress + currentOffset, byteSize);

                VkBufferImageCopy copy = copyRegions.get(i);
                copy.bufferOffset(currentOffset);
                copy.bufferRowLength(0); // Плотная упаковка
                copy.bufferImageHeight(0);

                copy.imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(0)
                        .baseArrayLayer(0)
                        .layerCount(1);

                copy.imageOffset().set(sprite.x(), sprite.y(), 0);
                copy.imageExtent().set(sprite.width(), sprite.height(), 1);

                currentOffset += (byteSize + 3) & ~3;
            }

            this.ringHead = currentOffset;

            // 3. Барьер 1: SHADER_READ_ONLY_OPTIMAL -> TRANSFER_DST_OPTIMAL
            this.recordLayoutTransition(
                    cmd,
                    atlasImage,
                    VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK13.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                    VK13.VK_ACCESS_2_SHADER_READ_BIT,
                    VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                    VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT,
                    stack
            );

            // 4. Пакетная передача всех спрайтов за один вызов
            VK10.vkCmdCopyBufferToImage(
                    cmd,
                    this.stagingBuffer,
                    atlasImage,
                    VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    copyRegions
            );

            // 5. Барьер 2: TRANSFER_DST_OPTIMAL -> SHADER_READ_ONLY_OPTIMAL
            this.recordLayoutTransition(
                    cmd,
                    atlasImage,
                    VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                    VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT,
                    VK13.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                    VK13.VK_ACCESS_2_SHADER_READ_BIT,
                    stack
            );
        }
    }

    private void recordLayoutTransition(
            VkCommandBuffer cmd,
            long image,
            int oldLayout,
            int newLayout,
            long srcStage,
            long srcAccess,
            long dstStage,
            long dstAccess,
            MemoryStack stack
    ) {
        if (this.supportsSync2) {
            VkImageMemoryBarrier2.Buffer barrier = VkImageMemoryBarrier2.calloc(1, stack)
                    .sType(VK13.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2)
                    .oldLayout(oldLayout)
                    .newLayout(newLayout)
                    .srcStageMask(srcStage)
                    .srcAccessMask(srcAccess)
                    .dstStageMask(dstStage)
                    .dstAccessMask(dstAccess)
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

            VK13.vkCmdPipelineBarrier2(cmd, depInfo);
        } else {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(oldLayout)
                    .newLayout(newLayout)
                    .srcAccessMask((int) srcAccess)
                    .dstAccessMask((int) dstAccess)
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
                    cmd,
                    (int) srcStage,
                    (int) dstStage,
                    0,
                    null,
                    null,
                    barrier
            );
        }
    }

    public long getStagingBuffer() {
        return this.stagingBuffer;
    }

    public int getBufferCapacity() {
        return this.bufferCapacity;
    }

    @Override
    public synchronized void close() {
        if (this.isClosed) {
            return;
        }

        if (this.mappedAddress != 0L) {
            VK10.vkUnmapMemory(this.device, this.stagingMemory);
            this.mappedAddress = 0L;
        }

        if (this.stagingBuffer != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyBuffer(this.device, this.stagingBuffer, null);
            this.stagingBuffer = VK10.VK_NULL_HANDLE;
        }

        if (this.stagingMemory != VK10.VK_NULL_HANDLE) {
            VK10.vkFreeMemory(this.device, this.stagingMemory, null);
            this.stagingMemory = VK10.VK_NULL_HANDLE;
        }

        this.isClosed = true;
        LOGGER.info("Vuldium Atlas Texture Uploader успешно закрыт.");
    }
}
