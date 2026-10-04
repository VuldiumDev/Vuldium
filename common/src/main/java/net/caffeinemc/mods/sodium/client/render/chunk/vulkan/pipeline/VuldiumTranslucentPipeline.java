package net.caffeinemc.mods.sodium.client.render.chunk.vulkan.pipeline;

import net.caffeinemc.mods.sodium.client.gpu.device.vulkan.VuldiumDeviceContext;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkCommandBuffer;
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
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfo;
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
 * Специализированный графический конвейер Vulkan (VkPipeline) для рендеринга полупрозрачной
 * воксельной геометрии (Translucent Pass: вода, витражные стёкла, лёд, эффекты частиц).
 *
 * Архитектурные гарантии:
 * 1. Alpha Blending: (SRC_ALPHA, ONE_MINUS_SRC_ALPHA) для цвета, (ONE, ZERO) для альфа-канала.
 * 2. Depth Test без записи: depthTestEnable = true, depthWriteEnable = false (геометрия за водой не отсекается).
 * 3. Поддержка двухсторонних граней (VK_CULL_MODE_NONE) для поверхностей воды под/над водой.
 * 4. Совместимость с Dynamic Rendering (VK_KHR_dynamic_rendering / Vulkan 1.3) и классическими Render Pass.
 */
public class VuldiumTranslucentPipeline implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vuldium/TranslucentPipeline");

    private final VuldiumDeviceContext context;
    private final VkDevice device;
    private final int vertexStride;
    private final int cullMode;
    private final int depthCompareOp;

    private long pipelineLayout = VK10.VK_NULL_HANDLE;
    private long pipeline = VK10.VK_NULL_HANDLE;
    private boolean isClosed = false;

    public VuldiumTranslucentPipeline(
            VuldiumDeviceContext context,
            VuldiumShaderModule vertShader,
            VuldiumShaderModule fragShader,
            LongBuffer descriptorSetLayouts,
            long renderPass,
            long pipelineCache,
            int colorFormat,
            int depthFormat,
            int cullMode,
            int depthCompareOp,
            int vertexStride
    ) {
        this.context = context;
        this.device = context.getLogicalDevice();
        this.vertexStride = vertexStride;
        this.cullMode = cullMode;
        this.depthCompareOp = depthCompareOp;

        this.initLayout(descriptorSetLayouts);
        this.initPipeline(vertShader, fragShader, renderPass, pipelineCache, colorFormat, depthFormat);
    }

    public static VuldiumTranslucentPipeline createDefault(
            VuldiumDeviceContext context,
            VuldiumShaderModule vertShader,
            VuldiumShaderModule fragShader,
            LongBuffer descriptorSetLayouts,
            long renderPass,
            long pipelineCache,
            int colorFormat,
            int depthFormat
    ) {
        return new VuldiumTranslucentPipeline(
                context,
                vertShader,
                fragShader,
                descriptorSetLayouts,
                renderPass,
                pipelineCache,
                colorFormat,
                depthFormat,
                VK10.VK_CULL_MODE_NONE,
                VK10.VK_COMPARE_OP_GREATER_OR_EQUAL,
                20 // 20-байтный стандартный вертекс Sodium
        );
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
                throw new IllegalStateException("Ошибка создания VkPipelineLayout для Translucent Pipeline: " + res);
            }

            this.pipelineLayout = pLayout.get(0);
        }
    }

    private void initPipeline(
            VuldiumShaderModule vertShader,
            VuldiumShaderModule fragShader,
            long renderPass,
            long pipelineCache,
            int colorFormat,
            int depthFormat
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 1. Стадии шейдеров (Vertex + Fragment)
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).set(vertShader.createStageInfo(stack, "main"));
            stages.get(1).set(fragShader.createStageInfo(stack, "main"));

            // 2. Вершинный ввод (16-байтный Vector C или 20-байтный Sodium)
            VkVertexInputBindingDescription.Buffer bindings = VkVertexInputBindingDescription.calloc(1, stack);
            bindings.get(0)
                    .binding(0)
                    .stride(this.vertexStride)
                    .inputRate(VK10.VK_VERTEX_INPUT_RATE_VERTEX);

            VkVertexInputAttributeDescription.Buffer attributes = VkVertexInputAttributeDescription.calloc(4, stack);
            if (this.vertexStride == 16) {
                attributes.get(0).location(0).binding(0).format(VK10.VK_FORMAT_R32_UINT).offset(0);
                attributes.get(1).location(1).binding(0).format(VK10.VK_FORMAT_R8G8B8A8_UNORM).offset(4);
                attributes.get(2).location(2).binding(0).format(VK10.VK_FORMAT_R16G16_UINT).offset(8);
                attributes.get(3).location(3).binding(0).format(VK10.VK_FORMAT_R8G8B8A8_UINT).offset(12);
            } else {
                attributes.get(0).location(0).binding(0).format(VK10.VK_FORMAT_R32G32_UINT).offset(0);
                attributes.get(1).location(1).binding(0).format(VK10.VK_FORMAT_R8G8B8A8_UNORM).offset(8);
                attributes.get(2).location(2).binding(0).format(VK10.VK_FORMAT_R16G16_UINT).offset(12);
                attributes.get(3).location(3).binding(0).format(VK10.VK_FORMAT_R8G8B8A8_UINT).offset(16);
            }

            VkPipelineVertexInputStateCreateInfo vertexInputInfo = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
                    .pVertexBindingDescriptions(bindings)
                    .pVertexAttributeDescriptions(attributes);

            // 3. Сборка примитивов
            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                    .topology(VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)
                    .primitiveRestartEnable(false);

            // 4. Viewport / Scissor
            VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                    .viewportCount(1)
                    .scissorCount(1);

            // 5. Растеризация: настраиваемый cullMode (VK_CULL_MODE_NONE для воды)
            VkPipelineRasterizationStateCreateInfo rasterizer = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                    .depthClampEnable(false)
                    .rasterizerDiscardEnable(false)
                    .polygonMode(VK10.VK_POLYGON_MODE_FILL)
                    .cullMode(this.cullMode)
                    .frontFace(VK10.VK_FRONT_FACE_COUNTER_CLOCKWISE)
                    .depthBiasEnable(false)
                    .lineWidth(1.0f);

            // 6. Мультисэмплинг
            VkPipelineMultisampleStateCreateInfo multisampling = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                    .sampleShadingEnable(false)
                    .rasterizationSamples(VK10.VK_SAMPLE_COUNT_1_BIT);

            // 7. Depth / Stencil: тест глубины включен, но ЗАПИСЬ ГЛУБИНЫ ВЫКЛЮЧЕНА
            VkPipelineDepthStencilStateCreateInfo depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
                    .depthTestEnable(true)
                    .depthWriteEnable(false) // КРИТИЧНО ДЛЯ ПОЛУПРОЗРАЧНОСТИ
                    .depthCompareOp(this.depthCompareOp)
                    .depthBoundsTestEnable(false)
                    .stencilTestEnable(false);

            // 8. Color Blending: точный полупрозрачный режим
            VkPipelineColorBlendAttachmentState.Buffer colorBlendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack);
            colorBlendAttachment.colorWriteMask(
                    VK10.VK_COLOR_COMPONENT_R_BIT |
                            VK10.VK_COLOR_COMPONENT_G_BIT |
                            VK10.VK_COLOR_COMPONENT_B_BIT |
                            VK10.VK_COLOR_COMPONENT_A_BIT
            );
            colorBlendAttachment.blendEnable(true)
                    .srcColorBlendFactor(VK10.VK_BLEND_FACTOR_SRC_ALPHA)
                    .dstColorBlendFactor(VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                    .colorBlendOp(VK10.VK_BLEND_OP_ADD)
                    .srcAlphaBlendFactor(VK10.VK_BLEND_FACTOR_ONE)
                    .dstAlphaBlendFactor(VK10.VK_BLEND_FACTOR_ZERO)
                    .alphaBlendOp(VK10.VK_BLEND_OP_ADD);

            VkPipelineColorBlendStateCreateInfo colorBlending = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                    .logicOpEnable(false)
                    .pAttachments(colorBlendAttachment);

            // 9. Динамические состояния (Viewport и Scissor)
            IntBuffer dynamicStates = stack.ints(VK10.VK_DYNAMIC_STATE_VIEWPORT, VK10.VK_DYNAMIC_STATE_SCISSOR);
            VkPipelineDynamicStateCreateInfo dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
                    .pDynamicStates(dynamicStates);

            // 10. Pipeline Info
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
                    .layout(this.pipelineLayout);

            // Dynamic Rendering vs Classic RenderPass
            if (renderPass != VK10.VK_NULL_HANDLE) {
                pipelineInfo.renderPass(renderPass).subpass(0);
            } else {
                VkPipelineRenderingCreateInfo renderingCreateInfo = VkPipelineRenderingCreateInfo.calloc(stack)
                        .sType(VK13.VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO)
                        .pColorAttachmentFormats(stack.ints(colorFormat))
                        .depthAttachmentFormat(depthFormat);
                pipelineInfo.pNext(renderingCreateInfo.address());
            }

            LongBuffer pPipeline = stack.mallocLong(1);
            int res = VK10.vkCreateGraphicsPipelines(this.device, pipelineCache, pipelineInfo, null, pPipeline);
            if (res != VK10.VK_SUCCESS) {
                throw new IllegalStateException("Ошибка создания VkGraphicsPipeline для Translucent Pass: " + res);
            }

            this.pipeline = pPipeline.get(0);
            LOGGER.info("Vuldium Translucent Pipeline успешно скомпилирован (CullMode={}, DepthWrite=false)", this.cullMode);
        }
    }

    public long getPipelineHandle() {
        return this.pipeline;
    }

    public long getLayoutHandle() {
        return this.pipelineLayout;
    }

    public void bind(VkCommandBuffer cmdBuf) {
        VK10.vkCmdBindPipeline(cmdBuf, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, this.pipeline);
    }

    @Override
    public synchronized void close() {
        if (this.isClosed) {
            return;
        }

        if (this.pipeline != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyPipeline(this.device, this.pipeline, null);
            this.pipeline = VK10.VK_NULL_HANDLE;
        }

        if (this.pipelineLayout != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyPipelineLayout(this.device, this.pipelineLayout, null);
            this.pipelineLayout = VK10.VK_NULL_HANDLE;
        }

        this.isClosed = true;
    }
}
