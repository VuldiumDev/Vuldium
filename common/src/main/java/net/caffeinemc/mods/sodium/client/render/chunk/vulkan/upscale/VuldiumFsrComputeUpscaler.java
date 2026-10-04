package net.caffeinemc.mods.sodium.client.render.chunk.vulkan.upscale;

import net.caffeinemc.mods.sodium.client.gpu.device.vulkan.VuldiumDeviceContext;
import net.caffeinemc.mods.sodium.client.gpu.device.vulkan.capabilities.UpscalerType;
import net.caffeinemc.mods.sodium.client.gpu.device.vulkan.sync.VuldiumSync2;
import net.caffeinemc.mods.sodium.client.render.chunk.vulkan.pipeline.VuldiumShaderModule;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

/**
 * Нативная реализация универсального алгоритма FidelityFX Super Resolution на Compute-шейдерах Vulkan.
 * Состоит из двух стадий:
 * 1. EASU (Edge-Adaptive Spatial Upsampling): адаптивная реконструкция контуров.
 * 2. RCAS (Robust Contrast Adaptive Sharpening): усиление высокочастотных деталей с защитой от артефактов.
 */
public class VuldiumFsrComputeUpscaler implements UpscalerInstance {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vuldium/FSR");

    private final VuldiumDeviceContext context;
    private final VkDevice device;
    private final VuldiumSync2 sync2;

    private int renderWidth = 0;
    private int renderHeight = 0;
    private int targetWidth = 0;
    private int targetHeight = 0;

    // Сэмплер
    private long bilinearSampler = VK10.VK_NULL_HANDLE;

    // Промежуточный буфер EASU -> RCAS (целевое разрешение)
    private long easuImage = VK10.VK_NULL_HANDLE;
    private long easuMemory = VK10.VK_NULL_HANDLE;
    private long easuImageView = VK10.VK_NULL_HANDLE;

    // EASU Pipeline
    private long easuDescriptorLayout = VK10.VK_NULL_HANDLE;
    private long easuPipelineLayout = VK10.VK_NULL_HANDLE;
    private long easuPipeline = VK10.VK_NULL_HANDLE;

    // RCAS Pipeline
    private long rcasDescriptorLayout = VK10.VK_NULL_HANDLE;
    private long rcasPipelineLayout = VK10.VK_NULL_HANDLE;
    private long rcasPipeline = VK10.VK_NULL_HANDLE;

    // Дескрипторы
    private long descriptorPool = VK10.VK_NULL_HANDLE;
    private long easuDescriptorSet = VK10.VK_NULL_HANDLE;
    private long rcasDescriptorSet = VK10.VK_NULL_HANDLE;

    // Буферы Push Constants
    private final ByteBuffer easuPushConstants = MemoryUtil.memAlloc(32);
    private final ByteBuffer rcasPushConstants = MemoryUtil.memAlloc(32);

    private boolean isInitialized = false;

    public VuldiumFsrComputeUpscaler(VuldiumDeviceContext context) {
        this.context = context;
        this.device = context.getLogicalDevice();
        this.sync2 = new VuldiumSync2(context);

        this.initSampler();
        this.initLayoutsAndPipelines();
        LOGGER.info("Vuldium FSR Compute Upscaler успешно создан.");
    }

