package net.caffeinemc.mods.sodium.client.render.chunk.vulkan.pipeline;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

/**
 * Обертка над Vulkan VkShaderModule для загрузки и компиляции SPIR-V шейдеров.
 */
public class VuldiumShaderModule implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vuldium/ShaderModule");
    private static final int SPIRV_MAGIC = 0x07230203;

    private final VkDevice device;
    private final int stage;
    private long shaderModule = VK10.VK_NULL_HANDLE;

    public VuldiumShaderModule(VkDevice device, int stage, ByteBuffer spirvCode) {
        this.device = device;
        this.stage = stage;
        this.compile(spirvCode);
    }

    private void compile(ByteBuffer spirvCode) {
        if (spirvCode.remaining() % 4 != 0) {
            throw new IllegalArgumentException("Размер SPIR-V кода должен быть кратен 4 байтам");
        }

        // Проверка magic number SPIR-V (little-endian)
        int magic = spirvCode.getInt(spirvCode.position());
        if (magic != SPIRV_MAGIC) {
            throw new IllegalArgumentException(String.format("Неверный SPIR-V magic number: 0x%08X (ожидался 0x%08X)", magic, SPIRV_MAGIC));
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                    .pCode(spirvCode);

            LongBuffer pModule = stack.mallocLong(1);
            int res = VK10.vkCreateShaderModule(this.device, createInfo, null, pModule);
            if (res != VK10.VK_SUCCESS) {
                throw new IllegalStateException("Ошибка создания VkShaderModule: " + res);
            }

            this.shaderModule = pModule.get(0);
        }
    }

    public static VuldiumShaderModule fromResource(VkDevice device, int stage, String resourcePath) throws IOException {
        try (InputStream in = VuldiumShaderModule.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Не найден ресурс SPIR-V шейдера: " + resourcePath);
            }

            byte[] bytes = in.readAllBytes();
            ByteBuffer buffer = MemoryUtil.memAlloc(bytes.length);
            try {
                buffer.put(bytes).flip();
                return new VuldiumShaderModule(device, stage, buffer);
            } finally {
                MemoryUtil.memFree(buffer);
            }
        }
    }

    public long getHandle() {
        return this.shaderModule;
    }

    public int getStage() {
        return this.stage;
    }

    public VkPipelineShaderStageCreateInfo createStageInfo(MemoryStack stack, String entryPoint) {
        ByteBuffer pEntryPoint = stack.UTF8(entryPoint);
        return VkPipelineShaderStageCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                .stage(this.stage)
                .module(this.shaderModule)
                .pName(pEntryPoint);
    }

    @Override
    public void close() {
        if (this.shaderModule != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyShaderModule(this.device, this.shaderModule, null);
            this.shaderModule = VK10.VK_NULL_HANDLE;
        }
    }
}
