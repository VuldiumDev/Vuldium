package net.caffeinemc.mods.sodium.client.gpu.arena.vulkan;

import net.caffeinemc.mods.sodium.client.gpu.arena.BufferSegment;
import net.caffeinemc.mods.sodium.client.gpu.arena.PendingUpload;
import net.caffeinemc.mods.sodium.client.gpu.device.vulkan.VuldiumDeviceContext;
import net.caffeinemc.mods.sodium.client.util.NativeBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * Высокопроизводительная реализация буферной арены Vulkan с двухуровневой субаллокацией.
 * Хранит геометрию в Device-Local памяти и производит потокобезопасный трансфер через
 * кольцевой хост-видимый Staging Buffer с батчингом через vkCmdCopyBuffer.
 */
public class VuldiumBufferArenaImpl implements VuldiumBufferArena {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vuldium/BufferArena");
    private static final int ALIGNMENT = 16;

    public static final long DEFAULT_DEVICE_CAPACITY = 256L * 1024L * 1024L; // 256 МБ базовая емкость
    public static final long HIGH_DISTANCE_DEVICE_CAPACITY = 512L * 1024L * 1024L; // 512 МБ для 32+ чанков (исключает динамические vkAllocateMemory)
    public static final long DEFAULT_STAGING_CAPACITY = 48L * 1024L * 1024L; // 48 МБ кольцевой staging буфер

    private final VuldiumDeviceContext context;
    private final VkDevice device;

    private final long capacity;
    private final int stride;
    private long usedBytes = 0;

    // Ресурсы целевого буфера геометрии (Device-Local)
    private long deviceBuffer = VK10.VK_NULL_HANDLE;
    private long deviceMemory = VK10.VK_NULL_HANDLE;

    // Ресурсы кольцевого Staging буфера (Host-Visible & Coherent)
    private final long stagingCapacity;
    private long stagingBuffer = VK10.VK_NULL_HANDLE;
    private long stagingMemory = VK10.VK_NULL_HANDLE;
    private long pMappedStaging = MemoryUtil.NULL;
    private long stagingHead = 0;

    // Список отложенных операций копирования для текущего кадра
    private record StagingCopy(long srcOffset, long dstOffset, long size) {}
    private final List<StagingCopy> pendingCopies = new ArrayList<>();

    // Связный список сегментов субаллокатора памяти
    private static class MemoryNode {
        long offset;
        long length;
        boolean isFree;
        MemoryNode prev;
        MemoryNode next;

        MemoryNode(long offset, long length, boolean isFree) {
            this.offset = offset;
            this.length = length;
            this.isFree = isFree;
        }
    }

    private MemoryNode headNode;
    private boolean isClosed = false;

    public VuldiumBufferArenaImpl(VuldiumDeviceContext context, long capacity, int stride, long stagingCapacity) {
        this.context = context;
        this.device = context.getLogicalDevice();
        this.capacity = capacity;
        this.stride = stride;
        this.stagingCapacity = stagingCapacity;

        this.initDeviceLocalBuffer();
        this.initStagingBuffer();

        this.headNode = new MemoryNode(0, capacity, true);
        LOGGER.info("Vuldium BufferArena аллоцирована: DeviceCapacity={} MB, StagingCapacity={} MB, Stride={}",
                capacity / (1024 * 1024), stagingCapacity / (1024 * 1024), stride);
    }

