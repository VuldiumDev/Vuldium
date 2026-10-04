package net.caffeinemc.mods.sodium.client.render.chunk.vulkan.culling;

import net.caffeinemc.mods.sodium.client.gpu.device.vulkan.VuldiumDeviceContext;
import net.caffeinemc.mods.sodium.client.gpu.device.vulkan.sync.VuldiumSync2;
import net.caffeinemc.mods.sodium.client.render.chunk.vulkan.pipeline.VuldiumShaderModule;
import org.joml.Vector4f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDrawIndexedIndirectCommand;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

/**
 * Высокопроизводительный GPU-Driven Culler на базе Compute Shaders и Draw Indexed Indirect Count.
 * Полностью разгружает CPU от проверки видимости секций чанков.
 *
 * Архитектура:
 * 1. Вход: SSBO ChunkCandidate (48 байт на секцию).
 * 2. Вычисления: Compute Shader (локальный размер 64) тестирует AABB секций против 6 плоскостей пирамиды видимости.
 * 3. Выход: SSBO VkDrawIndexedIndirectCommand (20 байт) + атомарный счетчик видимых вызовов в DrawCountBuffer.
 * 4. Отрисовка: мгновенный vkCmdDrawIndexedIndirectCount без чтения данных обратно на CPU (Zero Host Readback).
 */
