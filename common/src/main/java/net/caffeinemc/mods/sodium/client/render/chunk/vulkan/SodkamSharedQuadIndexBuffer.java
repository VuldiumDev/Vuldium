package net.caffeinemc.mods.sodium.client.render.chunk.vulkan;

import net.caffeinemc.mods.sodium.client.gpu.device.vulkan.SodkamDeviceContext;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

/**
 * Разделяемый индексный буфер Vulkan для отрисовки квадов (6 индексов на 4 вершины).
 * Хранит шаблон индексов (0, 1, 2, 2, 3, 0), аллоцирован в Host-Visible или Device-Local памяти.
 */
public class SodkamSharedQuadIndexBuffer implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vuldium/QuadIndexBuffer");
    public static final int ELEMENTS_PER_QUAD = 6;
    public static final int VERTICES_PER_QUAD = 4;

    private final SodkamDeviceContext context;
    private final VkDevice device;

    private long buffer = VK10.VK_NULL_HANDLE;
    private long memory = VK10.VK_NULL_HANDLE;
    private int currentQuadCapacity = 0;

    public SodkamSharedQuadIndexBuffer(SodkamDeviceContext context, int initialQuadCapacity) {
        this.context = context;
        this.device = context.getLogicalDevice();
        this.ensureCapacity(initialQuadCapacity);
    }

    public synchronized void ensureCapacity(int quadCount) {
        if (quadCount <= this.currentQuadCapacity) {
            return;
        }

        int newCapacity = Math.max(this.currentQuadCapacity * 2, quadCount + 16384);
        this.reallocate(newCapacity);
    }

    private void reallocate(int newQuadCapacity) {
        this.close();

        long byteSize = (long) newQuadCapacity * ELEMENTS_PER_QUAD * Integer.BYTES;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(byteSize)
                    .usage(VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT)
                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);

            LongBuffer pBuffer = stack.mallocLong(1);
            int res = VK10.vkCreateBuffer(this.device, bufferInfo, null, pBuffer);
            if (res != VK10.VK_SUCCESS) {
                throw new IllegalStateException("Ошибка создания Quad Index Buffer: " + res);
            }
            this.buffer = pBuffer.get(0);

            VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
            VK10.vkGetBufferMemoryRequirements(this.device, this.buffer, memReqs);

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
                throw new IllegalStateException("Ошибка аллокации памяти Quad Index Buffer: " + res);
            }
            this.memory = pMemory.get(0);

            VK10.vkBindBufferMemory(this.device, this.buffer, this.memory, 0);

            PointerBuffer pData = stack.mallocPointer(1);
            res = VK10.vkMapMemory(this.device, this.memory, 0, byteSize, 0, pData);
            if (res != VK10.VK_SUCCESS) {
                throw new IllegalStateException("Ошибка маппинга памяти Quad Index Buffer: " + res);
            }

            long pMapped = pData.get(0);
            IntBuffer intBuffer = MemoryUtil.memIntBuffer(pMapped, newQuadCapacity * ELEMENTS_PER_QUAD);

            for (int quad = 0; quad < newQuadCapacity; quad++) {
                int indexBase = quad * ELEMENTS_PER_QUAD;
                int vertBase = quad * VERTICES_PER_QUAD;

                intBuffer.put(indexBase + 0, vertBase + 0);
                intBuffer.put(indexBase + 1, vertBase + 1);
                intBuffer.put(indexBase + 2, vertBase + 2);
                intBuffer.put(indexBase + 3, vertBase + 2);
                intBuffer.put(indexBase + 4, vertBase + 3);
                intBuffer.put(indexBase + 5, vertBase + 0);
            }

            VK10.vkUnmapMemory(this.device, this.memory);

            this.currentQuadCapacity = newQuadCapacity;
            LOGGER.info("Vuldium Quad Index Buffer аллоцирован: Quads={}, Size={} KB",
                    newQuadCapacity, byteSize / 1024);
        }
    }

    public long getBufferHandle() {
        return this.buffer;
    }

    public int getCurrentQuadCapacity() {
        return this.currentQuadCapacity;
    }

    @Override
    public synchronized void close() {
        if (this.buffer != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyBuffer(this.device, this.buffer, null);
            this.buffer = VK10.VK_NULL_HANDLE;
        }

        if (this.memory != VK10.VK_NULL_HANDLE) {
            VK10.vkFreeMemory(this.device, this.memory, null);
            this.memory = VK10.VK_NULL_HANDLE;
        }

        this.currentQuadCapacity = 0;
    }
}
