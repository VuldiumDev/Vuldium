package net.caffeinemc.mods.sodium.client.gpu.device.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VKCapabilitiesDevice;
import org.lwjgl.vulkan.VKCapabilitiesInstance;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceLimits;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreTypeCreateInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Эталонная реализация ядра SodkamDeviceContext с поддержкой Dedicated Transfer Queue
 * и Timeline Semaphores для неблокирующей параллельной загрузки геометрии.
 */
public class SodkamDeviceContextImpl implements SodkamDeviceContext {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vuldium/DeviceContext");

    private final VkDevice device;
    private final VkPhysicalDevice physicalDevice;
    private final VkQueue graphicsQueue;
    private final int graphicsQueueFamilyIndex;

    private final VkQueue transferQueue;
    private final int transferQueueFamilyIndex;

    private final VKCapabilitiesDevice deviceCapabilities;
    private final VKCapabilitiesInstance instanceCapabilities;

    private final VkPhysicalDeviceProperties deviceProperties;
    private final VkPhysicalDeviceMemoryProperties memoryProperties;

    private long transferCommandPool = VK10.VK_NULL_HANDLE;
    private long transferTimelineSemaphore = VK10.VK_NULL_HANDLE;
    private final AtomicLong timelineCounter = new AtomicLong(0);

    private boolean isClosed = false;

    public SodkamDeviceContextImpl(VulkanContextBridge.NativeHandles handles) {
        this.device = handles.device();
        this.physicalDevice = handles.physicalDevice();
        this.graphicsQueue = handles.graphicsQueue();
        this.graphicsQueueFamilyIndex = handles.graphicsQueueFamilyIndex();
        this.transferQueue = handles.transferQueue();
        this.transferQueueFamilyIndex = handles.transferQueueFamilyIndex();

        this.deviceCapabilities = this.device.getCapabilities();
        this.instanceCapabilities = this.physicalDevice.getCapabilities();

        this.deviceProperties = VkPhysicalDeviceProperties.create();
        VK10.vkGetPhysicalDeviceProperties(this.physicalDevice, this.deviceProperties);

        this.memoryProperties = VkPhysicalDeviceMemoryProperties.create();
        VK10.vkGetPhysicalDeviceMemoryProperties(this.physicalDevice, this.memoryProperties);

        this.initTransferCommandPool();
        this.initTimelineSemaphore();

        LOGGER.info("Vuldium DeviceContext инициализирован: GPU={}, GraphicsFamily={}, TransferFamily={}",
                this.deviceProperties.deviceNameString(),
                this.graphicsQueueFamilyIndex,
                this.transferQueueFamilyIndex
        );
    }

    private void initTransferCommandPool() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    .queueFamilyIndex(this.transferQueueFamilyIndex)
                    .flags(VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);

            LongBuffer pPool = stack.mallocLong(1);
            int result = VK10.vkCreateCommandPool(this.device, poolInfo, null, pPool);
            if (result != VK10.VK_SUCCESS) {
                throw new IllegalStateException("Не удалось создать VkCommandPool для трансфера Sodkam: " + result);
            }

