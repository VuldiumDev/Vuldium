package net.caffeinemc.mods.sodium.client.render.chunk.vulkan.mesher;

import net.caffeinemc.mods.sodium.client.gpu.arena.vulkan.VuldiumBufferArena;
import net.caffeinemc.mods.sodium.client.gpu.device.vulkan.VuldiumDeviceContext;
import net.caffeinemc.mods.sodium.client.gpu.device.vulkan.sync.VuldiumSync2;
import net.caffeinemc.mods.sodium.client.render.chunk.vulkan.pipeline.VuldiumShaderModule;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkBufferMemoryBarrier2;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

/**
 * Высокопроизводительный диспетчер вычислительного конвейера GPU-мешинга (VuldiumVoxelComputeDispatcher).
 *
 * Управляет выполнением вычислительного шейдера {@code voxel_mesher.comp} на ядрах GPU:
 * 1. Инициализирует Compute Pipeline с Dynamic Descriptors для Raw Palette SSBO, Vertex SSBO, Index SSBO
 *    и MeshAllocationCounter SSBO.
 * 2. Реализует синхронизацию через {@code VK_KHR_synchronization2} (без грубых конвейерных пузырей).
 * 3. Обеспечивает прямую генерацию команд {@code VkDrawIndexedIndirectCommand} прямо в памяти VRAM
 *    без возврата данных на CPU.
 */
