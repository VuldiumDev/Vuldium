package net.caffeinemc.mods.sodium.client.render.chunk.vulkan.post;

import net.caffeinemc.mods.sodium.client.gpu.device.vulkan.SodkamDeviceContext;
import net.caffeinemc.mods.sodium.client.gpu.device.vulkan.sync.SodkamSync2;
import net.caffeinemc.mods.sodium.client.render.chunk.vulkan.pipeline.SodkamShaderModule;
import org.joml.Matrix4f;
import org.joml.Vector3d;
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
 * Проход генерации и реконструкции экранных векторов движения (SodkamVelocityPass).
 * Формирует 2D буфер экранной скорости (Motion Vectors) формата VK_FORMAT_R16G16_SFLOAT
 * для передачи в темпоральные апскейлеры (FSR 2/3, DLSS, XeSS).
 */
public class SodkamVelocityPass implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vuldium/VelocityPass");
    public static final int PUSH_CONSTANTS_SIZE = 96;

    private final SodkamDeviceContext context;
    private final VkDevice device;
    private final SodkamSync2 sync2;

    // Ресурсы изображения Velocity Buffer
    private long velocityImage = VK10.VK_NULL_HANDLE;
    private long velocityMemory = VK10.VK_NULL_HANDLE;
    private long velocityImageView = VK10.VK_NULL_HANDLE;
    private long depthSampler = VK10.VK_NULL_HANDLE;

    private int width = 0;
    private int height = 0;

    // Конвейер вычислений
    private long descriptorPool = VK10.VK_NULL_HANDLE;
    private long descriptorSetLayout = VK10.VK_NULL_HANDLE;
    private long descriptorSet = VK10.VK_NULL_HANDLE;
    private long pipelineLayout = VK10.VK_NULL_HANDLE;
    private long computePipeline = VK10.VK_NULL_HANDLE;

    // История матриц для межпокадровой репроекции
    private final Matrix4f prevViewProj = new Matrix4f();
    private final Matrix4f currInvViewProj = new Matrix4f();
    private final Matrix4f reprojectionMatrix = new Matrix4f();

    private final Vector3d prevCameraPos = new Vector3d();
    private final Vector3d currCameraPos = new Vector3d();
    private boolean hasHistory = false;

    // Офф-хип буфер параметров
    private final ByteBuffer pushConstantBuffer = MemoryUtil.memAlloc(PUSH_CONSTANTS_SIZE);
    private boolean isClosed = false;

    public SodkamVelocityPass(SodkamDeviceContext context) {
        this.context = context;
        this.device = context.getLogicalDevice();
        this.sync2 = new SodkamSync2(context);

        this.initDepthSampler();
        this.initDescriptorLayout();
        this.initPipeline();
        this.initDescriptorPoolAndSet();

        LOGGER.info("Vuldium Velocity Pass успешно инициализирован.");
    }

    private void initDepthSampler() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                    .magFilter(VK10.VK_FILTER_NEAREST)
                    .minFilter(VK10.VK_FILTER_NEAREST)
                    .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .minLod(0.0f)
                    .maxLod(0.0f);

            LongBuffer pSampler = stack.mallocLong(1);
            int res = VK10.vkCreateSampler(this.device, samplerInfo, null, pSampler);
            if (res != VK10.VK_SUCCESS) {
                throw new IllegalStateException("Ошибка создания VkSampler для глубины: " + res);
            }
            this.depthSampler = pSampler.get(0);
        }
    }

    private void initDescriptorLayout() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);

            // Binding 0: Velocity Storage Image (writeonly)
            bindings.get(0)
                    .binding(0)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);

            // Binding 1: Depth Combined Image Sampler (readonly)
            bindings.get(1)
                    .binding(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(bindings);

            LongBuffer pLayout = stack.mallocLong(1);
            int res = VK10.vkCreateDescriptorSetLayout(this.device, layoutInfo, null, pLayout);
            if (res != VK10.VK_SUCCESS) {
                throw new IllegalStateException("Ошибка создания VkDescriptorSetLayout для VelocityPass: " + res);
            }
            this.descriptorSetLayout = pLayout.get(0);
        }
    }

    private void initPipeline() {
        try (SodkamShaderModule module = SodkamShaderModule.fromResource(
                this.device,
                VK10.VK_SHADER_STAGE_COMPUTE_BIT,
                "/assets/sodium/shaders/compute/velocity_reconstruct.spv")) {

            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkPushConstantRange.Buffer pushConstants = VkPushConstantRange.calloc(1, stack)
                        .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                        .offset(0)
                        .size(PUSH_CONSTANTS_SIZE);

                LongBuffer pLayouts = stack.longs(this.descriptorSetLayout);
                VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                        .pSetLayouts(pLayouts)
                        .pPushConstantRanges(pushConstants);

                LongBuffer pPipeLayout = stack.mallocLong(1);
                int res = VK10.vkCreatePipelineLayout(this.device, layoutInfo, null, pPipeLayout);
                if (res != VK10.VK_SUCCESS) {
                    throw new IllegalStateException("Ошибка создания VkPipelineLayout для VelocityPass: " + res);
                }
                this.pipelineLayout = pPipeLayout.get(0);

                VkPipelineShaderStageCreateInfo stageInfo = module.createStageInfo(stack, "main");

                VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
                        .stage(stageInfo)
                        .layout(this.pipelineLayout);

                LongBuffer pPipeline = stack.mallocLong(1);
                res = VK10.vkCreateComputePipelines(this.device, VK10.VK_NULL_HANDLE, pipelineInfo, null, pPipeline);
                if (res != VK10.VK_SUCCESS) {
                    throw new IllegalStateException("Ошибка создания VkComputePipeline для VelocityPass: " + res);
                }
                this.computePipeline = pPipeline.get(0);
            }
        } catch (IOException e) {
            throw new RuntimeException("Не удалось загрузить SPIR-V шейдер velocity_reconstruct.spv", e);
        }
    }

    private void initDescriptorPoolAndSet() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(2, stack);
            poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1);
            poolSizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1);

            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .pPoolSizes(poolSizes)
                    .maxSets(1);

            LongBuffer pPool = stack.mallocLong(1);
            int res = VK10.vkCreateDescriptorPool(this.device, poolInfo, null, pPool);
            if (res != VK10.VK_SUCCESS) {
                throw new IllegalStateException("Ошибка создания VkDescriptorPool для VelocityPass: " + res);
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
                throw new IllegalStateException("Ошибка выделения VkDescriptorSet для VelocityPass: " + res);
            }
            this.descriptorSet = pSet.get(0);
        }
    }

    /**
     * Выделяет или пересоздает буфер векторов движения при изменении разрешения окна.
     */
    public synchronized void ensureSize(int targetWidth, int targetHeight) {
        if (this.width == targetWidth && this.height == targetHeight && this.velocityImage != VK10.VK_NULL_HANDLE) {
            return;
        }

        this.destroyImageResources();

        this.width = targetWidth;
        this.height = targetHeight;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Создание VkImage формата R16G16_SFLOAT
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK10.VK_IMAGE_TYPE_2D)
                    .format(VK10.VK_FORMAT_R16G16_SFLOAT)
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK10.VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK10.VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK10.VK_IMAGE_USAGE_STORAGE_BIT | VK10.VK_IMAGE_USAGE_SAMPLED_BIT | VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED);
            imageInfo.extent().set(targetWidth, targetHeight, 1);

            LongBuffer pImage = stack.mallocLong(1);
            int res = VK10.vkCreateImage(this.device, imageInfo, null, pImage);
            if (res != VK10.VK_SUCCESS) {
                throw new IllegalStateException("Ошибка создания Velocity VkImage: " + res);
            }
            this.velocityImage = pImage.get(0);

            VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
            VK10.vkGetImageMemoryRequirements(this.device, this.velocityImage, memReqs);

            int memTypeIndex = this.context.findMemoryTypeIndex(
                    memReqs.memoryTypeBits(),
                    VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
            );

            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(memReqs.size())
                    .memoryTypeIndex(memTypeIndex);

            LongBuffer pMem = stack.mallocLong(1);
            res = VK10.vkAllocateMemory(this.device, allocInfo, null, pMem);
            if (res != VK10.VK_SUCCESS) {
                throw new IllegalStateException("Ошибка выделения памяти для Velocity Image: " + res);
            }
            this.velocityMemory = pMem.get(0);

            VK10.vkBindImageMemory(this.device, this.velocityImage, this.velocityMemory, 0);

            // Создание VkImageView
            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(this.velocityImage)
                    .viewType(VK10.VK_IMAGE_VIEW_TYPE_2D)
                    .format(VK10.VK_FORMAT_R16G16_SFLOAT);
            viewInfo.subresourceRange()
                    .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1);

            LongBuffer pView = stack.mallocLong(1);
            res = VK10.vkCreateImageView(this.device, viewInfo, null, pView);
            if (res != VK10.VK_SUCCESS) {
                throw new IllegalStateException("Ошибка создания Velocity VkImageView: " + res);
            }
            this.velocityImageView = pView.get(0);

            LOGGER.info("Vuldium Velocity Buffer создан: {}x{} (R16G16_SFLOAT)", targetWidth, targetHeight);
        }
    }

    /**
     * Обновляет историю матриц и положения камеры перед рендером кадра.
     */
    public void updateMatrices(Matrix4f projection, Matrix4f modelView, double camX, double camY, double camZ) {
        Matrix4f currViewProj = new Matrix4f(projection).mul(modelView);

        if (!this.hasHistory) {
            this.prevViewProj.set(currViewProj);
            this.prevCameraPos.set(camX, camY, camZ);
            this.hasHistory = true;
        }

        this.currCameraPos.set(camX, camY, camZ);

        // Инвертируем текущую матрицу View-Projection для реконструкции мировых координат
        currViewProj.invert(this.currInvViewProj);

        // Матрица прямой репроекции: M_reproject = prevViewProj * currInvViewProj
        this.prevViewProj.mul(this.currInvViewProj, this.reprojectionMatrix);

        // Сохраняем текущую матрицу для следующего кадра
        this.prevViewProj.set(currViewProj);
    }

    /**
     * Записывает диспетчеризацию compute-шейдера реконструкции векторов движения.
     *
     * @param cmdBuf         командный буфер Vulkan
     * @param depthImageView активный ImageView буфера глубины
     */
    public void recordVelocityReconstruction(VkCommandBuffer cmdBuf, long depthImageView, int renderWidth, int renderHeight) {
        this.ensureSize(renderWidth, renderHeight);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 1. Обновление дескрипторов
            VkDescriptorImageInfo.Buffer velocityImageInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .imageView(this.velocityImageView)
                    .imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);

            VkDescriptorImageInfo.Buffer depthImageInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .sampler(this.depthSampler)
                    .imageView(depthImageView)
                    .imageLayout(VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);

            // Binding 0: Velocity Storage Image
            writes.get(0)
                    .sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(this.descriptorSet)
                    .dstBinding(0)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .pImageInfo(velocityImageInfo);

            // Binding 1: Depth Texture
            writes.get(1)
                    .sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(this.descriptorSet)
                    .dstBinding(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .pImageInfo(depthImageInfo);

            VK10.vkUpdateDescriptorSets(this.device, writes, null);

            // 2. Привязка Pipeline и дескрипторов
            VK10.vkCmdBindPipeline(cmdBuf, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, this.computePipeline);
            LongBuffer pSets = stack.longs(this.descriptorSet);
            VK10.vkCmdBindDescriptorSets(cmdBuf, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, this.pipelineLayout, 0, pSets, null);

            // 3. Заполнение Push Constants (96 байт)
            this.pushConstantBuffer.clear();
            this.reprojectionMatrix.get(0, this.pushConstantBuffer); // 64 байта: offset 0

            // Смещение камеры: delta = camPosCurr - camPosPrev
            float deltaX = (float) (this.currCameraPos.x - this.prevCameraPos.x);
            float deltaY = (float) (this.currCameraPos.y - this.prevCameraPos.y);
            float deltaZ = (float) (this.currCameraPos.z - this.prevCameraPos.z);
            this.pushConstantBuffer.putFloat(64, deltaX);
            this.pushConstantBuffer.putFloat(68, deltaY);
            this.pushConstantBuffer.putFloat(72, deltaZ);
            this.pushConstantBuffer.putFloat(76, 0.0f); // padding

            // Экранные параметры
            this.pushConstantBuffer.putFloat(80, (float) renderWidth);
            this.pushConstantBuffer.putFloat(84, (float) renderHeight);
            this.pushConstantBuffer.putFloat(88, 1.0f / (float) renderWidth);
            this.pushConstantBuffer.putFloat(92, 1.0f / (float) renderHeight);
            this.pushConstantBuffer.position(0);

            VK10.vkCmdPushConstants(cmdBuf, this.pipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, this.pushConstantBuffer);

            // 4. Диспетчеризация Compute Shader: local_size = 16x16
            int groupX = (renderWidth + 15) / 16;
            int groupY = (renderHeight + 15) / 16;
            VK10.vkCmdDispatch(cmdBuf, groupX, groupY, 1);

            // 5. Обновление положения камеры для следующего кадра
            this.prevCameraPos.set(this.currCameraPos);
        }
    }

    public long getVelocityImage() {
        return this.velocityImage;
    }

    public long getVelocityImageView() {
        return this.velocityImageView;
    }

    public int getWidth() {
        return this.width;
    }

    public int getHeight() {
        return this.height;
    }

    private void destroyImageResources() {
        if (this.velocityImageView != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyImageView(this.device, this.velocityImageView, null);
            this.velocityImageView = VK10.VK_NULL_HANDLE;
        }
        if (this.velocityImage != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyImage(this.device, this.velocityImage, null);
            this.velocityImage = VK10.VK_NULL_HANDLE;
        }
        if (this.velocityMemory != VK10.VK_NULL_HANDLE) {
            VK10.vkFreeMemory(this.device, this.velocityMemory, null);
            this.velocityMemory = VK10.VK_NULL_HANDLE;
        }
    }

    @Override
    public synchronized void close() {
        if (this.isClosed) {
            return;
        }

        this.context.waitIdle();
        this.destroyImageResources();

        if (this.depthSampler != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroySampler(this.device, this.depthSampler, null);
            this.depthSampler = VK10.VK_NULL_HANDLE;
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
        if (this.pipelineLayout != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyPipelineLayout(this.device, this.pipelineLayout, null);
            this.pipelineLayout = VK10.VK_NULL_HANDLE;
        }

        MemoryUtil.memFree(this.pushConstantBuffer);
        this.isClosed = true;

        LOGGER.info("Vuldium Velocity Pass успешно закрыт.");
    }
}