    private void initDeviceLocalBuffer() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int usageFlags = VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT |
                    VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT |
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT |
                    VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT |
                    VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;

            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(this.capacity)
                    .usage(usageFlags)
                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);

            LongBuffer pBuffer = stack.mallocLong(1);
            int res = VK10.vkCreateBuffer(this.device, bufferInfo, null, pBuffer);
            if (res != VK10.VK_SUCCESS) {
                throw new IllegalStateException("Не удалось создать целевой VkBuffer арены: " + res);
            }
            this.deviceBuffer = pBuffer.get(0);

            VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
            VK10.vkGetBufferMemoryRequirements(this.device, this.deviceBuffer, memReqs);

            int memTypeIndex = this.context.findMemoryTypeIndex(
                    memReqs.memoryTypeBits(),
                    VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
            );

            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(memReqs.size())
                    .memoryTypeIndex(memTypeIndex);

            LongBuffer pMemory = stack.mallocLong(1);
            res = VK10.vkAllocateMemory(this.device, allocInfo, null, pMemory);
            if (res != VK10.VK_SUCCESS) {
                throw new IllegalStateException("Не удалось выделить Device-Local память: " + res);
            }
            this.deviceMemory = pMemory.get(0);

            VK10.vkBindBufferMemory(this.device, this.deviceBuffer, this.deviceMemory, 0);
        }
    }

    private void initStagingBuffer() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(this.stagingCapacity)
                    .usage(VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);

            LongBuffer pBuffer = stack.mallocLong(1);
            int res = VK10.vkCreateBuffer(this.device, bufferInfo, null, pBuffer);
            if (res != VK10.VK_SUCCESS) {
                throw new IllegalStateException("Не удалось создать Staging VkBuffer: " + res);
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

            LongBuffer pMemory = stack.mallocLong(1);
            res = VK10.vkAllocateMemory(this.device, allocInfo, null, pMemory);
            if (res != VK10.VK_SUCCESS) {
                throw new IllegalStateException("Не удалось выделить память для Staging буфера: " + res);
            }
            this.stagingMemory = pMemory.get(0);

            VK10.vkBindBufferMemory(this.device, this.stagingBuffer, this.stagingMemory, 0);

            PointerBuffer pData = stack.mallocPointer(1);
            res = VK10.vkMapMemory(this.device, this.stagingMemory, 0, this.stagingCapacity, 0, pData);
            if (res != VK10.VK_SUCCESS) {
                throw new IllegalStateException("Не удалось замаппить Staging память: " + res);
            }
            this.pMappedStaging = pData.get(0);
        }
    }

    @Override
    public long getVkBufferHandle() {
        return this.deviceBuffer;
    }

    @Override
    public long getVkDeviceMemory() {
        return this.deviceMemory;
    }

    @Override
    public long getCapacity() {
        return this.capacity;
    }

    @Override
    public synchronized long getUsedBytes() {
        return this.usedBytes;
    }

    @Override
    public synchronized BufferSegment allocate(long sizeBytes) {
        long alignedSize = (sizeBytes + ALIGNMENT - 1) & ~(ALIGNMENT - 1);

        MemoryNode current = this.headNode;
        while (current != null) {
            if (current.isFree && current.length >= alignedSize) {
                if (current.length > alignedSize) {
                    MemoryNode nextNode = new MemoryNode(
                            current.offset + alignedSize,
                            current.length - alignedSize,
                            true
                    );
                    nextNode.next = current.next;
                    nextNode.prev = current;
                    if (current.next != null) {
                        current.next.prev = nextNode;
                    }
                    current.next = nextNode;
                    current.length = alignedSize;
                }

                current.isFree = false;
                this.usedBytes += alignedSize;

                return new BufferSegment(null, null, 0, current.offset, alignedSize);
            }
            current = current.next;
        }

        throw new OutOfMemoryError(String.format(
                "Vuldium Arena переполнена: Запрошено=%d байт, Занято=%d/%d байт",
                alignedSize, this.usedBytes, this.capacity
        ));
    }

    @Override
    public synchronized void free(BufferSegment segment) {
        if (segment == null) {
            return;
        }

        long offset = segment.getOffset();
        long length = segment.getLength();

        MemoryNode current = this.headNode;
        while (current != null) {
            if (current.offset == offset) {
                current.isFree = true;
                this.usedBytes -= current.length;

                // Слияние со следующим свободным блоком
                if (current.next != null && current.next.isFree) {
                    current.length += current.next.length;
                    current.next = current.next.next;
                    if (current.next != null) {
                        current.next.prev = current;
                    }
                }

                // Слияние с предыдущим свободным блоком
                if (current.prev != null && current.prev.isFree) {
                    current.prev.length += current.length;
                    current.prev.next = current.next;
                    if (current.next != null) {
                        current.next.prev = current.prev;
                    }
                }

                return;
            }
            current = current.next;
        }
    }

    @Override
    public synchronized void enqueueUploads(List<PendingUpload> uploadQueue) {
        for (PendingUpload upload : uploadQueue) {
            NativeBuffer buffer = upload.getDataBuffer();
            int bytes = buffer.getLength();
            if (bytes == 0) {
                continue;
            }

            BufferSegment targetSegment = this.allocate(bytes);

            // Проверка переполнения Staging Ring буфера
            if (this.stagingHead + bytes > this.stagingCapacity) {
                // Если буфер полон в рамках одного кадра, сбрасываем указатель
                this.stagingHead = 0;
            }

            long stagingOffset = this.stagingHead;
            MemoryUtil.memCopy(
                    MemoryUtil.memAddress(buffer.getDirectBuffer()),
                    this.pMappedStaging + stagingOffset,
                    bytes
            );

            this.stagingHead += (bytes + ALIGNMENT - 1) & ~(ALIGNMENT - 1);
            this.pendingCopies.add(new StagingCopy(stagingOffset, targetSegment.getOffset(), bytes));
        }
    }

    @Override
    public synchronized void flushUploads(VkCommandBuffer transferCmdBuf) {
        if (this.pendingCopies.isEmpty()) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            int count = this.pendingCopies.size();
            VkBufferCopy.Buffer copyRegions = VkBufferCopy.calloc(count, stack);

            for (int i = 0; i < count; i++) {
                StagingCopy copy = this.pendingCopies.get(i);
                copyRegions.get(i)
                        .srcOffset(copy.srcOffset())
                        .dstOffset(copy.dstOffset())
                        .size(copy.size());
            }

            VK10.vkCmdCopyBuffer(transferCmdBuf, this.stagingBuffer, this.deviceBuffer, copyRegions);

            // Барьер памяти после трансфера перед чтением вершинным конвейером
            VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(1, stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                    .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT | VK10.VK_ACCESS_INDEX_READ_BIT | VK10.VK_ACCESS_INDIRECT_COMMAND_READ_BIT)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .buffer(this.deviceBuffer)
                    .offset(0)
                    .size(VK10.VK_WHOLE_SIZE);

            VK10.vkCmdPipelineBarrier(
                    transferCmdBuf,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_PIPELINE_STAGE_VERTEX_INPUT_BIT | VK10.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,
                    0,
                    null,
                    barrier,
                    null
            );

            this.pendingCopies.clear();
        }
    }

    @Override
    public synchronized void defragment(VkCommandBuffer copyCmdBuf) {
        // Дефрагментация: перемещение блоков для ликвидации свободных дыр
        MemoryNode current = this.headNode;
        long targetOffset = 0;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            while (current != null) {
                if (!current.isFree) {
                    if (current.offset != targetOffset) {
                        VkBufferCopy.Buffer copyRegion = VkBufferCopy.calloc(1, stack)
                                .srcOffset(current.offset)
                                .dstOffset(targetOffset)
                                .size(current.length);

                        VK10.vkCmdCopyBuffer(copyCmdBuf, this.deviceBuffer, this.deviceBuffer, copyRegion);
                        current.offset = targetOffset;
                    }
                    targetOffset += current.length;
                }
                current = current.next;
            }
        }
    }

    @Override
    public synchronized void close() {
        if (this.isClosed) {
            return;
        }

        this.context.waitIdle();

        if (this.pMappedStaging != MemoryUtil.NULL) {
            VK10.vkUnmapMemory(this.device, this.stagingMemory);
            this.pMappedStaging = MemoryUtil.NULL;
        }

        if (this.stagingBuffer != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyBuffer(this.device, this.stagingBuffer, null);
            this.stagingBuffer = VK10.VK_NULL_HANDLE;
        }

        if (this.stagingMemory != VK10.VK_NULL_HANDLE) {
            VK10.vkFreeMemory(this.device, this.stagingMemory, null);
            this.stagingMemory = VK10.VK_NULL_HANDLE;
        }

        if (this.deviceBuffer != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyBuffer(this.device, this.deviceBuffer, null);
            this.deviceBuffer = VK10.VK_NULL_HANDLE;
        }

        if (this.deviceMemory != VK10.VK_NULL_HANDLE) {
            VK10.vkFreeMemory(this.device, this.deviceMemory, null);
            this.deviceMemory = VK10.VK_NULL_HANDLE;
        }

        this.isClosed = true;
        LOGGER.info("Vuldium BufferArena успешно освобождена.");
    }

    @Override
    public long getStagingVkBuffer() {
        return this.stagingBuffer;
    }

    @Override
    public long getStagingCapacity() {
        return this.stagingCapacity;
    }

    @Override
    public synchronized long stageDirectBuffer(java.nio.ByteBuffer buffer, long bytes) {
        if (this.stagingHead + bytes > this.stagingCapacity) {
            this.stagingHead = 0;
        }

        long offset = this.stagingHead;
        MemoryUtil.memCopy(
                MemoryUtil.memAddress(buffer),
                this.pMappedStaging + offset,
                bytes
        );

        this.stagingHead += (bytes + ALIGNMENT - 1) & ~(ALIGNMENT - 1);
        return offset;
    }

    public static VuldiumBufferArena createHighDistanceArena(VuldiumDeviceContext context, int stride) {
        return new VuldiumBufferArenaImpl(context, HIGH_DISTANCE_DEVICE_CAPACITY, stride, DEFAULT_STAGING_CAPACITY);
    }
}
