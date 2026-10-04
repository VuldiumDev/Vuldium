package net.caffeinemc.mods.sodium.client.render.chunk.vulkan.pipeline;

import net.caffeinemc.mods.sodium.client.gpu.device.vulkan.VuldiumDeviceContext;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

/**
 * Графический конвейер Vulkan (VkPipeline) для рендеринга геометрии чанков Vuldium.
 * Конфигурирует 20-байтный вершинный формат Sodium, Push Constants (128 байт),
 * тест глубины, блендинг и интеграцию с VkPipelineCache.
 */
public class VuldiumChunkPipeline implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vuldium/ChunkPipeline");

    private final VuldiumDeviceContext context;
    private final VkDevice device;
    private final boolean isTranslucent;
    private final int vertexStride;

    private long pipelineLayout = VK10.VK_NULL_HANDLE;
    private long pipeline = VK10.VK_NULL_HANDLE;

    public VuldiumChunkPipeline(
            VuldiumDeviceContext context,
            VuldiumShaderModule vertShader,
            VuldiumShaderModule fragShader,
            LongBuffer descriptorSetLayouts,
            long renderPass,
            long pipelineCache,
            boolean isTranslucent
    ) {
        this(context, vertShader, fragShader, descriptorSetLayouts, renderPass, pipelineCache, isTranslucent, 20);
    }

    public VuldiumChunkPipeline(
            VuldiumDeviceContext context,
            VuldiumShaderModule vertShader,
            VuldiumShaderModule fragShader,
            LongBuffer descriptorSetLayouts,
            long renderPass,
            long pipelineCache,
            boolean isTranslucent,
            int vertexStride
    ) {
        this.context = context;
        this.device = context.getLogicalDevice();
        this.isTranslucent = isTranslucent;
        this.vertexStride = vertexStride;

        this.initLayout(descriptorSetLayouts);
        this.initPipeline(vertShader, fragShader, renderPass, pipelineCache);
    }

    private void initLayout(LongBuffer descriptorSetLayouts) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPushConstantRange.Buffer pushConstants = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK10.VK_SHADER_STAGE_VERTEX_BIT | VK10.VK_SHADER_STAGE_FRAGMENT_BIT)
                    .offset(0)
                    .size(VuldiumPushConstants.SIZE);

            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pPushConstantRanges(pushConstants);

            if (descriptorSetLayouts != null && descriptorSetLayouts.hasRemaining()) {
                layoutInfo.pSetLayouts(descriptorSetLayouts);
            }

            LongBuffer pLayout = stack.mallocLong(1);
            int res = VK10.vkCreatePipelineLayout(this.device, layoutInfo, null, pLayout);
            if (res != VK10.VK_SUCCESS) {
                throw new IllegalStateException("Ошибка создания VkPipelineLayout: " + res);
            }

            this.pipelineLayout = pLayout.get(0);
        }
    }

    private void initPipeline(
            VuldiumShaderModule vertShader,
            VuldiumShaderModule fragShader,
            long renderPass,
            long pipelineCache
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 1. Стадии шейдеров
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).set(vertShader.createStageInfo(stack, "main"));
            stages.get(1).set(fragShader.createStageInfo(stack, "main"));

            // 2. Вершинный ввод: поддержка 16-байтного формата (Vector C) или 20-байтного Sodium
            VkVertexInputBindingDescription.Buffer bindings = VkVertexInputBindingDescription.calloc(1, stack);
            bindings.get(0)
                    .binding(0)
                    .stride(this.vertexStride)
                    .inputRate(VK10.VK_VERTEX_INPUT_RATE_VERTEX);

            VkVertexInputAttributeDescription.Buffer attributes = VkVertexInputAttributeDescription.calloc(4, stack);
            if (this.vertexStride == 16) {
                // Vector C (VuldiumPackedVertex):
                // - loc 0: R32_UINT (4 байта)
                // - loc 1: RGBA8_UNORM (4 байта)
                // - loc 2: RG16_UINT (4 байта)
                // - loc 3: RGBA8_UINT (4 байта)
                attributes.get(0).location(0).binding(0).format(VK10.VK_FORMAT_R32_UINT).offset(0);
                attributes.get(1).location(1).binding(0).format(VK10.VK_FORMAT_R8G8B8A8_UNORM).offset(4);
                attributes.get(2).location(2).binding(0).format(VK10.VK_FORMAT_R16G16_UINT).offset(8);
                attributes.get(3).location(3).binding(0).format(VK10.VK_FORMAT_R8G8B8A8_UINT).offset(12);
            } else {
                // Стандартный формат Sodium (20 байт):
                // - loc 0: RG32_UINT (8 байт)
                // - loc 1: RGBA8_UNORM (4 байта)
                // - loc 2: RG16_UINT (4 байта)
                // - loc 3: RGBA8_UINT (4 байта)
                attributes.get(0).location(0).binding(0).format(VK10.VK_FORMAT_R32G32_UINT).offset(0);
                attributes.get(1).location(1).binding(0).format(VK10.VK_FORMAT_R8G8B8A8_UNORM).offset(8);
                attributes.get(2).location(2).binding(0).format(VK10.VK_FORMAT_R16G16_UINT).offset(12);
                attributes.get(3).location(3).binding(0).format(VK10.VK_FORMAT_R8G8B8A8_UINT).offset(16);
            }

            VkPipelineVertexInputStateCreateInfo vertexInputInfo = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
                    .pVertexBindingDescriptions(bindings)
                    .pVertexAttributeDescriptions(attributes);

            // 3. Сборка примитивов: треугольники из квадов
            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                    .topology(VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)
                    .primitiveRestartEnable(false);

            // 4. Viewport / Scissor: Dynamic
            VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                    .viewportCount(1)
                    .scissorCount(1);

            // 5. Растеризатор
            VkPipelineRasterizationStateCreateInfo rasterizer = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                    .depthClampEnable(false)
                    .rasterizerDiscardEnable(false)
                    .polygonMode(VK10.VK_POLYGON_MODE_FILL)
                    .cullMode(VK10.VK_CULL_MODE_BACK_BIT)
                    .frontFace(VK10.VK_FRONT_FACE_COUNTER_CLOCKWISE)
                    .depthBiasEnable(false)
                    .lineWidth(1.0f);

            // 6. Multisample
            VkPipelineMultisampleStateCreateInfo multisampling = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                    .sampleShadingEnable(false)
                    .rasterizationSamples(VK10.VK_SAMPLE_COUNT_1_BIT);

            // 7. Depth / Stencil
            VkPipelineDepthStencilStateCreateInfo depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
                    .depthTestEnable(true)
                    .depthWriteEnable(!this.isTranslucent)
                    .depthCompareOp(VK10.VK_COMPARE_OP_GREATER_OR_EQUAL)
                    .depthBoundsTestEnable(false)
                    .stencilTestEnable(false);

            // 8. Color Blending
            VkPipelineColorBlendAttachmentState.Buffer colorBlendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack);
            colorBlendAttachment.colorWriteMask(
                    VK10.VK_COLOR_COMPONENT_R_BIT |
                            VK10.VK_COLOR_COMPONENT_G_BIT |
                            VK10.VK_COLOR_COMPONENT_B_BIT |
                            VK10.VK_COLOR_COMPONENT_A_BIT
            );

            if (this.isTranslucent) {
                colorBlendAttachment.blendEnable(true)
                        .srcColorBlendFactor(VK10.VK_BLEND_FACTOR_SRC_ALPHA)
                        .dstColorBlendFactor(VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                        .colorBlendOp(VK10.VK_BLEND_OP_ADD)
                        .srcAlphaBlendFactor(VK10.VK_BLEND_FACTOR_ONE)
                        .dstAlphaBlendFactor(VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                        .alphaBlendOp(VK10.VK_BLEND_OP_ADD);
            } else {
                colorBlendAttachment.blendEnable(false);
            }

            VkPipelineColorBlendStateCreateInfo colorBlending = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                    .logicOpEnable(false)
                    .pAttachments(colorBlendAttachment);

            // 9. Динамические состояния (Viewport и Scissor)
            IntBuffer dynamicStates = stack.ints(VK10.VK_DYNAMIC_STATE_VIEWPORT, VK10.VK_DYNAMIC_STATE_SCISSOR);
            VkPipelineDynamicStateCreateInfo dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
                    .pDynamicStates(dynamicStates);

            // 10. Создание Graphics Pipeline
            VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                    .pStages(stages)
                    .pVertexInputState(vertexInputInfo)
                    .pInputAssemblyState(inputAssembly)
                    .pViewportState(viewportState)
                    .pRasterizationState(rasterizer)
                    .pMultisampleState(multisampling)
                    .pDepthStencilState(depthStencil)
                    .pColorBlendState(colorBlending)
                    .pDynamicState(dynamicState)
                    .layout(this.pipelineLayout)
                    .renderPass(renderPass)
                    .subpass(0);

            LongBuffer pPipeline = stack.mallocLong(1);
            int res = VK10.vkCreateGraphicsPipelines(this.device, pipelineCache, pipelineInfo, null, pPipeline);
            if (res != VK10.VK_SUCCESS) {
                throw new IllegalStateException("Ошибка создания VkGraphicsPipeline: " + res);
            }

            this.pipeline = pPipeline.get(0);
            LOGGER.info("Vuldium Graphics Pipeline успешно создан: Translucent={}", this.isTranslucent);
        }
    }

    public long getPipelineHandle() {
        return this.pipeline;
    }

    public long getLayoutHandle() {
        return this.pipelineLayout;
    }

    public void bind(org.lwjgl.vulkan.VkCommandBuffer cmdBuf) {
        VK10.vkCmdBindPipeline(cmdBuf, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, this.pipeline);
    }

    @Override
    public void close() {
        if (this.pipeline != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyPipeline(this.device, this.pipeline, null);
            this.pipeline = VK10.VK_NULL_HANDLE;
        }

        if (this.pipelineLayout != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyPipelineLayout(this.device, this.pipelineLayout, null);
            this.pipelineLayout = VK10.VK_NULL_HANDLE;
        }
    }
}
