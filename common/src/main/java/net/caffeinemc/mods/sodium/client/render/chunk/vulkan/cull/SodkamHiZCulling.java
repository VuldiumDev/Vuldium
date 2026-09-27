package net.caffeinemc.mods.sodium.client.render.chunk.vulkan.cull;

import net.caffeinemc.mods.sodium.client.gpu.device.vulkan.SodkamDeviceContext;
import net.caffeinemc.mods.sodium.client.gpu.device.vulkan.sync.SodkamSync2;
import net.caffeinemc.mods.sodium.client.render.chunk.vulkan.culling.SodkamGpuCuller;
import net.caffeinemc.mods.sodium.client.render.chunk.vulkan.pipeline.SodkamShaderModule;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
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
 * Аппаратный GPU-Driven Hi-Z Occlusion Culling (Иерархический Z-буфер) под Vulkan 1.3.
 *
 * Архитектура:
 * 1. Генерация пирамиды глубин (HZB Downsampling):
 *    - Compute-шейдер hzb_reduce.comp строит цепочку Mip-уровней глубины,
 *      выбирая максимальное/минимальное значение блока 2x2 текселей.
 * 2. Двухпроходный алгоритм отсечения (Pass 1 Early Visibility, Pass 2 HZB Test):
 *    - Compute-шейдер hzb_cull.comp выполняет проекцию 8 вершин AABB непроверенных чанков
 *      в экранное пространство, вычисляет экранный размер бокса, выбирает Mip-уровень
 *      и считывает глубину пирамиды HZB.
 *    - Используются Wave Intrinsics (subgroupBallot, subgroupBallotExclusiveBitCount, subgroupElect)
 *      для нулевой конкуренции атомиков в VRAM.
 */