    private void initSampler() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                    .magFilter(VK10.VK_FILTER_LINEAR)
                    .minFilter(VK10.VK_FILTER_LINEAR)
                    .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);

            LongBuffer pSampler = stack.mallocLong(1);
            int res = VK10.vkCreateSampler(this.device, samplerInfo, null, pSampler);
            if (res != VK10.VK_SUCCESS) {
                throw new IllegalStateException("Ошибка создания VkSampler для FSR: " + res);
            }
            this.bilinearSampler = pSampler.get(0);
        }
    }

    private void initLayoutsAndPipelines() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Layout bindings: binding 0 = sampler2D, binding 1 = image2D
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
            bindings.get(0)
                    .binding(0)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);

            bindings.get(1)
                    .binding(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(bindings);

            LongBuffer pLayout = stack.mallocLong(1);
            VK10.vkCreateDescriptorSetLayout(this.device, layoutInfo, null, pLayout);
            this.easuDescriptorLayout = pLayout.get(0);

            VK10.vkCreateDescriptorSetLayout(this.device, layoutInfo, null, pLayout);
            this.rcasDescriptorLayout = pLayout.get(0);

            // Pipeline layouts
            VkPushConstantRange.Buffer pushConstants = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                    .offset(0)
                    .size(32);

            LongBuffer pSetLayouts = stack.longs(this.easuDescriptorLayout);
            VkPipelineLayoutCreateInfo pipeLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(pSetLayouts)
                    .pPushConstantRanges(pushConstants);

            VK10.vkCreatePipelineLayout(this.device, pipeLayoutInfo, null, pLayout);
            this.easuPipelineLayout = pLayout.get(0);

            pSetLayouts.put(0, this.rcasDescriptorLayout);
            VK10.vkCreatePipelineLayout(this.device, pipeLayoutInfo, null, pLayout);
            this.rcasPipelineLayout = pLayout.get(0);

            // Создание Compute Pipelines
            this.easuPipeline = this.createComputePipeline("/assets/sodium/shaders/compute/fsr_easu.spv", this.easuPipelineLayout);
            this.rcasPipeline = this.createComputePipeline("/assets/sodium/shaders/compute/fsr_rcas.spv", this.rcasPipelineLayout);
        }
    }

    private long createComputePipeline(String shaderResource, long layout) {
        try (VuldiumShaderModule module = VuldiumShaderModule.fromResource(this.device, VK10.VK_SHADER_STAGE_COMPUTE_BIT, shaderResource);
             MemoryStack stack = MemoryStack.stackPush()) {

            VkPipelineShaderStageCreateInfo stageInfo = module.createStageInfo(stack, "main");
            VkComputePipelineCreateInfo.Buffer pipeInfo = VkComputePipelineCreateInfo.calloc(1, stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
                    .stage(stageInfo)
                    .layout(layout);

            LongBuffer pPipeline = stack.mallocLong(1);
            int res = VK10.vkCreateComputePipelines(this.device, VK10.VK_NULL_HANDLE, pipeInfo, null, pPipeline);
            if (res != VK10.VK_SUCCESS) {
                throw new IllegalStateException("Ошибка создания Compute Pipeline для " + shaderResource + ": " + res);
            }
            return pPipeline.get(0);
        } catch (IOException e) {
            throw new RuntimeException("Не удалось загрузить шейдер: " + shaderResource, e);
        }
    }

    @Override
    public synchronized void init(int renderWidth, int renderHeight, int targetWidth, int targetHeight) {
        if (this.renderWidth == renderWidth && this.renderHeight == renderHeight &&
            this.targetWidth == targetWidth && this.targetHeight == targetHeight && this.isInitialized) {
            return;
        }

        this.destroyIntermediateResources();

        this.renderWidth = renderWidth;
        this.renderHeight = renderHeight;
        this.targetWidth = targetWidth;
        this.targetHeight = targetHeight;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Создание промежуточного изображения EASU (targetWidth x targetHeight, RGBA8_UNORM)
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK10.VK_IMAGE_TYPE_2D)
                    .format(VK10.VK_FORMAT_R8G8B8A8_UNORM)
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK10.VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK10.VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK10.VK_IMAGE_USAGE_STORAGE_BIT | VK10.VK_IMAGE_USAGE_SAMPLED_BIT)
                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED);
            imageInfo.extent().set(targetWidth, targetHeight, 1);

            LongBuffer pImage = stack.mallocLong(1);
            VK10.vkCreateImage(this.device, imageInfo, null, pImage);
            this.easuImage = pImage.get(0);

            VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
            VK10.vkGetImageMemoryRequirements(this.device, this.easuImage, memReqs);

            int memTypeIndex = this.context.findMemoryTypeIndex(memReqs.memoryTypeBits(), VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(memReqs.size())
                    .memoryTypeIndex(memTypeIndex);

            LongBuffer pMem = stack.mallocLong(1);
            VK10.vkAllocateMemory(this.device, allocInfo, null, pMem);
            this.easuMemory = pMem.get(0);
            VK10.vkBindImageMemory(this.device, this.easuImage, this.easuMemory, 0);

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(this.easuImage)
                    .viewType(VK10.VK_IMAGE_VIEW_TYPE_2D)
                    .format(VK10.VK_FORMAT_R8G8B8A8_UNORM);
            viewInfo.subresourceRange()
                    .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1);

            LongBuffer pView = stack.mallocLong(1);
            VK10.vkCreateImageView(this.device, viewInfo, null, pView);
            this.easuImageView = pView.get(0);

            // Создание дескрипторного пула и сетов
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(2, stack);
            poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(2);
            poolSizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(2);

            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .pPoolSizes(poolSizes)
                    .maxSets(2);

            LongBuffer pPool = stack.mallocLong(1);
            VK10.vkCreateDescriptorPool(this.device, poolInfo, null, pPool);
            this.descriptorPool = pPool.get(0);

            LongBuffer pSetLayouts = stack.longs(this.easuDescriptorLayout, this.rcasDescriptorLayout);
            VkDescriptorSetAllocateInfo setAllocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(this.descriptorPool)
                    .pSetLayouts(pSetLayouts);

            LongBuffer pSets = stack.mallocLong(2);
            VK10.vkAllocateDescriptorSets(this.device, setAllocInfo, pSets);
            this.easuDescriptorSet = pSets.get(0);
            this.rcasDescriptorSet = pSets.get(1);

            this.isInitialized = true;
            LOGGER.info("Vuldium FSR инициализирован: Render={}x{}, Target={}x{}", renderWidth, renderHeight, targetWidth, targetHeight);
        }
    }

    @Override
    public void dispatch(
            VkCommandBuffer cmd,
            long colorInView,
            long depthInView,
            long velocityInView,
            long colorOutView,
            float jitterX,
            float jitterY,
            float sharpness,
            boolean resetHistory
    ) {
        if (!this.isInitialized) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // --- СТАДИЯ 1: EASU (ColorIn -> EasuImage) ---
            VkDescriptorImageInfo.Buffer easuInputInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .sampler(this.bilinearSampler)
                    .imageView(colorInView)
                    .imageLayout(VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

            VkDescriptorImageInfo.Buffer easuOutputInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .imageView(this.easuImageView)
                    .imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);

            VkWriteDescriptorSet.Buffer easuWrites = VkWriteDescriptorSet.calloc(2, stack);
            easuWrites.get(0)
                    .sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(this.easuDescriptorSet)
                    .dstBinding(0)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .pImageInfo(easuInputInfo);

            easuWrites.get(1)
                    .sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(this.easuDescriptorSet)
                    .dstBinding(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .pImageInfo(easuOutputInfo);

            VK10.vkUpdateDescriptorSets(this.device, easuWrites, null);

            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, this.easuPipeline);
            LongBuffer pEasuSets = stack.longs(this.easuDescriptorSet);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, this.easuPipelineLayout, 0, pEasuSets, null);

            this.easuPushConstants.clear();
            this.easuPushConstants.putFloat(0, (float) this.renderWidth);
            this.easuPushConstants.putFloat(4, (float) this.renderHeight);
            this.easuPushConstants.putFloat(8, 1.0f / (float) this.renderWidth);
            this.easuPushConstants.putFloat(12, 1.0f / (float) this.renderHeight);
            this.easuPushConstants.putFloat(16, (float) this.targetWidth);
            this.easuPushConstants.putFloat(20, (float) this.targetHeight);
            this.easuPushConstants.putFloat(24, 1.0f / (float) this.targetWidth);
            this.easuPushConstants.putFloat(28, 1.0f / (float) this.targetHeight);
            this.easuPushConstants.position(0);

            VK10.vkCmdPushConstants(cmd, this.easuPipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, this.easuPushConstants);

            int groupTargetX = (this.targetWidth + 15) / 16;
            int groupTargetY = (this.targetHeight + 15) / 16;
            VK10.vkCmdDispatch(cmd, groupTargetX, groupTargetY, 1);

            // Барьер: EASU Write -> RCAS Read
            this.sync2.barrierComputeToCompute(cmd);

            // --- СТАДИЯ 2: RCAS (EasuImage -> ColorOut) ---
            VkDescriptorImageInfo.Buffer rcasInputInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .sampler(this.bilinearSampler)
                    .imageView(this.easuImageView)
                    .imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);

            VkDescriptorImageInfo.Buffer rcasOutputInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .imageView(colorOutView)
                    .imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);

            VkWriteDescriptorSet.Buffer rcasWrites = VkWriteDescriptorSet.calloc(2, stack);
            rcasWrites.get(0)
                    .sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(this.rcasDescriptorSet)
                    .dstBinding(0)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .pImageInfo(rcasInputInfo);

            rcasWrites.get(1)
                    .sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(this.rcasDescriptorSet)
                    .dstBinding(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .pImageInfo(rcasOutputInfo);

            VK10.vkUpdateDescriptorSets(this.device, rcasWrites, null);

            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, this.rcasPipeline);
            LongBuffer pRcasSets = stack.longs(this.rcasDescriptorSet);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, this.rcasPipelineLayout, 0, pRcasSets, null);

            this.rcasPushConstants.clear();
            this.rcasPushConstants.putFloat(0, (float) this.targetWidth);
            this.rcasPushConstants.putFloat(4, (float) this.targetHeight);
            this.rcasPushConstants.putFloat(8, 1.0f / (float) this.targetWidth);
            this.rcasPushConstants.putFloat(12, 1.0f / (float) this.targetHeight);
            this.rcasPushConstants.putFloat(16, sharpness);
            this.rcasPushConstants.putFloat(20, 0.0f);
            this.rcasPushConstants.position(0);

            VK10.vkCmdPushConstants(cmd, this.rcasPipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, this.rcasPushConstants);
            VK10.vkCmdDispatch(cmd, groupTargetX, groupTargetY, 1);
        }
    }

    private void destroyIntermediateResources() {
        if (this.descriptorPool != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyDescriptorPool(this.device, this.descriptorPool, null);
            this.descriptorPool = VK10.VK_NULL_HANDLE;
        }
        if (this.easuImageView != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyImageView(this.device, this.easuImageView, null);
            this.easuImageView = VK10.VK_NULL_HANDLE;
        }
        if (this.easuImage != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyImage(this.device, this.easuImage, null);
            this.easuImage = VK10.VK_NULL_HANDLE;
        }
        if (this.easuMemory != VK10.VK_NULL_HANDLE) {
            VK10.vkFreeMemory(this.device, this.easuMemory, null);
            this.easuMemory = VK10.VK_NULL_HANDLE;
        }
        this.isInitialized = false;
    }

    @Override
    public void destroy() {
        this.context.waitIdle();
        this.destroyIntermediateResources();

        if (this.bilinearSampler != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroySampler(this.device, this.bilinearSampler, null);
            this.bilinearSampler = VK10.VK_NULL_HANDLE;
        }
        if (this.easuPipeline != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyPipeline(this.device, this.easuPipeline, null);
            this.easuPipeline = VK10.VK_NULL_HANDLE;
        }
        if (this.rcasPipeline != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyPipeline(this.device, this.rcasPipeline, null);
            this.rcasPipeline = VK10.VK_NULL_HANDLE;
        }
        if (this.easuPipelineLayout != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyPipelineLayout(this.device, this.easuPipelineLayout, null);
            this.easuPipelineLayout = VK10.VK_NULL_HANDLE;
        }
        if (this.rcasPipelineLayout != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyPipelineLayout(this.device, this.rcasPipelineLayout, null);
            this.rcasPipelineLayout = VK10.VK_NULL_HANDLE;
        }
        if (this.easuDescriptorLayout != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyDescriptorSetLayout(this.device, this.easuDescriptorLayout, null);
            this.easuDescriptorLayout = VK10.VK_NULL_HANDLE;
        }
        if (this.rcasDescriptorLayout != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyDescriptorSetLayout(this.device, this.rcasDescriptorLayout, null);
            this.rcasDescriptorLayout = VK10.VK_NULL_HANDLE;
        }

        MemoryUtil.memFree(this.easuPushConstants);
        MemoryUtil.memFree(this.rcasPushConstants);

        LOGGER.info("Vuldium FSR Compute Upscaler успешно закрыт.");
    }

    @Override
    public UpscalerType getType() {
        return UpscalerType.FSR;
    }

    @Override
    public boolean isSupported() {
        return true;
    }
}
