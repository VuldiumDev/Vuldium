package net.caffeinemc.mods.sodium.client.gpu.device.vulkan.sync;

import net.caffeinemc.mods.sodium.client.gpu.device.vulkan.SodkamDeviceContext;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;

/**
 * Синхронизатор кадров Vulkan для защиты участков буферов от гонок данных (data hazard)
 * между операциями DMA-копирования и активной отрисовкой на GPU.
 * Реализует семафорно-фенсовую модель Ring Buffer для N кадров в полёте (Frames in Flight).
 */
public class SodkamFrameSync implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vuldium/FrameSync");
    public static final int MAX_FRAMES_IN_FLIGHT = 2;

    private final SodkamDeviceContext context;
    private final VkDevice device;

    private final long[] inFlightFences = new long[MAX_FRAMES_IN_FLIGHT];
    private int currentFrame = 0;
    private boolean isClosed = false;

    public SodkamFrameSync(SodkamDeviceContext context) {
        this.context = context;
        this.device = context.getLogicalDevice();
        this.initFences();
    }

    private void initFences() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
                    .flags(VK10.VK_FENCE_CREATE_SIGNALED_BIT); // Инициализируем сигнальным состоянием

            LongBuffer pFence = stack.mallocLong(1);
            for (int i = 0; i < MAX_FRAMES_IN_FLIGHT; i++) {
                int res = VK10.vkCreateFence(this.device, fenceInfo, null, pFence);
                if (res != VK10.VK_SUCCESS) {
                    throw new IllegalStateException("Ошибка создания VkFence: " + res);
                }
                this.inFlightFences[i] = pFence.get(0);
            }
        }
    }

    /**
     * Ожидает завершения обработки GPU предыдущего кадра в данном слоте перед
     * началом модификации буферов или записи новых команд трансфера.
     */
    public void beginFrame() {
        long fence = this.inFlightFences[this.currentFrame];
        // Ожидание завершения кадра на GPU с таймаутом 1 секунда
        VK10.vkWaitForFences(this.device, fence, true, 1_000_000_000L);
        VK10.vkResetFences(this.device, fence);
    }

    /**
     * Переход к следующему кадру в кольцевой очереди.
     */
    public void endFrame() {
        this.currentFrame = (this.currentFrame + 1) % MAX_FRAMES_IN_FLIGHT;
    }

    public long getCurrentFence() {
        return this.inFlightFences[this.currentFrame];
    }

    public int getCurrentFrameIndex() {
        return this.currentFrame;
    }

    @Override
    public synchronized void close() {
        if (this.isClosed) {
            return;
        }

        this.context.waitIdle();

        for (int i = 0; i < MAX_FRAMES_IN_FLIGHT; i++) {
            if (this.inFlightFences[i] != VK10.VK_NULL_HANDLE) {
                VK10.vkDestroyFence(this.device, this.inFlightFences[i], null);
                this.inFlightFences[i] = VK10.VK_NULL_HANDLE;
            }
        }

        this.isClosed = true;
        LOGGER.info("Vuldium FrameSync успешно освобожден.");
    }
}