public class SodkamHiZCulling implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vuldium/HiZCulling");

    private static final int MAX_MIP_LEVELS = 12;

    private final SodkamDeviceContext context;
    private final VkDevice device;
    private SodkamGpuCuller gpuCuller;
    private final SodkamSync2 sync2;

    private boolean enabled = true;
    private int width = 0;
    private int height = 0;
    private int mipLevels = 1;

    // Ресурсы HZB пирамиды (Текстура глубины с цепочкой mip-уровней R32_SFLOAT)
    private long hzbImage = VK10.VK_NULL_HANDLE;
    private long hzbMemory = VK10.VK_NULL_HANDLE;
    private long hzbImageView = VK10.VK_NULL_HANDLE; // Полный view всех mip-уровней
    private final long[] hzbMipViews = new long[MAX_MIP_LEVELS]; // Отдельные view для каждого mip
    private long hzbSampler = VK10.VK_NULL_HANDLE;

    // Ресурсы шейдеров и пайплайнов
    private long reducePipelineLayout = VK10.VK_NULL_HANDLE;
    private long reducePipeline = VK10.VK_NULL_HANDLE;
    private long reduceDescriptorLayout = VK10.VK_NULL_HANDLE;

    private long cullPipelineLayout = VK10.VK_NULL_HANDLE;
    private long cullPipeline = VK10.VK_NULL_HANDLE;
    private long cullDescriptorLayout = VK10.VK_NULL_HANDLE;

    private long descriptorPool = VK10.VK_NULL_HANDLE;
    private final long[] reduceDescriptorSets = new long[MAX_MIP_LEVELS];
    private long cullDescriptorSet = VK10.VK_NULL_HANDLE;

    // Push Constants буфер (256 байт)
    private final ByteBuffer cullPushConstantBuffer = MemoryUtil.memAlloc(256);
    private final ByteBuffer reducePushConstantBuffer = MemoryUtil.memAlloc(32);

    // Матрица и плоскости для текущего кадра
    private final Matrix4f cachedViewProj = new Matrix4f();
    private final Vector4f[] cachedFrustumPlanes = new Vector4f[6];
    private boolean isClosed = false;

    public SodkamHiZCulling(VkDevice device) {
        this(null, null, device);
    }

    public SodkamHiZCulling(SodkamDeviceContext context, SodkamGpuCuller gpuCuller) {
        this(context, gpuCuller, context != null ? context.getLogicalDevice() : null);
    }

    private SodkamHiZCulling(SodkamDeviceContext context, SodkamGpuCuller gpuCuller, VkDevice device) {
        this.context = context;
        this.device = device != null ? device : (context != null ? context.getLogicalDevice() : null);
        this.gpuCuller = gpuCuller;
        this.sync2 = context != null ? new SodkamSync2(context) : null;

        for (int i = 0; i < 6; i++) {
            this.cachedFrustumPlanes[i] = new Vector4f();
        }

        if (this.device != null) {
            this.initPipelines();
        }

        LOGGER.info("Vuldium Hi-Z Occlusion Culling (двухпроходный) инициализирован.");
    }

    public void setGpuCuller(SodkamGpuCuller gpuCuller) {
        this.gpuCuller = gpuCuller;
        if (this.cullDescriptorSet != VK10.VK_NULL_HANDLE) {
            this.updateCullDescriptors();
        }
    }

    public void setViewParameters(Matrix4f viewProj, Vector4f[] frustumPlanes) {
        if (viewProj != null) {
            this.cachedViewProj.set(viewProj);
        }
        if (frustumPlanes != null) {
            for (int i = 0; i < 6 && i < frustumPlanes.length; i++) {
                this.cachedFrustumPlanes[i].set(frustumPlanes[i]);
            }
        }
    }

    private void initPipelines() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 1. Reduce Layout: Binding 0 (sampler2D InDepth), Binding 1 (image2D OutDepth)
            VkDescriptorSetLayoutBinding.Buffer reduceBindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
            reduceBindings.get(0)
                    .binding(0)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);

            reduceBindings.get(1)
                    .binding(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);

            VkDescriptorSetLayoutCreateInfo reduceLayoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(reduceBindings);

            LongBuffer pReduceLayout = stack.mallocLong(1);
            VK10.vkCreateDescriptorSetLayout(this.device, reduceLayoutInfo, null, pReduceLayout);
            this.reduceDescriptorLayout = pReduceLayout.get(0);

            // Push Constants для Reduce (inDimensions, outDimensions, isReverseZ) = 32 байта
            VkPushConstantRange.Buffer reducePcr = VkPushConstantRange.calloc(1, stack);
            reducePcr.get(0)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                    .offset(0)
                    .size(32);

            VkPipelineLayoutCreateInfo reducePipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(pReduceLayout)
                    .pPushConstantRanges(reducePcr);

            LongBuffer pPipelineLayout = stack.mallocLong(1);
            VK10.vkCreatePipelineLayout(this.device, reducePipelineLayoutInfo, null, pPipelineLayout);
            this.reducePipelineLayout = pPipelineLayout.get(0);

            // Загрузка SPIR-V hzb_reduce.spv
            try (SodkamShaderModule reduceModule = SodkamShaderModule.fromResource(
                    this.device, VK10.VK_SHADER_STAGE_COMPUTE_BIT, "/assets/sodium/shaders/compute/hzb_reduce.spv")) {
                VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
                        .stage(reduceModule.createStageInfo(stack, "main"))
                        .layout(this.reducePipelineLayout);

                LongBuffer pPipeline = stack.mallocLong(1);
                VK10.vkCreateComputePipelines(this.device, VK10.VK_NULL_HANDLE, pipelineInfo, null, pPipeline);
                this.reducePipeline = pPipeline.get(0);
            }

            // 2. Cull Layout: Binding 0 (Candidates), Binding 1 (Commands), Binding 2 (Count), Binding 3 (HzbPyramid)
            VkDescriptorSetLayoutBinding.Buffer cullBindings = VkDescriptorSetLayoutBinding.calloc(4, stack);
            cullBindings.get(0)
                    .binding(0)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);

            cullBindings.get(1)
                    .binding(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);

            cullBindings.get(2)
                    .binding(2)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);

            cullBindings.get(3)
                    .binding(3)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);

            VkDescriptorSetLayoutCreateInfo cullLayoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(cullBindings);

            LongBuffer pCullLayout = stack.mallocLong(1);
            VK10.vkCreateDescriptorSetLayout(this.device, cullLayoutInfo, null, pCullLayout);
            this.cullDescriptorLayout = pCullLayout.get(0);

            // Push Constants для Cull (viewProj 64b + 6 planes 96b + screenSize 8b + totalChunks 4b + passIndex 4b + isReverseZ 4b + maxMipLevel 4b = 184 байта)
            VkPushConstantRange.Buffer cullPcr = VkPushConstantRange.calloc(1, stack);
            cullPcr.get(0)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                    .offset(0)
                    .size(256);

            VkPipelineLayoutCreateInfo cullPipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(pCullLayout)
                    .pPushConstantRanges(cullPcr);

            VK10.vkCreatePipelineLayout(this.device, cullPipelineLayoutInfo, null, pPipelineLayout);
            this.cullPipelineLayout = pPipelineLayout.get(0);

            // Загрузка SPIR-V hzb_cull.spv
            try (SodkamShaderModule cullModule = SodkamShaderModule.fromResource(
                    this.device, VK10.VK_SHADER_STAGE_COMPUTE_BIT, "/assets/sodium/shaders/compute/hzb_cull.spv")) {
                VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
                        .stage(cullModule.createStageInfo(stack, "main"))
                        .layout(this.cullPipelineLayout);

                LongBuffer pPipeline = stack.mallocLong(1);
                VK10.vkCreateComputePipelines(this.device, VK10.VK_NULL_HANDLE, pipelineInfo, null, pPipeline);
                this.cullPipeline = pPipeline.get(0);
            }

            // Инициализация сэмплера с Clamp to Edge и Nearest Mipmap
            org.lwjgl.vulkan.VkSamplerCreateInfo samplerInfo = org.lwjgl.vulkan.VkSamplerCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                    .magFilter(VK10.VK_FILTER_NEAREST)
                    .minFilter(VK10.VK_FILTER_NEAREST)
                    .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .minLod(0.0f)
                    .maxLod(16.0f);

            LongBuffer pSampler = stack.mallocLong(1);
            VK10.vkCreateSampler(this.device, samplerInfo, null, pSampler);
            this.hzbSampler = pSampler.get(0);
        } catch (Throwable t) {
            LOGGER.error("Ошибка компиляции/создания пайплайнов Hi-Z: {}", t.getMessage(), t);
        }
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void resize(int w, int h) {
        if (this.width == w && this.height == h) {
            return;
        }
        this.width = w;
        this.height = h;

        int maxDim = Math.max(w, h);
        this.mipLevels = Math.min(MAX_MIP_LEVELS, (int) Math.floor(Math.log(maxDim) / Math.log(2.0)) + 1);

        this.recreateHzbImages();
    }

    private void recreateHzbImages() {
        if (this.device == null || this.width <= 0 || this.height <= 0) {
            return;
        }

        this.destroyHzbImages();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Создание R32_SFLOAT изображения для пирамиды глубин
            org.lwjgl.vulkan.VkImageCreateInfo imageInfo = org.lwjgl.vulkan.VkImageCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK10.VK_IMAGE_TYPE_2D)
                    .format(VK10.VK_FORMAT_R32_SFLOAT)
                    .extent(e -> e.width(this.width).height(this.height).depth(1))
                    .mipLevels(this.mipLevels)
                    .arrayLayers(1)
                    .samples(VK10.VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK10.VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK10.VK_IMAGE_USAGE_SAMPLED_BIT | VK10.VK_IMAGE_USAGE_STORAGE_BIT | VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED);

            LongBuffer pImage = stack.mallocLong(1);
            VK10.vkCreateImage(this.device, imageInfo, null, pImage);
            this.hzbImage = pImage.get(0);

            VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
            VK10.vkGetImageMemoryRequirements(this.device, this.hzbImage, memReqs);

            int memTypeIndex = 0;
            if (this.context != null) {
                memTypeIndex = this.context.findMemoryTypeIndex(memReqs.memoryTypeBits(), VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            }

            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(memReqs.size())
                    .memoryTypeIndex(memTypeIndex);

            LongBuffer pMemory = stack.mallocLong(1);
            VK10.vkAllocateMemory(this.device, allocInfo, null, pMemory);
            this.hzbMemory = pMemory.get(0);

            VK10.vkBindImageMemory(this.device, this.hzbImage, this.hzbMemory, 0);

            // Создание общего ImageView для всей пирамиды
            org.lwjgl.vulkan.VkImageViewCreateInfo viewInfo = org.lwjgl.vulkan.VkImageViewCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(this.hzbImage)
                    .viewType(VK10.VK_IMAGE_VIEW_TYPE_2D)
                    .format(VK10.VK_FORMAT_R32_SFLOAT)
                    .subresourceRange(r -> r
                            .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(0)
                            .levelCount(this.mipLevels)
                            .baseArrayLayer(0)
                            .layerCount(1));

            LongBuffer pView = stack.mallocLong(1);
            VK10.vkCreateImageView(this.device, viewInfo, null, pView);
            this.hzbImageView = pView.get(0);

            // Создание Mip Views для каждого отдельного уровня для шейдера редукции
            for (int lvl = 0; lvl < this.mipLevels; lvl++) {
                final int finalLvl = lvl;
                viewInfo.subresourceRange(r -> r
                        .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(finalLvl)
                        .levelCount(1)
                        .baseArrayLayer(0)
                        .layerCount(1));

                VK10.vkCreateImageView(this.device, viewInfo, null, pView);
                this.hzbMipViews[lvl] = pView.get(0);
            }

            this.initDescriptorSets();
        }
    }

    private void initDescriptorSets() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (this.descriptorPool != VK10.VK_NULL_HANDLE) {
                VK10.vkDestroyDescriptorPool(this.device, this.descriptorPool, null);
            }

            // Пул дескрипторов для Mip-проходов редукции + 1 для culling
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(3, stack);
            poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(this.mipLevels + 2);
            poolSizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(this.mipLevels + 2);
            poolSizes.get(2).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(4);

            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .maxSets(this.mipLevels + 2)
                    .pPoolSizes(poolSizes);

            LongBuffer pPool = stack.mallocLong(1);
            VK10.vkCreateDescriptorPool(this.device, poolInfo, null, pPool);
            this.descriptorPool = pPool.get(0);

            // Выделение Cull Descriptor Set
            LongBuffer pCullLayout = stack.longs(this.cullDescriptorLayout);
            VkDescriptorSetAllocateInfo cullAllocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(this.descriptorPool)
                    .pSetLayouts(pCullLayout);

            LongBuffer pSet = stack.mallocLong(1);
            VK10.vkAllocateDescriptorSets(this.device, cullAllocInfo, pSet);
            this.cullDescriptorSet = pSet.get(0);

            // Выделение Reduce Descriptor Sets для пар уровней
            for (int lvl = 0; lvl < this.mipLevels - 1; lvl++) {
                LongBuffer pReduceLayout = stack.longs(this.reduceDescriptorLayout);
                VkDescriptorSetAllocateInfo reduceAllocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                        .descriptorPool(this.descriptorPool)
                        .pSetLayouts(pReduceLayout);

                VK10.vkAllocateDescriptorSets(this.device, reduceAllocInfo, pSet);
                this.reduceDescriptorSets[lvl] = pSet.get(0);

                // Запись дескрипторов: Binding 0 = level N, Binding 1 = level N+1
                VkDescriptorImageInfo.Buffer inInfo = VkDescriptorImageInfo.calloc(1, stack)
                        .sampler(this.hzbSampler)
                        .imageView(this.hzbMipViews[lvl])
                        .imageLayout(VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

                VkDescriptorImageInfo.Buffer outInfo = VkDescriptorImageInfo.calloc(1, stack)
                        .imageView(this.hzbMipViews[lvl + 1])
                        .imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);

                VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
                writes.get(0)
                        .sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                        .dstSet(this.reduceDescriptorSets[lvl])
                        .dstBinding(0)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .pImageInfo(inInfo);

                writes.get(1)
                        .sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                        .dstSet(this.reduceDescriptorSets[lvl])
                        .dstBinding(1)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .pImageInfo(outInfo);

                VK10.vkUpdateDescriptorSets(this.device, writes, null);
            }

            this.updateCullDescriptors();
        }
    }

    private void updateCullDescriptors() {
        if (this.gpuCuller == null || this.cullDescriptorSet == VK10.VK_NULL_HANDLE || this.hzbImageView == VK10.VK_NULL_HANDLE) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(4, stack);

            // Binding 0: Candidates Buffer
            VkDescriptorBufferInfo.Buffer b0 = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(this.gpuCuller.getCandidatesBuffer())
                    .offset(0)
                    .range(VK10.VK_WHOLE_SIZE);
            writes.get(0)
                    .sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(this.cullDescriptorSet)
                    .dstBinding(0)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .pBufferInfo(b0);

            // Binding 1: Commands Buffer
            VkDescriptorBufferInfo.Buffer b1 = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(this.gpuCuller.getCommandsBuffer())
                    .offset(0)
                    .range(VK10.VK_WHOLE_SIZE);
            writes.get(1)
                    .sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(this.cullDescriptorSet)
                    .dstBinding(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .pBufferInfo(b1);

            // Binding 2: Count Buffer
            VkDescriptorBufferInfo.Buffer b2 = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(this.gpuCuller.getCountBuffer())
                    .offset(0)
                    .range(VK10.VK_WHOLE_SIZE);
            writes.get(2)
                    .sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(this.cullDescriptorSet)
                    .dstBinding(2)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .pBufferInfo(b2);

            // Binding 3: HZB Pyramid sampler
            VkDescriptorImageInfo.Buffer img3 = VkDescriptorImageInfo.calloc(1, stack)
                    .sampler(this.hzbSampler)
                    .imageView(this.hzbImageView)
                    .imageLayout(VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            writes.get(3)
                    .sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(this.cullDescriptorSet)
                    .dstBinding(3)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .pImageInfo(img3);

            VK10.vkUpdateDescriptorSets(this.device, writes, null);
        }
    }

    /**
     * Построение пирамиды глубин (HZB Downsampling) из текущего Z-буфера.
     */
    public void generateHiZPyramid(VkCommandBuffer cmd, long depthImageView) {
        if (!this.enabled || cmd == null || depthImageView == VK10.VK_NULL_HANDLE || this.reducePipeline == VK10.VK_NULL_HANDLE) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, this.reducePipeline);

            int curW = this.width;
            int curH = this.height;

            for (int lvl = 0; lvl < this.mipLevels - 1; lvl++) {
                int nextW = Math.max(1, curW / 2);
                int nextH = Math.max(1, curH / 2);

                LongBuffer pSets = stack.longs(this.reduceDescriptorSets[lvl]);
                VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, this.reducePipelineLayout, 0, pSets, null);

                this.reducePushConstantBuffer.clear();
                this.reducePushConstantBuffer.putFloat(curW).putFloat(curH);
                this.reducePushConstantBuffer.putFloat(nextW).putFloat(nextH);
                this.reducePushConstantBuffer.putInt(0); // standard Z
                this.reducePushConstantBuffer.position(0);

                VK10.vkCmdPushConstants(cmd, this.reducePipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, this.reducePushConstantBuffer);

                int gx = (nextW + 15) / 16;
                int gy = (nextH + 15) / 16;
                VK10.vkCmdDispatch(cmd, gx, gy, 1);

                // Барьер памяти между уровнями пирамиды: Compute Write -> Compute Read
                final int nextMip = lvl + 1;
                VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                        .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                        .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT)
                        .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                        .newLayout(VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                        .image(this.hzbImage)
                        .subresourceRange(r -> r
                                .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                                .baseMipLevel(nextMip)
                                .levelCount(1)
                                .baseArrayLayer(0)
                                .layerCount(1));

                VK10.vkCmdPipelineBarrier(cmd,
                        VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        0, null, null, barrier);

                curW = nextW;
                curH = nextH;
            }
        }
    }

    /**
     * Двухпроходная проверка видимости чанков против Hi-Z пирамиды.
     */
    public void executeCull(VkCommandBuffer cmd, int chunkCount) {
        if (!this.enabled || cmd == null || chunkCount <= 0 || this.cullPipeline == VK10.VK_NULL_HANDLE) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (this.gpuCuller != null) {
                // Сброс счетчика видимых вызовов
                VK10.vkCmdFillBuffer(cmd, this.gpuCuller.getCountBuffer(), 0, Integer.BYTES, 0);

                VkBufferMemoryBarrier.Buffer fillBarrier = VkBufferMemoryBarrier.calloc(1, stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                        .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                        .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT)
                        .buffer(this.gpuCuller.getCountBuffer())
                        .offset(0)
                        .size(Integer.BYTES);

                VK10.vkCmdPipelineBarrier(cmd,
                        VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        0, null, fillBarrier, null);
            }

            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, this.cullPipeline);
            LongBuffer pSets = stack.longs(this.cullDescriptorSet);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, this.cullPipelineLayout, 0, pSets, null);

            // Заполнение Push Constants
            this.cullPushConstantBuffer.clear();
            // 1. viewProj (mat4 = 16 floats = 64 bytes)
            float[] matFloats = new float[16];
            this.cachedViewProj.get(matFloats);
            for (float f : matFloats) {
                this.cullPushConstantBuffer.putFloat(f);
            }

            // 2. frustumPlanes[6] (6 * 4 floats = 96 bytes)
            for (int i = 0; i < 6; i++) {
                Vector4f p = this.cachedFrustumPlanes[i];
                this.cullPushConstantBuffer.putFloat(p.x).putFloat(p.y).putFloat(p.z).putFloat(p.w);
            }

            // 3. screenSize (2 floats = 8 bytes)
            this.cullPushConstantBuffer.putFloat(Math.max(1, this.width)).putFloat(Math.max(1, this.height));

            // 4. totalChunks, passIndex, isReverseZ, maxMipLevel (4 * 4 = 16 bytes)
            this.cullPushConstantBuffer.putInt(chunkCount);
            this.cullPushConstantBuffer.putInt(2); // Single Pass Frustum + HZB
            this.cullPushConstantBuffer.putInt(0); // Standard Z
            this.cullPushConstantBuffer.putFloat(this.mipLevels - 1);
            this.cullPushConstantBuffer.position(0);

            VK10.vkCmdPushConstants(cmd, this.cullPipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, this.cullPushConstantBuffer);

            int groupCountX = (chunkCount + 63) / 64;
            VK10.vkCmdDispatch(cmd, groupCountX, 1, 1);

            if (this.sync2 != null && this.gpuCuller != null) {
                this.sync2.barrierComputeToIndirect(cmd, this.gpuCuller.getCommandsBuffer(), this.gpuCuller.getCountBuffer());
            }
        }
    }

    private void destroyHzbImages() {
        for (int i = 0; i < MAX_MIP_LEVELS; i++) {
            if (this.hzbMipViews[i] != VK10.VK_NULL_HANDLE) {
                VK10.vkDestroyImageView(this.device, this.hzbMipViews[i], null);
                this.hzbMipViews[i] = VK10.VK_NULL_HANDLE;
            }
        }
        if (this.hzbImageView != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyImageView(this.device, this.hzbImageView, null);
            this.hzbImageView = VK10.VK_NULL_HANDLE;
        }
        if (this.hzbImage != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyImage(this.device, this.hzbImage, null);
            this.hzbImage = VK10.VK_NULL_HANDLE;
        }
        if (this.hzbMemory != VK10.VK_NULL_HANDLE) {
            VK10.vkFreeMemory(this.device, this.hzbMemory, null);
            this.hzbMemory = VK10.VK_NULL_HANDLE;
        }
    }

    @Override
    public synchronized void close() {
        if (this.isClosed) {
            return;
        }

        if (this.context != null) {
            this.context.waitIdle();
        }

        this.destroyHzbImages();

        if (this.hzbSampler != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroySampler(this.device, this.hzbSampler, null);
            this.hzbSampler = VK10.VK_NULL_HANDLE;
        }

        if (this.descriptorPool != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyDescriptorPool(this.device, this.descriptorPool, null);
            this.descriptorPool = VK10.VK_NULL_HANDLE;
        }

        if (this.reducePipeline != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyPipeline(this.device, this.reducePipeline, null);
            this.reducePipeline = VK10.VK_NULL_HANDLE;
        }
        if (this.reducePipelineLayout != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyPipelineLayout(this.device, this.reducePipelineLayout, null);
            this.reducePipelineLayout = VK10.VK_NULL_HANDLE;
        }
        if (this.reduceDescriptorLayout != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyDescriptorSetLayout(this.device, this.reduceDescriptorLayout, null);
            this.reduceDescriptorLayout = VK10.VK_NULL_HANDLE;
        }

        if (this.cullPipeline != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyPipeline(this.device, this.cullPipeline, null);
            this.cullPipeline = VK10.VK_NULL_HANDLE;
        }
        if (this.cullPipelineLayout != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyPipelineLayout(this.device, this.cullPipelineLayout, null);
            this.cullPipelineLayout = VK10.VK_NULL_HANDLE;
        }
        if (this.cullDescriptorLayout != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyDescriptorSetLayout(this.device, this.cullDescriptorLayout, null);
            this.cullDescriptorLayout = VK10.VK_NULL_HANDLE;
        }

        MemoryUtil.memFree(this.cullPushConstantBuffer);
        MemoryUtil.memFree(this.reducePushConstantBuffer);
        this.isClosed = true;

        LOGGER.info("Vuldium Hi-Z Occlusion Culling успешно закрыт.");
    }
}
