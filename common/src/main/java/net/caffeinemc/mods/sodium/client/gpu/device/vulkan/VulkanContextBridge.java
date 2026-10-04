package net.caffeinemc.mods.sodium.client.gpu.device.vulkan;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanQueue;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import net.caffeinemc.mods.sodium.mixin.core.GpuDeviceAccessor;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;

/**
 * Мост между внутренним контекстом RenderPearl/Blaze3D и подсистемой Vuldium.
 * Обеспечивает безопасное прямое извлечение нативных хэндлов Vulkan без ломких Mixin-инъекций.
 */
public final class VulkanContextBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vuldium/ContextBridge");

    private static Field CMD_BUF_FIELD;
    private static volatile boolean fallbackActive = false;
    private static volatile String fallbackReason = null;

    public record NativeHandles(
            VkDevice device,
            VkPhysicalDevice physicalDevice,
            VkQueue graphicsQueue,
            int graphicsQueueFamilyIndex,
            VkQueue transferQueue,
            int transferQueueFamilyIndex
    ) {}

    private VulkanContextBridge() {}

    public static boolean isFallbackActive() {
        return fallbackActive;
    }

    public static String getFallbackReason() {
        return fallbackReason;
    }

    public static synchronized void activateFallback(String reason) {
        fallbackActive = true;
        fallbackReason = reason;
        LOGGER.warn("[VULDIUM CRASH PREVENTION] Активирован безопасный Fallback-режим: {}", reason);
    }

    public static VuldiumDeviceContext createDeviceContextSafe() {
        if (fallbackActive) {
            LOGGER.warn("Vuldium работает в безопасном Fallback-режиме: {}", fallbackReason);
            return null;
        }

        try {
            NativeHandles handles = extractNativeHandles();
            if (handles == null || handles.device() == null) {
                activateFallback("Нативное устройство Vulkan от Mojang не найдено.");
                return null;
            }
            return new VuldiumDeviceContextImpl(handles);
        } catch (Throwable t) {
            activateFallback("Сбой при перехвате Vulkan-контекста: " + t.getMessage());
            LOGGER.error("Критическая ошибка инициализации нативного Vulkan. Активирован fallback-конвейер.", t);
            return null;
        }
    }

    /**
     * Проверяет, активен ли в данный момент нативный Vulkan-бэкенд RenderPearl.
     *
     * @return true, если активен VulkanDevice
     */
    public static boolean isVulkanBackend() {
        try {
            GpuDevice gpuDevice = RenderSystem.getDevice();
            if (gpuDevice instanceof GpuDeviceAccessor accessor) {
                GpuDeviceBackend backend = accessor.sodium$getBackend();
                return backend instanceof VulkanDevice;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Извлекает нативные структуры Vulkan из активного устройства RenderSystem.
     *
     * @return кортеж дескрипторов NativeHandles
     * @throws IllegalStateException если текущее устройство не является VulkanDevice
     */
    public static NativeHandles extractNativeHandles() {
        GpuDevice gpuDevice = RenderSystem.getDevice();
        if (gpuDevice == null) {
            throw new IllegalStateException("RenderSystem.getDevice() вернул null. Графическая подсистема не инициализирована.");
        }

        if (!(gpuDevice instanceof GpuDeviceAccessor accessor)) {
            throw new IllegalStateException("GpuDevice не реализует GpuDeviceAccessor: " + gpuDevice.getClass().getName());
        }

        GpuDeviceBackend backend = accessor.sodium$getBackend();
        if (backend == null) {
            throw new IllegalStateException("Backend устройства равен null.");
        }

        if (backend instanceof VulkanDevice vulkanDevice) {
            VkDevice device = vulkanDevice.vkDevice();
            VkPhysicalDevice physicalDevice = device.getPhysicalDevice();
            VulkanQueue gq = vulkanDevice.graphicsQueue();
            VkQueue graphicsQueue = gq.vkQueue();
            int graphicsQueueFamilyIndex = gq.queueFamilyIndex();

            VulkanQueue tq = vulkanDevice.transferQueue();
            VkQueue transferQueue = (tq != null) ? tq.vkQueue() : graphicsQueue;
            int transferQueueFamilyIndex = (tq != null) ? tq.queueFamilyIndex() : graphicsQueueFamilyIndex;

            LOGGER.info("Vuldium: нативные Vulkan-дескрипторы успешно извлечены (GraphicsFamily={}, TransferFamily={}).",
                    graphicsQueueFamilyIndex, transferQueueFamilyIndex);
            return new NativeHandles(device, physicalDevice, graphicsQueue, graphicsQueueFamilyIndex, transferQueue, transferQueueFamilyIndex);
        }

        throw new IllegalStateException("Текущий backend устройства не является VulkanDevice: " + backend.getClass().getName());
    }

    /**
     * Извлекает активный VkCommandBuffer из экземпляра RenderPass (VulkanRenderPass).
     *
     * @param renderPass активный проход рендера
     * @return нативный VkCommandBuffer или null, если не найден или если backend не Vulkan
     */
    public static VkCommandBuffer extractCommandBuffer(Object renderPass) {
        if (renderPass == null || !(renderPass instanceof VulkanRenderPass)) {
            return null;
        }

        try {
            if (CMD_BUF_FIELD == null) {
                CMD_BUF_FIELD = VulkanRenderPass.class.getDeclaredField("commandBuffer");
                CMD_BUF_FIELD.setAccessible(true);
            }
            return (VkCommandBuffer) CMD_BUF_FIELD.get(renderPass);
        } catch (Throwable t) {
            LOGGER.warn("Vuldium: не удалось извлечь commandBuffer из VulkanRenderPass: {}", t.getMessage());
            return null;
        }
    }
}