            this.transferCommandPool = pPool.get(0);
        }
    }

    private void initTimelineSemaphore() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSemaphoreTypeCreateInfo timelineInfo = VkSemaphoreTypeCreateInfo.calloc(stack)
                    .sType(VK12.VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO)
                    .semaphoreType(VK12.VK_SEMAPHORE_TYPE_TIMELINE)
                    .initialValue(0L);

            VkSemaphoreCreateInfo createInfo = VkSemaphoreCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO)
                    .pNext(timelineInfo.address());

            LongBuffer pSemaphore = stack.mallocLong(1);
            int res = VK10.vkCreateSemaphore(this.device, createInfo, null, pSemaphore);
            if (res != VK10.VK_SUCCESS) {
                throw new IllegalStateException("Не удалось создать Timeline Semaphore: " + res);
            }

            this.transferTimelineSemaphore = pSemaphore.get(0);
            LOGGER.info("Vuldium Timeline Semaphore успешно создан.");
        }
    }

    @Override
    public VkDevice getLogicalDevice() {
        return this.device;
    }

    @Override
    public VkPhysicalDevice getPhysicalDevice() {
        return this.physicalDevice;
    }

    @Override
    public VkQueue getGraphicsQueue() {
        return this.graphicsQueue;
    }

    @Override
    public int getGraphicsQueueFamilyIndex() {
        return this.graphicsQueueFamilyIndex;
    }

    @Override
    public VkQueue getTransferQueue() {
        return this.transferQueue;
    }

    @Override
    public int getTransferQueueFamilyIndex() {
        return this.transferQueueFamilyIndex;
    }

    @Override
    public long getTransferTimelineSemaphore() {
        return this.transferTimelineSemaphore;
    }

    @Override
    public long signalNextTransferTimelineValue() {
        return this.timelineCounter.incrementAndGet();
    }

    @Override
    public long getLastSubmittedTransferTimelineValue() {
        return this.timelineCounter.get();
    }

    @Override
    public VKCapabilitiesDevice getDeviceCapabilities() {
        return this.deviceCapabilities;
    }

    @Override
    public VKCapabilitiesInstance getInstanceCapabilities() {
        return this.instanceCapabilities;
    }

    @Override
    public VkPhysicalDeviceLimits getDeviceLimits() {
        return this.deviceProperties.limits();
    }

    @Override
    public VkPhysicalDeviceMemoryProperties getMemoryProperties() {
        return this.memoryProperties;
    }

    @Override
    public int findMemoryTypeIndex(int typeFilter, int requiredProperties) {
        for (int i = 0; i < this.memoryProperties.memoryTypeCount(); i++) {
            boolean isTypeSupported = (typeFilter & (1 << i)) != 0;
            boolean hasProperties = (this.memoryProperties.memoryTypes(i).propertyFlags() & requiredProperties) == requiredProperties;

            if (isTypeSupported && hasProperties) {
                return i;
            }
        }

        throw new IllegalStateException(String.format(
                "Не найден подходящий тип памяти Vulkan. Filter=0x%X, RequiredFlags=0x%X",
                typeFilter, requiredProperties
        ));
    }

    @Override
    public long getTransferCommandPool() {
        return this.transferCommandPool;
    }

    @Override
    public VkCommandBuffer allocateTransferCommandBuffer() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(this.transferCommandPool)
                    .level(VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);

            var pCommandBuffer = stack.mallocPointer(1);
            int result = VK10.vkAllocateCommandBuffers(this.device, allocInfo, pCommandBuffer);
            if (result != VK10.VK_SUCCESS) {
                throw new IllegalStateException("Ошибка аллокации VkCommandBuffer: " + result);
            }

            return new VkCommandBuffer(pCommandBuffer.get(0), this.device);
        }
    }

    @Override
    public void freeTransferCommandBuffer(VkCommandBuffer cmdBuf) {
        if (cmdBuf != null && this.transferCommandPool != VK10.VK_NULL_HANDLE) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var pCommandBuffer = stack.pointers(cmdBuf);
                VK10.vkFreeCommandBuffers(this.device, this.transferCommandPool, pCommandBuffer);
            }
        }
    }

    @Override
    public void waitIdle() {
        VK10.vkQueueWaitIdle(this.graphicsQueue);
        if (this.transferQueue != this.graphicsQueue) {
            VK10.vkQueueWaitIdle(this.transferQueue);
        }
    }

    @Override
    public String getDeviceName() {
        return this.deviceProperties != null ? this.deviceProperties.deviceNameString() : "Vulkan GPU";
    }

    @Override
    public void close() {
        if (this.isClosed) {
            return;
        }

        this.waitIdle();

        if (this.transferTimelineSemaphore != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroySemaphore(this.device, this.transferTimelineSemaphore, null);
            this.transferTimelineSemaphore = VK10.VK_NULL_HANDLE;
        }

        if (this.transferCommandPool != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyCommandPool(this.device, this.transferCommandPool, null);
            this.transferCommandPool = VK10.VK_NULL_HANDLE;
        }

        this.isClosed = true;
        LOGGER.info("Vuldium DeviceContext успешно закрыт.");
    }
}