public class VuldiumGpuCuller implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vuldium/GpuCuller");

    public static final int CANDIDATE_STRIDE = 48;
    public static final int PUSH_CONSTANTS_SIZE = 128; // 6 плоскостей * 16 байт = 96 байт + 4 байта totalChunks + паддинг
    private static final int DEFAULT_MAX_CHUNKS = 65536;

    private final VuldiumDeviceContext context;
    private final VkDevice device;
    private final VuldiumSync2 sync2;

    private int maxChunks;

    // Ресурсы дескрипторов
    private long descriptorPool = VK10.VK_NULL_HANDLE;
    private long descriptorSetLayout = VK10.VK_NULL_HANDLE;
    private long descriptorSet = VK10.VK_NULL_HANDLE;

    // Ресурсы Compute Pipeline
    private long computePipelineLayout = VK10.VK_NULL_HANDLE;
    private long computePipeline = VK10.VK_NULL_HANDLE;

    // Буфер 0: Входные кандидаты секций (SSBO, Host-Visible / Coherent для быстрой записи)
    private long candidatesBuffer = VK10.VK_NULL_HANDLE;
    private long candidatesMemory = VK10.VK_NULL_HANDLE;
    private long pMappedCandidates = MemoryUtil.NULL;

    // Буфер 1: Выходной буфер непрямых команд (SSBO + Indirect Buffer, Device-Local)
    private long commandsBuffer = VK10.VK_NULL_HANDLE;
    private long commandsMemory = VK10.VK_NULL_HANDLE;

    // Буфер 2: Атомарный счетчик видимых вызовов (SSBO + Indirect Count Buffer, Device-Local)
    private long countBuffer = VK10.VK_NULL_HANDLE;
    private long countMemory = VK10.VK_NULL_HANDLE;

    // Предвыделенный офф-хип буфер Push Constants для предотвращения JVM аллокаций
    private final ByteBuffer pushConstantBuffer = MemoryUtil.memAlloc(PUSH_CONSTANTS_SIZE);
    private boolean isClosed = false;

    public static VuldiumGpuCuller create(VuldiumDeviceContext context, int initialMaxChunks) {
        try (VuldiumShaderModule module = VuldiumShaderModule.fromResource(
                context.getLogicalDevice(),
                VK10.VK_SHADER_STAGE_COMPUTE_BIT,
                "/assets/sodium/shaders/compute/chunk_cull.spv")) {
            return new VuldiumGpuCuller(context, module, initialMaxChunks);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Не удалось загрузить SPIR-V шейдер chunk_cull.spv", e);
        }
    }

    public VuldiumGpuCuller(VuldiumDeviceContext context, VuldiumShaderModule cullComputeShader, int initialMaxChunks) {
        this.context = context;
        this.device = context.getLogicalDevice();
        this.sync2 = new VuldiumSync2(context);
        this.maxChunks = Math.max(initialMaxChunks, DEFAULT_MAX_CHUNKS);

        this.initDescriptorLayout();
        this.initPipeline(cullComputeShader);
        this.initBuffers(this.maxChunks);
        this.initDescriptorPoolAndSet();
        this.updateDescriptorSets();

        LOGGER.info("Vuldium GPU Culler инициализирован: MaxChunks={}", this.maxChunks);
    }

    private void initDescriptorLayout() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(3, stack);

            // Binding 0: CandidatesBuffer (Readonly SSBO)
            bindings.get(0)
                    .binding(0)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);

            // Binding 1: OutputCommandsBuffer (Writeonly SSBO)
            bindings.get(1)
                    .binding(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);

            // Binding 2: DrawCountBuffer (Atomic Counter SSBO)
            bindings.get(2)
                    .binding(2)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(bindings);

            LongBuffer pLayout = stack.mallocLong(1);
            int res = VK10.vkCreateDescriptorSetLayout(this.device, layoutInfo, null, pLayout);
            if (res != VK10.VK_SUCCESS) {
                throw new IllegalStateException("Ошибка создания VkDescriptorSetLayout для Culler: " + res);
            }
            this.descriptorSetLayout = pLayout.get(0);
        }
    }

    private void initPipeline(VuldiumShaderModule cullComputeShader) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPushConstantRange.Buffer pushConstants = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                    .offset(0)
                    .size(PUSH_CONSTANTS_SIZE);

            LongBuffer pSetLayouts = stack.longs(this.descriptorSetLayout);
            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(pSetLayouts)
                    .pPushConstantRanges(pushConstants);

            LongBuffer pPipelineLayout = stack.mallocLong(1);
            int res = VK10.vkCreatePipelineLayout(this.device, layoutInfo, null, pPipelineLayout);
            if (res != VK10.VK_SUCCESS) {
                throw new IllegalStateException("Ошибка создания VkPipelineLayout для Culler: " + res);
            }
            this.computePipelineLayout = pPipelineLayout.get(0);

            VkPipelineShaderStageCreateInfo stageInfo = cullComputeShader.createStageInfo(stack, "main");

            VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
                    .stage(stageInfo)
                    .layout(this.computePipelineLayout);

            LongBuffer pPipeline = stack.mallocLong(1);
            res = VK10.vkCreateComputePipelines(this.device, VK10.VK_NULL_HANDLE, pipelineInfo, null, pPipeline);
            if (res != VK10.VK_SUCCESS) {
                throw new IllegalStateException("Ошибка компиляции VkComputePipeline для Culler: " + res);
            }
            this.computePipeline = pPipeline.get(0);
        }
    }

    private void initBuffers(int chunks) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 1. Candidates Buffer (Host-Visible & Coherent)
            long candidatesSize = (long) chunks * CANDIDATE_STRIDE;
            VkBufferCreateInfo candidatesInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(candidatesSize)
                    .usage(VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)
                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);

            LongBuffer pBuf = stack.mallocLong(1);
            VK10.vkCreateBuffer(this.device, candidatesInfo, null, pBuf);
            this.candidatesBuffer = pBuf.get(0);

            VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
            VK10.vkGetBufferMemoryRequirements(this.device, this.candidatesBuffer, memReqs);

            int memTypeIndex = this.context.findMemoryTypeIndex(
                    memReqs.memoryTypeBits(),
                    VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
            );

            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(memReqs.size())
                    .memoryTypeIndex(memTypeIndex);

            LongBuffer pMem = stack.mallocLong(1);
            VK10.vkAllocateMemory(this.device, allocInfo, null, pMem);
            this.candidatesMemory = pMem.get(0);
            VK10.vkBindBufferMemory(this.device, this.candidatesBuffer, this.candidatesMemory, 0);

            PointerBuffer pData = stack.mallocPointer(1);
            VK10.vkMapMemory(this.device, this.candidatesMemory, 0, candidatesSize, 0, pData);
            this.pMappedCandidates = pData.get(0);

            // 2. Output Commands Buffer (Device-Local)
            long commandsSize = (long) chunks * VkDrawIndexedIndirectCommand.SIZEOF;
            VkBufferCreateInfo commandsInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(commandsSize)
                    .usage(VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT)
                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);

            VK10.vkCreateBuffer(this.device, commandsInfo, null, pBuf);
            this.commandsBuffer = pBuf.get(0);

            VK10.vkGetBufferMemoryRequirements(this.device, this.commandsBuffer, memReqs);
            memTypeIndex = this.context.findMemoryTypeIndex(memReqs.memoryTypeBits(), VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);

            allocInfo.allocationSize(memReqs.size()).memoryTypeIndex(memTypeIndex);
            VK10.vkAllocateMemory(this.device, allocInfo, null, pMem);
            this.commandsMemory = pMem.get(0);
            VK10.vkBindBufferMemory(this.device, this.commandsBuffer, this.commandsMemory, 0);

            // 3. Count Buffer (Device-Local, 4 байта + трансфер для vkCmdFillBuffer)
            VkBufferCreateInfo countInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(Integer.BYTES)
                    .usage(VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT)
                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);

            VK10.vkCreateBuffer(this.device, countInfo, null, pBuf);
            this.countBuffer = pBuf.get(0);

            VK10.vkGetBufferMemoryRequirements(this.device, this.countBuffer, memReqs);
            memTypeIndex = this.context.findMemoryTypeIndex(memReqs.memoryTypeBits(), VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);

            allocInfo.allocationSize(memReqs.size()).memoryTypeIndex(memTypeIndex);
            VK10.vkAllocateMemory(this.device, allocInfo, null, pMem);
            this.countMemory = pMem.get(0);
            VK10.vkBindBufferMemory(this.device, this.countBuffer, this.countMemory, 0);
        }
    }

    private void initDescriptorPoolAndSet() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
            poolSizes.get(0)
                    .type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(3);

            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .maxSets(1)
                    .pPoolSizes(poolSizes);

            LongBuffer pPool = stack.mallocLong(1);
            int res = VK10.vkCreateDescriptorPool(this.device, poolInfo, null, pPool);
            if (res != VK10.VK_SUCCESS) {
                throw new IllegalStateException("Ошибка создания VkDescriptorPool для Culler: " + res);
            }
            this.descriptorPool = pPool.get(0);

            LongBuffer pSetLayouts = stack.longs(this.descriptorSetLayout);
            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(this.descriptorPool)
                    .pSetLayouts(pSetLayouts);

            LongBuffer pSet = stack.mallocLong(1);
            res = VK10.vkAllocateDescriptorSets(this.device, allocInfo, pSet);
            if (res != VK10.VK_SUCCESS) {
                throw new IllegalStateException("Ошибка аллокации VkDescriptorSet для Culler: " + res);
            }
            this.descriptorSet = pSet.get(0);
        }
    }

    private void updateDescriptorSets() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(3, stack);

            // Write 0: CandidatesBuffer
            VkDescriptorBufferInfo.Buffer bufInfo0 = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(this.candidatesBuffer)
                    .offset(0)
                    .range(VK10.VK_WHOLE_SIZE);
            writes.get(0)
                    .sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(this.descriptorSet)
                    .dstBinding(0)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .pBufferInfo(bufInfo0);

            // Write 1: CommandsBuffer
            VkDescriptorBufferInfo.Buffer bufInfo1 = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(this.commandsBuffer)
                    .offset(0)
                    .range(VK10.VK_WHOLE_SIZE);
            writes.get(1)
                    .sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(this.descriptorSet)
                    .dstBinding(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .pBufferInfo(bufInfo1);

            // Write 2: CountBuffer
            VkDescriptorBufferInfo.Buffer bufInfo2 = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(this.countBuffer)
                    .offset(0)
                    .range(VK10.VK_WHOLE_SIZE);
            writes.get(2)
                    .sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(this.descriptorSet)
                    .dstBinding(2)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .pBufferInfo(bufInfo2);

            VK10.vkUpdateDescriptorSets(this.device, writes, null);
        }
    }

    /**
     * Возвращает базовый адрес отображенной памяти входного буфера кандидатов.
     * Позволяет выполнять прямое JNI/Unsafe заполнение структур ChunkCandidate без аллокаций.
     */
    public long getMappedCandidatesAddress() {
        return this.pMappedCandidates;
    }

    /**
     * Записывает вычисления GPU-Driven Culling в командный буфер (должен вызываться ВНЕ render pass).
     *
     * @param cmdBuf         активный VkCommandBuffer
     * @param frustumPlanes  6 плоскостей усеченной пирамиды
     * @param totalChunks    количество секций чанков для проверки
     */
    public void recordCulling(
            VkCommandBuffer cmdBuf,
            Vector4f[] frustumPlanes,
            int totalChunks
    ) {
        if (totalChunks <= 0) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 1. Аппаратный сброс атомарного счетчика видимых чанков на GPU за один вызов
            VK10.vkCmdFillBuffer(cmdBuf, this.countBuffer, 0, Integer.BYTES, 0);

            // Барьер: сброс счетчика (Transfer Write) -> чтение/запись в Compute Shader
            VkBufferMemoryBarrier.Buffer fillBarrier = VkBufferMemoryBarrier.calloc(1, stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                    .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .buffer(this.countBuffer)
                    .offset(0)
                    .size(Integer.BYTES);

            VK10.vkCmdPipelineBarrier(
                    cmdBuf,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0,
                    null,
                    fillBarrier,
                    null
            );

            // 2. Привязка Compute Pipeline и дескрипторов
            VK10.vkCmdBindPipeline(cmdBuf, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, this.computePipeline);
            LongBuffer pSets = stack.longs(this.descriptorSet);
            VK10.vkCmdBindDescriptorSets(cmdBuf, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, this.computePipelineLayout, 0, pSets, null);

            // 3. Формирование Push Constants (6 плоскостей + totalChunks) без JVM аллокаций
            this.pushConstantBuffer.clear();
            for (int i = 0; i < 6; i++) {
                Vector4f p = frustumPlanes[i];
                this.pushConstantBuffer.putFloat(p.x).putFloat(p.y).putFloat(p.z).putFloat(p.w);
            }
            this.pushConstantBuffer.putInt(totalChunks);
            this.pushConstantBuffer.position(0);

            VK10.vkCmdPushConstants(cmdBuf, this.computePipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, this.pushConstantBuffer);

            // 4. Диспетчеризация Compute Shader: local_size_x = 64
            int groupCountX = (totalChunks + 63) / 64;
            VK10.vkCmdDispatch(cmdBuf, groupCountX, 1, 1);

            // 5. Тонкий барьер памяти: Compute Write -> Draw Indirect Read через Synchronization2
            this.sync2.barrierComputeToIndirect(cmdBuf, this.commandsBuffer, this.countBuffer);
        }
    }

    /**
     * Выполняет непрямую отрисовку с динамическим счетчиком на GPU (вызывается ВНУТРИ активного render pass).
     *
     * @param cmdBuf       активный VkCommandBuffer прохода рендеринга
     * @param maxDrawCount максимальное число вызовов (totalChunks)
     */
    public void recordDrawIndirectCount(VkCommandBuffer cmdBuf, int maxDrawCount) {
        if (maxDrawCount <= 0) {
            return;
        }

        VK12.vkCmdDrawIndexedIndirectCount(
                cmdBuf,
                this.commandsBuffer,
                0L,
                this.countBuffer,
                0L,
                maxDrawCount,
                VkDrawIndexedIndirectCommand.SIZEOF
        );
    }

    /**
     * Удобный композитный метод: Culling Compute + Indirect Count Draw (если контекст позволяет оба вызова).
     */
    public void recordCullingAndDraw(
            VkCommandBuffer cmdBuf,
            Vector4f[] frustumPlanes,
            int totalChunks
    ) {
        if (totalChunks <= 0) {
            return;
        }
        this.recordCulling(cmdBuf, frustumPlanes, totalChunks);
        this.recordDrawIndirectCount(cmdBuf, totalChunks);
    }

    public long getCandidatesBuffer() {
        return this.candidatesBuffer;
    }

    public long getCommandsBuffer() {
        return this.commandsBuffer;
    }

    public long getCountBuffer() {
        return this.countBuffer;
    }

    @Override
    public synchronized void close() {
        if (this.isClosed) {
            return;
        }

        this.context.waitIdle();

        if (this.pMappedCandidates != MemoryUtil.NULL) {
            VK10.vkUnmapMemory(this.device, this.candidatesMemory);
            this.pMappedCandidates = MemoryUtil.NULL;
        }

        if (this.candidatesBuffer != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyBuffer(this.device, this.candidatesBuffer, null);
            this.candidatesBuffer = VK10.VK_NULL_HANDLE;
        }
        if (this.candidatesMemory != VK10.VK_NULL_HANDLE) {
            VK10.vkFreeMemory(this.device, this.candidatesMemory, null);
            this.candidatesMemory = VK10.VK_NULL_HANDLE;
        }

        if (this.commandsBuffer != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyBuffer(this.device, this.commandsBuffer, null);
            this.commandsBuffer = VK10.VK_NULL_HANDLE;
        }
        if (this.commandsMemory != VK10.VK_NULL_HANDLE) {
            VK10.vkFreeMemory(this.device, this.commandsMemory, null);
            this.commandsMemory = VK10.VK_NULL_HANDLE;
        }

        if (this.countBuffer != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyBuffer(this.device, this.countBuffer, null);
            this.countBuffer = VK10.VK_NULL_HANDLE;
        }
        if (this.countMemory != VK10.VK_NULL_HANDLE) {
            VK10.vkFreeMemory(this.device, this.countMemory, null);
            this.countMemory = VK10.VK_NULL_HANDLE;
        }

        if (this.descriptorPool != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyDescriptorPool(this.device, this.descriptorPool, null);
            this.descriptorPool = VK10.VK_NULL_HANDLE;
        }
        if (this.descriptorSetLayout != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyDescriptorSetLayout(this.device, this.descriptorSetLayout, null);
            this.descriptorSetLayout = VK10.VK_NULL_HANDLE;
        }

        if (this.computePipeline != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyPipeline(this.device, this.computePipeline, null);
            this.computePipeline = VK10.VK_NULL_HANDLE;
        }
        if (this.computePipelineLayout != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyPipelineLayout(this.device, this.computePipelineLayout, null);
            this.computePipelineLayout = VK10.VK_NULL_HANDLE;
        }

        MemoryUtil.memFree(this.pushConstantBuffer);
        this.isClosed = true;

        LOGGER.info("Vuldium GPU Culler успешно закрыт.");
    }
}