public class VuldiumVoxelComputeDispatcher implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vuldium/ComputeDispatcher");

    public static final int SECTION_VOXELS = 16 * 16 * 16; // 4096 блоков
    public static final int PALETTE_SECTION_BYTES = SECTION_VOXELS * Integer.BYTES; // 16 КБ на секцию
    public static final int MAX_BATCH_SECTIONS = 64; // Пакетная обработка до 64 секций
    public static final int TOTAL_PALETTE_BUFFER_SIZE = PALETTE_SECTION_BYTES * MAX_BATCH_SECTIONS;

    public static final int INDEX_BUFFER_DEFAULT_SIZE = 16 * 1024 * 1024; // 16 МБ под индексы квадов (4 млн индексов)
    public static final int COUNTER_BUFFER_SIZE = 64; // Счетчики + DrawIndexedIndirectCommand (offset 16)
    public static final long INDIRECT_COMMAND_OFFSET = 16L;

    private final VuldiumDeviceContext context;
    private final VkDevice device;
    private final VuldiumBufferArena bufferArena;
    private final VuldiumSync2 sync2;
    private final boolean supportsSync2;

    private long descriptorPool = VK10.VK_NULL_HANDLE;
    private long descriptorSetLayout = VK10.VK_NULL_HANDLE;
    private long descriptorSet = VK10.VK_NULL_HANDLE;

    private long pipelineLayout = VK10.VK_NULL_HANDLE;
    private long pipeline = VK10.VK_NULL_HANDLE;

    // Входной буфер сырых палитр блоков (Host-Visible / Coherent)
    private long paletteBuffer = VK10.VK_NULL_HANDLE;
    private long paletteMemory = VK10.VK_NULL_HANDLE;
    private long pMappedPalette = MemoryUtil.NULL;

    // Выходной индексный буфер (Device-Local)
    private long indexBuffer = VK10.VK_NULL_HANDLE;
    private long indexMemory = VK10.VK_NULL_HANDLE;

    // Буфер атомарного счетчика и команды DrawIndexedIndirect (Device-Local)
    private long counterBuffer = VK10.VK_NULL_HANDLE;
    private long counterMemory = VK10.VK_NULL_HANDLE;

    private final ByteBuffer pushConstantBuffer = MemoryUtil.memAlloc(32);
    private boolean isClosed = false;

    public VuldiumVoxelComputeDispatcher(VuldiumDeviceContext context, VuldiumBufferArena bufferArena) {
        this.context = context;
        this.device = context.getLogicalDevice();
        this.bufferArena = bufferArena;
        this.sync2 = new VuldiumSync2(context);
        this.supportsSync2 = context.getDeviceCapabilities().apiVersion >= VK13.VK_API_VERSION_1_3 ||
                context.getDeviceCapabilities().VK_KHR_synchronization2;

        this.initDescriptorLayout();
        this.initPipeline();
        this.initBuffers();
        this.initDescriptors();

        LOGGER.info("Vuldium Voxel Compute Dispatcher успешно инициализирован (Sync2={}, BatchSections={}).",
                this.supportsSync2, MAX_BATCH_SECTIONS);
    }

    private void initDescriptorLayout() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(4, stack);

            // Binding 0: RawChunkPalette (SSBO Readonly)
            bindings.get(0)
                    .binding(0)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);

            // Binding 1: OutputVertexBuffer (SSBO Writeonly)
            bindings.get(1)
                    .binding(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);

            // Binding 2: OutputIndexBuffer (SSBO Writeonly)
            bindings.get(2)
                    .binding(2)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);

            // Binding 3: MeshAllocationCounter & DrawIndirect (SSBO Read-Write)
            bindings.get(3)
                    .binding(3)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(bindings);

            LongBuffer pLayout = stack.mallocLong(1);
            VK10.vkCreateDescriptorSetLayout(this.device, layoutInfo, null, pLayout);
            this.descriptorSetLayout = pLayout.get(0);
        }
    }

    private void initPipeline() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack);
            pcr.get(0)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                    .offset(0)
                    .size(32);

            LongBuffer pLayouts = stack.longs(this.descriptorSetLayout);
            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(pLayouts)
                    .pPushConstantRanges(pcr);

            LongBuffer pPipelineLayout = stack.mallocLong(1);
            VK10.vkCreatePipelineLayout(this.device, layoutInfo, null, pPipelineLayout);
            this.pipelineLayout = pPipelineLayout.get(0);

            try (VuldiumShaderModule shaderModule = VuldiumShaderModule.fromResource(
                    this.device, VK10.VK_SHADER_STAGE_COMPUTE_BIT, "/assets/sodium/shaders/compute/voxel_mesher.spv")) {
                VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
                        .stage(shaderModule.createStageInfo(stack, "main"))
                        .layout(this.pipelineLayout);

                LongBuffer pPipeline = stack.mallocLong(1);
                VK10.vkCreateComputePipelines(this.device, VK10.VK_NULL_HANDLE, pipelineInfo, null, pPipeline);
                this.pipeline = pPipeline.get(0);
            }
        } catch (Throwable t) {
            LOGGER.error("Не удалось создать Compute Pipeline для VuldiumVoxelComputeDispatcher: {}", t.getMessage(), t);
        }
    }

    private void initBuffers() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 1. Входной буфер сырых палитр (Host-Visible / Host-Coherent)
            VkBufferCreateInfo bufInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(TOTAL_PALETTE_BUFFER_SIZE)
                    .usage(VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);

            LongBuffer pBuffer = stack.mallocLong(1);
            VK10.vkCreateBuffer(this.device, bufInfo, null, pBuffer);
            this.paletteBuffer = pBuffer.get(0);

            VkMemoryRequirements reqs = VkMemoryRequirements.calloc(stack);
            VK10.vkGetBufferMemoryRequirements(this.device, this.paletteBuffer, reqs);

            int memProps = VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
            int memType = this.context.findMemoryTypeIndex(reqs.memoryTypeBits(), memProps);

            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(reqs.size())
                    .memoryTypeIndex(memType);

            LongBuffer pMem = stack.mallocLong(1);
            VK10.vkAllocateMemory(this.device, allocInfo, null, pMem);
            this.paletteMemory = pMem.get(0);
            VK10.vkBindBufferMemory(this.device, this.paletteBuffer, this.paletteMemory, 0);

            PointerBuffer pMap = stack.mallocPointer(1);
            VK10.vkMapMemory(this.device, this.paletteMemory, 0, TOTAL_PALETTE_BUFFER_SIZE, 0, pMap);
            this.pMappedPalette = pMap.get(0);

            // 2. Индексный буфер (Device-Local)
            bufInfo.size(INDEX_BUFFER_DEFAULT_SIZE)
                    .usage(VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT);

            VK10.vkCreateBuffer(this.device, bufInfo, null, pBuffer);
            this.indexBuffer = pBuffer.get(0);

            VK10.vkGetBufferMemoryRequirements(this.device, this.indexBuffer, reqs);
            memType = this.context.findMemoryTypeIndex(reqs.memoryTypeBits(), VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            allocInfo.allocationSize(reqs.size()).memoryTypeIndex(memType);

            VK10.vkAllocateMemory(this.device, allocInfo, null, pMem);
            this.indexMemory = pMem.get(0);
            VK10.vkBindBufferMemory(this.device, this.indexBuffer, this.indexMemory, 0);

            // 3. Буфер атомарных счетчиков и команды DrawIndexedIndirect (Device-Local)
            bufInfo.size(COUNTER_BUFFER_SIZE)
                    .usage(VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT);

            VK10.vkCreateBuffer(this.device, bufInfo, null, pBuffer);
            this.counterBuffer = pBuffer.get(0);

            VK10.vkGetBufferMemoryRequirements(this.device, this.counterBuffer, reqs);
            memType = this.context.findMemoryTypeIndex(reqs.memoryTypeBits(), VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            allocInfo.allocationSize(reqs.size()).memoryTypeIndex(memType);

            VK10.vkAllocateMemory(this.device, allocInfo, null, pMem);
            this.counterMemory = pMem.get(0);
            VK10.vkBindBufferMemory(this.device, this.counterBuffer, this.counterMemory, 0);
        }
    }

    private void initDescriptors() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
            poolSizes.get(0)
                    .type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(4);

            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .maxSets(1)
                    .pPoolSizes(poolSizes);

            LongBuffer pPool = stack.mallocLong(1);
            VK10.vkCreateDescriptorPool(this.device, poolInfo, null, pPool);
            this.descriptorPool = pPool.get(0);

            LongBuffer pLayouts = stack.longs(this.descriptorSetLayout);
            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(this.descriptorPool)
                    .pSetLayouts(pLayouts);

            LongBuffer pSet = stack.mallocLong(1);
            VK10.vkAllocateDescriptorSets(this.device, allocInfo, pSet);
            this.descriptorSet = pSet.get(0);

            // Привязка дескрипторов SSBO 0..3
            VkDescriptorBufferInfo.Buffer b0 = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(this.paletteBuffer)
                    .offset(0)
                    .range(VK10.VK_WHOLE_SIZE);

            VkDescriptorBufferInfo.Buffer b1 = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(this.bufferArena.getVkBufferHandle())
                    .offset(0)
                    .range(VK10.VK_WHOLE_SIZE);

            VkDescriptorBufferInfo.Buffer b2 = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(this.indexBuffer)
                    .offset(0)
                    .range(VK10.VK_WHOLE_SIZE);

            VkDescriptorBufferInfo.Buffer b3 = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(this.counterBuffer)
                    .offset(0)
                    .range(VK10.VK_WHOLE_SIZE);

            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(4, stack);
            writes.get(0)
                    .sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(this.descriptorSet)
                    .dstBinding(0)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .pBufferInfo(b0);

            writes.get(1)
                    .sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(this.descriptorSet)
                    .dstBinding(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .pBufferInfo(b1);

            writes.get(2)
                    .sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(this.descriptorSet)
                    .dstBinding(2)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .pBufferInfo(b2);

            writes.get(3)
                    .sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(this.descriptorSet)
                    .dstBinding(3)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .pBufferInfo(b3);

            VK10.vkUpdateDescriptorSets(this.device, writes, null);
        }
    }

    /**
     * Загружает сырую палитру секции чанка в Host-Visible память без GC-аллокаций.
     */
    public void uploadPalette(int batchSectionIndex, int[] blockStates) {
        if (this.pMappedPalette == MemoryUtil.NULL || blockStates == null) {
            return;
        }
        int clampedIdx = Math.max(0, Math.min(batchSectionIndex, MAX_BATCH_SECTIONS - 1));
        long targetAddress = this.pMappedPalette + ((long) clampedIdx * PALETTE_SECTION_BYTES);
        int count = Math.min(blockStates.length, SECTION_VOXELS);

        MemoryUtil.memCopy(
                MemoryUtil.memAddress(IntBuffer.wrap(blockStates)),
                targetAddress,
                (long) count * Integer.BYTES
        );
    }

    /**
     * Выполняет диспетчеризацию вычислений мешинга пакета секций на GPU.
     *
     * @param cmd          активный командный буфер
     * @param sectionCount количество секций в пакете
     */
    public void dispatchMeshing(VkCommandBuffer cmd, int sectionCount) {
        if (cmd == null || this.pipeline == VK10.VK_NULL_HANDLE || sectionCount <= 0) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 1. Барьер до вызова: буфер палитр переходит в режим чтения вычислительным шейдером
            if (this.supportsSync2) {
                VkBufferMemoryBarrier2.Buffer preBarrier = VkBufferMemoryBarrier2.calloc(1, stack)
                        .sType(VK13.VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER_2)
                        .srcStageMask(VK13.VK_PIPELINE_STAGE_2_HOST_BIT | VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT)
                        .srcAccessMask(VK13.VK_ACCESS_2_HOST_WRITE_BIT | VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT)
                        .dstStageMask(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                        .dstAccessMask(VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT)
                        .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .buffer(this.paletteBuffer)
                        .offset(0L)
                        .size(VK10.VK_WHOLE_SIZE);

                VkDependencyInfo depInfo = VkDependencyInfo.calloc(stack)
                        .sType(VK13.VK_STRUCTURE_TYPE_DEPENDENCY_INFO)
                        .pBufferMemoryBarriers(preBarrier);

                VK13.vkCmdPipelineBarrier2(cmd, depInfo);
            } else {
                VkBufferMemoryBarrier.Buffer preBarrier = VkBufferMemoryBarrier.calloc(1, stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                        .srcAccessMask(VK10.VK_ACCESS_HOST_WRITE_BIT | VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                        .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT)
                        .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .buffer(this.paletteBuffer)
                        .offset(0L)
                        .size(VK10.VK_WHOLE_SIZE);

                VK10.vkCmdPipelineBarrier(cmd,
                        VK10.VK_PIPELINE_STAGE_HOST_BIT | VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        0, null, preBarrier, null);
            }

            // 2. Сброс счетчиков вершин и команды DrawIndexedIndirect
            VK10.vkCmdFillBuffer(cmd, this.counterBuffer, 0, COUNTER_BUFFER_SIZE, 0);

            // 3. Привязка Compute Pipeline и дескрипторов
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, this.pipeline);
            LongBuffer pSets = stack.longs(this.descriptorSet);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, this.pipelineLayout, 0, pSets, null);

            // 4. Диспетчеризация Compute Wavefronts: (sectionCount + 7) / 8
            int dispatchX = (sectionCount + 7) / 8;
            VK10.vkCmdDispatch(cmd, Math.max(1, dispatchX), 1, 1);

            // 5. Барьер после вызова: переход выходного вершинного и индексного буферов
            //    из SHADER_WRITE в VERTEX_ATTRIBUTE_READ и INDEX_READ для этапа VERTEX_INPUT.
            //    Команда Indirect Draw переходит в INDIRECT_COMMAND_READ для DRAW_INDIRECT.
            if (this.supportsSync2) {
                VkBufferMemoryBarrier2.Buffer postBarriers = VkBufferMemoryBarrier2.calloc(3, stack);

                // Вершинный буфер
                postBarriers.get(0)
                        .sType(VK13.VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER_2)
                        .srcStageMask(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                        .srcAccessMask(VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT)
                        .dstStageMask(VK13.VK_PIPELINE_STAGE_2_VERTEX_ATTRIBUTE_INPUT_BIT)
                        .dstAccessMask(VK13.VK_ACCESS_2_VERTEX_ATTRIBUTE_READ_BIT)
                        .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .buffer(this.bufferArena.getVkBufferHandle())
                        .offset(0L)
                        .size(VK10.VK_WHOLE_SIZE);

                // Индексный буфер
                postBarriers.get(1)
                        .sType(VK13.VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER_2)
                        .srcStageMask(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                        .srcAccessMask(VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT)
                        .dstStageMask(VK13.VK_PIPELINE_STAGE_2_INDEX_INPUT_BIT)
                        .dstAccessMask(VK13.VK_ACCESS_2_INDEX_READ_BIT)
                        .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .buffer(this.indexBuffer)
                        .offset(0L)
                        .size(VK10.VK_WHOLE_SIZE);

                // Indirect Draw Command буфер
                postBarriers.get(2)
                        .sType(VK13.VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER_2)
                        .srcStageMask(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                        .srcAccessMask(VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT)
                        .dstStageMask(VK13.VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT)
                        .dstAccessMask(VK13.VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT)
                        .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .buffer(this.counterBuffer)
                        .offset(INDIRECT_COMMAND_OFFSET)
                        .size(20L); // VkDrawIndexedIndirectCommand = 5 uints = 20 байт

                VkDependencyInfo depInfo = VkDependencyInfo.calloc(stack)
                        .sType(VK13.VK_STRUCTURE_TYPE_DEPENDENCY_INFO)
                        .pBufferMemoryBarriers(postBarriers);

                VK13.vkCmdPipelineBarrier2(cmd, depInfo);
            } else {
                VkBufferMemoryBarrier.Buffer postBarriers = VkBufferMemoryBarrier.calloc(3, stack);

                postBarriers.get(0)
                        .sType(VK10.VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                        .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                        .dstAccessMask(VK10.VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT)
                        .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .buffer(this.bufferArena.getVkBufferHandle())
                        .offset(0L)
                        .size(VK10.VK_WHOLE_SIZE);

                postBarriers.get(1)
                        .sType(VK10.VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                        .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                        .dstAccessMask(VK10.VK_ACCESS_INDEX_READ_BIT)
                        .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .buffer(this.indexBuffer)
                        .offset(0L)
                        .size(VK10.VK_WHOLE_SIZE);

                postBarriers.get(2)
                        .sType(VK10.VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                        .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                        .dstAccessMask(VK10.VK_ACCESS_INDIRECT_COMMAND_READ_BIT)
                        .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .buffer(this.counterBuffer)
                        .offset(INDIRECT_COMMAND_OFFSET)
                        .size(20L);

                VK10.vkCmdPipelineBarrier(cmd,
                        VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        VK10.VK_PIPELINE_STAGE_VERTEX_INPUT_BIT | VK10.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,
                        0, null, postBarriers, null);
            }
        }
    }

    /**
     * Одиночная диспетчеризация конкретной секции по мировым координатам.
     */
    public void dispatchMeshingSingle(VkCommandBuffer cmd, int sectionX, int sectionY, int sectionZ, int sectionIndex) {
        if (cmd == null || this.pipeline == VK10.VK_NULL_HANDLE) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            this.pushConstantBuffer.clear();
            this.pushConstantBuffer.putInt(sectionX).putInt(sectionY).putInt(sectionZ).putInt(sectionIndex);
            this.pushConstantBuffer.putInt(0); // Material bits
            this.pushConstantBuffer.putInt(0); // Light data
            this.pushConstantBuffer.position(0);

            VK10.vkCmdPushConstants(cmd, this.pipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, this.pushConstantBuffer);
            this.dispatchMeshing(cmd, 1);
        }
    }

    public long getPaletteBuffer() {
        return this.paletteBuffer;
    }

    public long getIndexBuffer() {
        return this.indexBuffer;
    }

    public long getCounterBuffer() {
        return this.counterBuffer;
    }

    public long getIndirectCommandBufferOffset() {
        return INDIRECT_COMMAND_OFFSET;
    }

    @Override
    public synchronized void close() {
        if (this.isClosed) {
            return;
        }

        this.context.waitIdle();

        if (this.pMappedPalette != MemoryUtil.NULL) {
            VK10.vkUnmapMemory(this.device, this.paletteMemory);
            this.pMappedPalette = MemoryUtil.NULL;
        }

        if (this.paletteBuffer != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyBuffer(this.device, this.paletteBuffer, null);
            this.paletteBuffer = VK10.VK_NULL_HANDLE;
        }
        if (this.paletteMemory != VK10.VK_NULL_HANDLE) {
            VK10.vkFreeMemory(this.device, this.paletteMemory, null);
            this.paletteMemory = VK10.VK_NULL_HANDLE;
        }

        if (this.indexBuffer != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyBuffer(this.device, this.indexBuffer, null);
            this.indexBuffer = VK10.VK_NULL_HANDLE;
        }
        if (this.indexMemory != VK10.VK_NULL_HANDLE) {
            VK10.vkFreeMemory(this.device, this.indexMemory, null);
            this.indexMemory = VK10.VK_NULL_HANDLE;
        }

        if (this.counterBuffer != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyBuffer(this.device, this.counterBuffer, null);
            this.counterBuffer = VK10.VK_NULL_HANDLE;
        }
        if (this.counterMemory != VK10.VK_NULL_HANDLE) {
            VK10.vkFreeMemory(this.device, this.counterMemory, null);
            this.counterMemory = VK10.VK_NULL_HANDLE;
        }

        if (this.descriptorPool != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyDescriptorPool(this.device, this.descriptorPool, null);
            this.descriptorPool = VK10.VK_NULL_HANDLE;
        }
        if (this.descriptorSetLayout != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyDescriptorSetLayout(this.device, this.descriptorSetLayout, null);
            this.descriptorSetLayout = VK10.VK_NULL_HANDLE;
        }

        if (this.pipeline != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyPipeline(this.device, this.pipeline, null);
            this.pipeline = VK10.VK_NULL_HANDLE;
        }
        if (this.pipelineLayout != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyPipelineLayout(this.device, this.pipelineLayout, null);
            this.pipelineLayout = VK10.VK_NULL_HANDLE;
        }

        MemoryUtil.memFree(this.pushConstantBuffer);
        this.isClosed = true;

        LOGGER.info("Vuldium Voxel Compute Dispatcher успешно закрыт.");
    }
}
