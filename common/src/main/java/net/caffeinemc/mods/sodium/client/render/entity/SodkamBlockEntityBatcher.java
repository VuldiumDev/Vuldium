package net.caffeinemc.mods.sodium.client.render.entity;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.caffeinemc.mods.sodium.client.gpu.device.vulkan.SodkamDeviceContext;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;

/**
 * Высокопроизводительный пакетный сборщик геометрии динамических блоков (SodkamBlockEntityBatcher).
 *
 * Архитектурные принципы:
 * 1. Устраняет раздельные вызовы отрисовки сундуков, табличек, голов мобов, поршней и колоколов.
 * 2. Буферизует вершины в стандартном формате Minecraft Entity (36 байт: Pos3D, Color4, UV2, Overlay2, Light2, Normal3).
 * 3. Реализует {@link VertexConsumer} для прозрачного перехвата вызовов рендеринга без промежуточных конвертаций.
 * 4. Использует постоянно замапленную Host-Visible | Host-Coherent память для Zero-JNI записи на CPU.
 * 5. Интеграция с Z-буфером: отрисовка выполняется сразу после непрозрачного ландшафта (Opaque Pass)
 *    с включённым тестом и записью глубины (depthTestEnable = VK_TRUE, depthWriteEnable = VK_TRUE).
 */
