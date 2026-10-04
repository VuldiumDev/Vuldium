package net.caffeinemc.mods.sodium.client.render.chunk.vulkan.pipeline;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPipelineCacheCreateInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Управление кешем конвейеров Vulkan (VkPipelineCache).
 * Сохраняет скомпилированные пайплайны на диск для устранения микрофризов при запуске.
 */
public class VuldiumPipelineCache implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vuldium/PipelineCache");

    private final VkDevice device;
    private final Path cacheFilePath;
    private long pipelineCache = VK10.VK_NULL_HANDLE;

    public VuldiumPipelineCache(VkDevice device, Path cacheFilePath) {
        this.device = device;
        this.cacheFilePath = cacheFilePath;
        this.init();
    }

    private void init() {
        ByteBuffer initialData = null;
        if (this.cacheFilePath != null && Files.exists(this.cacheFilePath)) {
            try {
                byte[] bytes = Files.readAllBytes(this.cacheFilePath);
                initialData = MemoryUtil.memAlloc(bytes.length);
                initialData.put(bytes).flip();
                LOGGER.info("Загружен существующий VkPipelineCache размером {} байт", bytes.length);
            } catch (IOException e) {
                LOGGER.warn("Не удалось прочитать файл кеша пайплайнов, создается новый", e);
            }
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPipelineCacheCreateInfo createInfo = VkPipelineCacheCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO);

            if (initialData != null) {
                createInfo.pInitialData(initialData);
            }

            LongBuffer pCache = stack.mallocLong(1);
            int res = VK10.vkCreatePipelineCache(this.device, createInfo, null, pCache);
            if (res != VK10.VK_SUCCESS) {
                LOGGER.error("Не удалось создать VkPipelineCache: res={}. Кеширование отключено.", res);
                this.pipelineCache = VK10.VK_NULL_HANDLE;
            } else {
                this.pipelineCache = pCache.get(0);
            }
        } finally {
            if (initialData != null) {
                MemoryUtil.memFree(initialData);
            }
        }
    }

    public long getHandle() {
        return this.pipelineCache;
    }

    /**
     * Сохраняет данные кеша на диск.
     */
    public void saveToDisk() {
        if (this.pipelineCache == VK10.VK_NULL_HANDLE || this.cacheFilePath == null) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pDataSize = stack.mallocPointer(1);
            int res = VK10.vkGetPipelineCacheData(this.device, this.pipelineCache, pDataSize, null);
            if (res != VK10.VK_SUCCESS) {
                LOGGER.warn("Ошибка получения размера VkPipelineCacheData: {}", res);
                return;
            }

            long dataSize = pDataSize.get(0);
            if (dataSize <= 0) {
                return;
            }

            ByteBuffer dataBuffer = MemoryUtil.memAlloc((int) dataSize);
            try {
                res = VK10.vkGetPipelineCacheData(this.device, this.pipelineCache, pDataSize, dataBuffer);
                if (res == VK10.VK_SUCCESS) {
                    byte[] bytes = new byte[(int) dataSize];
                    dataBuffer.get(bytes);
                    if (this.cacheFilePath.getParent() != null) {
                        Files.createDirectories(this.cacheFilePath.getParent());
                    }
                    Files.write(this.cacheFilePath, bytes);
                    LOGGER.info("Сохранён VkPipelineCache размером {} байт на диск.", dataSize);
                }
            } catch (IOException e) {
                LOGGER.error("Не удалось записать файл VkPipelineCache на диск", e);
            } finally {
                MemoryUtil.memFree(dataBuffer);
            }
        }
    }

    @Override
    public void close() {
        if (this.pipelineCache != VK10.VK_NULL_HANDLE) {
            this.saveToDisk();
            VK10.vkDestroyPipelineCache(this.device, this.pipelineCache, null);
            this.pipelineCache = VK10.VK_NULL_HANDLE;
        }
    }
}
