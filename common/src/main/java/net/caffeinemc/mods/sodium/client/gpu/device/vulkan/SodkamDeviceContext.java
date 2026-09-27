package net.caffeinemc.mods.sodium.client.gpu.device.vulkan;

import org.lwjgl.vulkan.VKCapabilitiesDevice;
import org.lwjgl.vulkan.VKCapabilitiesInstance;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceLimits;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkQueue;

/**
 * Контекст графического устройства Vulkan для Sodkam.
 * Инкапсулирует перехваченные нативные дескрипторы устройства,
 * графической и трансферной очередей, Timeline Semaphores и пула команд трансфера.
 */
public interface SodkamDeviceContext extends AutoCloseable {

    /**
     * @return Логическое устройство Vulkan (VkDevice) игры.
     */
    VkDevice getLogicalDevice();

    /**
     * @return Физическое устройство GPU (VkPhysicalDevice).
     */
    VkPhysicalDevice getPhysicalDevice();

    /**
     * @return Основная графическая очередь VkQueue.
     */
    VkQueue getGraphicsQueue();

    /**
     * @return Индекс семейства очередей с поддержкой графики.
     */
    int getGraphicsQueueFamilyIndex();

    /**
     * @return Выделенная физическая очередь передачи данных (Dedicated Transfer Queue).
     */
    VkQueue getTransferQueue();

    /**
     * @return Индекс семейства очередей передачи данных.
     */
    int getTransferQueueFamilyIndex();

    /**
     * @return Дескриптор таймлайн-семафора (VkSemaphore Timeline) для синхронизации очередей.
     */
    long getTransferTimelineSemaphore();

    /**
     * Увеличивает и возвращает следующее сигнальное значение таймлайн-семафора.
     */
    long signalNextTransferTimelineValue();

    /**
     * @return Текущее (последнее зафиксированное) значение таймлайна.
     */
    long getLastSubmittedTransferTimelineValue();

    /**
     * @return Таблица возможностей и расширений логического устройства.
     */
    VKCapabilitiesDevice getDeviceCapabilities();

    /**
     * @return Таблица возможностей инстанса Vulkan.
     */
    VKCapabilitiesInstance getInstanceCapabilities();

    /**
     * @return Аппаратные лимиты GPU (выравнивания, Push Constants и т.д.).
     */
    VkPhysicalDeviceLimits getDeviceLimits();

    /**
     * @return Свойства доступных типов памяти хоста и GPU.
     */
    VkPhysicalDeviceMemoryProperties getMemoryProperties();

    /**
     * Поиск индекса типа памяти GPU по маске поддерживаемых типов и флагам свойств.
     *
     * @param typeFilter         битовая маска поддерживаемых типов (VkMemoryRequirements.memoryTypeBits)
     * @param requiredProperties битовая маска требуемых свойств (VK_MEMORY_PROPERTY_*)
     * @return индекс типа памяти
     */
    int findMemoryTypeIndex(int typeFilter, int requiredProperties);

    /**
     * Возвращает дескриптор выделенного пула команд для операций трансфера памяти.
     *
     * @return хэндл VkCommandPool
     */
    long getTransferCommandPool();

    /**
     * Выделяет одноразовый командный буфер для синхронных или асинхронных операций трансфера.
     *
     * @return аллоцированный VkCommandBuffer
     */
    VkCommandBuffer allocateTransferCommandBuffer();

    /**
     * Освобождает командный буфер трансфера.
     *
     * @param cmdBuf буфер для освобождения
     */
    void freeTransferCommandBuffer(VkCommandBuffer cmdBuf);

    /**
     * Синхронизация с полной остановкой графической очереди (vkQueueWaitIdle).
     */
    void waitIdle();

    /**
     * @return Человекочитаемое имя графического устройства GPU.
     */
    default String getDeviceName() {
        return "Vulkan GPU";
    }

    /**
     * Освобождение внутренних ресурсов Sodkam.
     */
    @Override
    void close();
}