public class SodkamBlockEntityBatcher implements VertexConsumer, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Sodkam/BlockEntityBatcher");

    public static final int VERTEX_STRIDE = 36;
    public static final int DEFAULT_VERTEX_CAPACITY = 65536; // 64K вершин = ~16K квадов

    private final SodkamDeviceContext context;
    private final VkDevice device;

    private long vertexBuffer = VK10.VK_NULL_HANDLE;
    private long vertexMemory = VK10.VK_NULL_HANDLE;
    private long mappedAddress = 0L;

    private int vertexCapacity;
    private int vertexCount = 0;
    private boolean isBuilding = false;
    private boolean isClosed = false;

    // Временное состояние сборки вершины при поточечном вызове атрибутов (Builder Pattern)
    private float currentX, currentY, currentZ;
    private int currentColor = 0xFFFFFFFF;
    private float currentU, currentV;
    private int currentOverlay = 0;
    private int currentLight = 0;
    private float currentNormalX = 0.0f, currentNormalY = 1.0f, currentNormalZ = 0.0f;

    public SodkamBlockEntityBatcher(SodkamDeviceContext context) {
        this(context, DEFAULT_VERTEX_CAPACITY);
    }

    public SodkamBlockEntityBatcher(SodkamDeviceContext context, int initialVertexCapacity) {
        this.context = context;
        this.device = context.getLogicalDevice();
        this.vertexCapacity = initialVertexCapacity;

        this.allocateBuffer(this.vertexCapacity);
        LOGGER.info("Vuldium Block Entity Batcher инициализирован (Ёмкость: {} вершин, {} КБ)",
                this.vertexCapacity, (this.vertexCapacity * VERTEX_STRIDE) / 1024);
    }

    private void allocateBuffer(int capacity) {
        long byteSize = (long) capacity * VERTEX_STRIDE;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(byteSize)
                    .usage(VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT)
                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);

            LongBuffer pBuffer = stack.mallocLong(1);
            int res = VK10.vkCreateBuffer(this.device, bufferInfo, null, pBuffer);
            if (res != VK10.VK_SUCCESS) {
                throw new IllegalStateException("Ошибка создания Vertex Buffer для Block Entities: " + res);
            }
            this.vertexBuffer = pBuffer.get(0);

            VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
            VK10.vkGetBufferMemoryRequirements(this.device, this.vertexBuffer, memReqs);

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
                throw new IllegalStateException("Ошибка аллокации памяти Vertex Buffer для Block Entities: " + res);
            }
            this.vertexMemory = pMem.get(0);

            VK10.vkBindBufferMemory(this.device, this.vertexBuffer, this.vertexMemory, 0);

            PointerBuffer pData = stack.mallocPointer(1);
            res = VK10.vkMapMemory(this.device, this.vertexMemory, 0, byteSize, 0, pData);
            if (res != VK10.VK_SUCCESS) {
                throw new IllegalStateException("Ошибка отображения (Map) памяти Vertex Buffer Block Entities: " + res);
            }
            this.mappedAddress = pData.get(0);
        }
    }

    private void ensureCapacity(int additionalVertices) {
        if (this.vertexCount + additionalVertices <= this.vertexCapacity) {
            return;
        }

        int newCapacity = Math.max(this.vertexCapacity * 2, this.vertexCount + additionalVertices + 8192);
        long oldAddress = this.mappedAddress;
        long oldBuffer = this.vertexBuffer;
        long oldMemory = this.vertexMemory;
        int oldBytes = this.vertexCount * VERTEX_STRIDE;

        // Создаем новый буфер увеличенной емкости
        this.allocateBuffer(newCapacity);

        // Копируем уже записанные вершины в новый буфер
        if (oldBytes > 0 && oldAddress != 0L) {
            MemoryUtil.memCopy(oldAddress, this.mappedAddress, oldBytes);
        }

        // Уничтожаем старый буфер
        if (oldAddress != 0L) {
            VK10.vkUnmapMemory(this.device, oldMemory);
        }
        if (oldBuffer != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyBuffer(this.device, oldBuffer, null);
        }
        if (oldMemory != VK10.VK_NULL_HANDLE) {
            VK10.vkFreeMemory(this.device, oldMemory, null);
        }

        this.vertexCapacity = newCapacity;
        LOGGER.debug("Vuldium Block Entity Batcher расширен до {} вершин", this.vertexCapacity);
    }

    /**
     * Начинает запись пакета геометрии динамических блоков для текущего кадра.
     */
    public synchronized void begin() {
        this.vertexCount = 0;
        this.isBuilding = true;
    }

    /**
     * Записывает одну вершину динамического блока в нативную память без JNI-накладных расходов.
     */
    public synchronized void vertex(
            float x, float y, float z,
            int colorRgba,
            float u, float v,
            int overlayUv,
            int lightUv,
            float normalX, float normalY, float normalZ
    ) {
        this.ensureCapacity(1);

        long ptr = this.mappedAddress + (long) this.vertexCount * VERTEX_STRIDE;

        // 1. Position 3D (12 байт, offset 0)
        MemoryUtil.memPutFloat(ptr, x);
        MemoryUtil.memPutFloat(ptr + 4, y);
        MemoryUtil.memPutFloat(ptr + 8, z);

        // 2. Color RGBA (4 байта, offset 12)
        MemoryUtil.memPutInt(ptr + 12, colorRgba);

        // 3. Texture UV (8 байт, offset 16)
        MemoryUtil.memPutFloat(ptr + 16, u);
        MemoryUtil.memPutFloat(ptr + 20, v);

        // 4. Overlay UV (4 байта, offset 24)
        MemoryUtil.memPutInt(ptr + 24, overlayUv);

        // 5. Light UV (4 байта, offset 28)
        MemoryUtil.memPutInt(ptr + 28, lightUv);

        // 6. Normal 3D + Padding (4 байта, offset 32)
        byte nx = (byte) ((int) (normalX * 127.0f) & 0xFF);
        byte ny = (byte) ((int) (normalY * 127.0f) & 0xFF);
        byte nz = (byte) ((int) (normalZ * 127.0f) & 0xFF);
        int packedNormal = (nx & 0xFF) | ((ny & 0xFF) << 8) | ((nz & 0xFF) << 16);
        MemoryUtil.memPutInt(ptr + 32, packedNormal);

        this.vertexCount++;
    }

    // =========================================================================
    // Реализация методов интерфейса VertexConsumer для интеграции с Minecraft
    // =========================================================================

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        this.currentX = x;
        this.currentY = y;
        this.currentZ = z;
        return this;
    }

    @Override
    public VertexConsumer setColor(int r, int g, int b, int a) {
        this.currentColor = (r & 0xFF) | ((g & 0xFF) << 8) | ((b & 0xFF) << 16) | ((a & 0xFF) << 24);
        return this;
    }

    public VertexConsumer setColor(int argb) {
        this.currentColor = argb;
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        this.currentU = u;
        this.currentV = v;
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        this.currentOverlay = (u & 0xFFFF) | ((v & 0xFFFF) << 16);
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        this.currentLight = (u & 0xFFFF) | ((v & 0xFFFF) << 16);
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        this.currentNormalX = x;
        this.currentNormalY = y;
        this.currentNormalZ = z;
        // Завершение формирования вершины при пошаговом вызове
        this.commitCurrentVertex();
        return this;
    }

    private void commitCurrentVertex() {
        this.vertex(
                this.currentX, this.currentY, this.currentZ,
                this.currentColor,
                this.currentU, this.currentV,
                this.currentOverlay,
                this.currentLight,
                this.currentNormalX, this.currentNormalY, this.currentNormalZ
        );
    }

    @Override
    public VertexConsumer setOverlay(int uv) {
        this.currentOverlay = uv;
        return this;
    }

    @Override
    public VertexConsumer setLight(int uv) {
        this.currentLight = uv;
        return this;
    }

    @Override
    public VertexConsumer setUv3(float u, float v) {
        return this;
    }

    @Override
    public VertexConsumer setLineWidth(float width) {
        return this;
    }

    @Override
    public void addVertex(
            float x, float y, float z,
            int color,
            float u, float v,
            int overlayCoords,
            int lightmap,
            float normalX, float normalY, float normalZ
    ) {
        this.vertex(x, y, z, color, u, v, overlayCoords, lightmap, normalX, normalY, normalZ);
    }

    /**
     * Завершает запись геометрии.
     */
    public synchronized void end() {
        this.isBuilding = false;
    }

    /**
     * Выполняет пакетный сброс (Flush) всей накопленной геометрии блоков в активный командный буфер.
     * Вызывается строго между фазой Opaque и фазой Translucent.
     * Включает проверку глубины и запись глубины (depthTestEnable = VK_TRUE, depthWriteEnable = VK_TRUE).
     *
     * @param cmd активный VkCommandBuffer прохода мира
     */
    public synchronized void flush(VkCommandBuffer cmd) {
        if (this.vertexCount <= 0 || this.vertexBuffer == VK10.VK_NULL_HANDLE) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pBuffers = stack.longs(this.vertexBuffer);
            LongBuffer pOffsets = stack.longs(0L);

            // Привязка единого вершинного буфера Block Entities
            VK10.vkCmdBindVertexBuffers(cmd, 0, pBuffers, pOffsets);

            // Единый пакетный вызов отрисовки геометрии динамических блоков
            VK10.vkCmdDraw(cmd, this.vertexCount, 1, 0, 0);
        }
    }

    public int getVertexCount() {
        return this.vertexCount;
    }

    public long getVertexBuffer() {
        return this.vertexBuffer;
    }

    public boolean isBuilding() {
        return this.isBuilding;
    }

    @Override
    public synchronized void close() {
        if (this.isClosed) {
            return;
        }

        if (this.mappedAddress != 0L) {
            VK10.vkUnmapMemory(this.device, this.vertexMemory);
            this.mappedAddress = 0L;
        }

        if (this.vertexBuffer != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyBuffer(this.device, this.vertexBuffer, null);
            this.vertexBuffer = VK10.VK_NULL_HANDLE;
        }

        if (this.vertexMemory != VK10.VK_NULL_HANDLE) {
            VK10.vkFreeMemory(this.device, this.vertexMemory, null);
            this.vertexMemory = VK10.VK_NULL_HANDLE;
        }

        this.isClosed = true;
        LOGGER.info("Vuldium Block Entity Batcher успешно закрыт.");
    }
}
