package net.caffeinemc.mods.sodium.client.gpu.device.vulkan.capabilities;

import net.caffeinemc.mods.sodium.client.gpu.device.vulkan.SodkamDeviceContext;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRFragmentShadingRate;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VKCapabilitiesDevice;
import org.lwjgl.vulkan.VkExtent2D;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFragmentShadingRatePropertiesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Анализатор аппаратных возможностей активного физического устройства Vulkan (VkPhysicalDevice).
 * Определяет вендора, поддерживаемые расширения (VRS, Dynamic Rendering, Sync2),
 * рекомендуемые алгоритмы масштабирования (Multi-AI Render) и готовность к Frame Generation.
 */
public final class SodkamDeviceCapabilities {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vuldium/Capabilities");

    private final GpuVendor vendor;
    private final String deviceName;
    private final int vendorId;
    private final int deviceId;
    private final int driverVersion;
    private final int apiVersion;

    // Расширения конвейера
    private final boolean supportsFragmentShadingRate;
    private final boolean supportsSynchronization2;
    private final boolean supportsDynamicRendering;
    private final boolean supportsPushDescriptors;
    private final boolean supportsMeshShaders;
    private final boolean supportsRayQuery;
    private final boolean supportsAccelerationStructure;
    private final boolean supportsLowLatency;

    // VRS (Variable Rate Shading)
    private final int minShadingRateTexelWidth;
    private final int minShadingRateTexelHeight;
    private final int maxShadingRateTexelWidth;
    private final int maxShadingRateTexelHeight;

    // Апскейлинг и генерация кадров
    private final UpscalerType primaryUpscaler;
    private final List<UpscalerType> availableUpscalers;
    private final boolean supportsFrameGeneration;

    // Descriptor Indexing (Bindless)
    private final boolean supportsDescriptorIndexing;
    private final boolean supportsNonUniformIndexing;
    private final boolean supportsPartiallyBound;
    private final boolean supportsVariableDescriptorCount;
    private final boolean supportsRuntimeDescriptorArray;

    public SodkamDeviceCapabilities(SodkamDeviceContext context) {
        VkPhysicalDevice physicalDevice = context.getPhysicalDevice();
        VKCapabilitiesDevice deviceCaps = context.getDeviceCapabilities();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
            VK10.vkGetPhysicalDeviceProperties(physicalDevice, props);

            this.vendorId = props.vendorID();
            this.deviceId = props.deviceID();
            this.deviceName = props.deviceNameString();
            this.driverVersion = props.driverVersion();
            this.apiVersion = props.apiVersion();
            this.vendor = GpuVendor.fromVendorId(this.vendorId);
        }

        // Проверка ключевых расширений
        this.supportsFragmentShadingRate = deviceCaps.VK_KHR_fragment_shading_rate;
        this.supportsSynchronization2 = deviceCaps.VK_KHR_synchronization2;
        this.supportsDynamicRendering = deviceCaps.VK_KHR_dynamic_rendering;
        this.supportsPushDescriptors = deviceCaps.VK_KHR_push_descriptor;
        this.supportsMeshShaders = deviceCaps.VK_EXT_mesh_shader;
        this.supportsRayQuery = deviceCaps.VK_KHR_ray_query;
        this.supportsAccelerationStructure = deviceCaps.VK_KHR_acceleration_structure;
        this.supportsLowLatency = deviceCaps.VK_NV_low_latency || deviceCaps.VK_NV_low_latency2;

        // Запрос аппаратных возможностей Descriptor Indexing (Bindless Pipeline)
        boolean hasDescIdx = deviceCaps.VK_EXT_descriptor_indexing || (this.apiVersion >= VK10.VK_MAKE_VERSION(1, 2, 0));
        boolean nonUniform = false;
        boolean partBound = false;
        boolean varCount = false;
        boolean runtimeArr = false;

        if (hasDescIdx) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                org.lwjgl.vulkan.VkPhysicalDeviceFeatures2 features2 = org.lwjgl.vulkan.VkPhysicalDeviceFeatures2.calloc(stack)
                        .sType(org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2);

                org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features features12 = org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features.calloc(stack)
                        .sType(org.lwjgl.vulkan.VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES);
                features2.pNext(features12.address());

                org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2(physicalDevice, features2);

                nonUniform = features12.shaderSampledImageArrayNonUniformIndexing();
                partBound = features12.descriptorBindingPartiallyBound();
                varCount = features12.descriptorBindingVariableDescriptorCount();
                runtimeArr = features12.runtimeDescriptorArray();
            } catch (Throwable t) {
                LOGGER.warn("Не удалось прочитать параметры VkPhysicalDeviceVulkan12Features: {}", t.getMessage());
                // Fallback для Vulkan 1.2+
                nonUniform = true;
                partBound = true;
                varCount = true;
                runtimeArr = true;
            }
        }
        this.supportsDescriptorIndexing = hasDescIdx;
        this.supportsNonUniformIndexing = nonUniform;
        this.supportsPartiallyBound = partBound;
        this.supportsVariableDescriptorCount = varCount;
        this.supportsRuntimeDescriptorArray = runtimeArr;

        // Запрос свойств аппаратного VRS при наличии расширения
        int minTexW = 0, minTexH = 0, maxTexW = 0, maxTexH = 0;
        if (this.supportsFragmentShadingRate) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkPhysicalDeviceFragmentShadingRatePropertiesKHR fsrProps =
                        VkPhysicalDeviceFragmentShadingRatePropertiesKHR.calloc(stack)
                                .sType(KHRFragmentShadingRate.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FRAGMENT_SHADING_RATE_PROPERTIES_KHR);

                VkPhysicalDeviceProperties2 props2 = VkPhysicalDeviceProperties2.calloc(stack)
                        .sType(org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2)
                        .pNext(fsrProps.address());

                org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2(physicalDevice, props2);

                VkExtent2D minExtent = fsrProps.minFragmentShadingRateAttachmentTexelSize();
                VkExtent2D maxExtent = fsrProps.maxFragmentShadingRateAttachmentTexelSize();
                minTexW = minExtent.width();
                minTexH = minExtent.height();
                maxTexW = maxExtent.width();
                maxTexH = maxExtent.height();
            } catch (Throwable t) {
                LOGGER.warn("Не удалось прочитать параметры VK_KHR_fragment_shading_rate: {}", t.getMessage());
            }
        }
        this.minShadingRateTexelWidth = minTexW;
        this.minShadingRateTexelHeight = minTexH;
        this.maxShadingRateTexelWidth = maxTexW;
        this.maxShadingRateTexelHeight = maxTexH;

        // Определение доступных алгоритмов апскейлинга
        List<UpscalerType> upscalers = new ArrayList<>();
        upscalers.add(UpscalerType.NONE);
        upscalers.add(UpscalerType.FSR_GENERIC);
        upscalers.add(UpscalerType.FSR);

        if (this.vendor == GpuVendor.NVIDIA) {
            upscalers.add(0, UpscalerType.DLSS);
            this.primaryUpscaler = UpscalerType.DLSS;
        } else if (this.vendor == GpuVendor.INTEL) {
            upscalers.add(0, UpscalerType.XESS);
            this.primaryUpscaler = UpscalerType.XESS;
        } else {
            this.primaryUpscaler = UpscalerType.FSR;
        }
        this.availableUpscalers = Collections.unmodifiableList(upscalers);

        // Frame Generation поддерживается на современных GPU (Vulkan 1.2+ и поддержка Compute)
        this.supportsFrameGeneration = (this.apiVersion >= VK10.VK_MAKE_VERSION(1, 2, 0));
    }

    public void logSummary() {
        int apiMajor = VK10.VK_VERSION_MAJOR(this.apiVersion);
        int apiMinor = VK10.VK_VERSION_MINOR(this.apiVersion);
        int apiPatch = VK10.VK_VERSION_PATCH(this.apiVersion);

        LOGGER.info("====================== Sodkam GPU Capabilities ======================");
        LOGGER.info("Устройство: {} (Vendor: {} [0x{}], DeviceID: 0x{})",
                this.deviceName, this.vendor.getVendorName(), Integer.toHexString(this.vendorId).toUpperCase(), Integer.toHexString(this.deviceId).toUpperCase());
        LOGGER.info("Vulkan API: {}.{}.{} | Драйвер: 0x{}", apiMajor, apiMinor, apiPatch, Integer.toHexString(this.driverVersion));
        LOGGER.info("Приоритетный AI-апскейлер: {} | Доступно апскейлеров: {}", this.primaryUpscaler.getDisplayName(), this.availableUpscalers.size());
        LOGGER.info("Frame Generation: {}", this.supportsFrameGeneration ? "Поддерживается (Optical Flow / FSR 3 FG)" : "Не поддерживается");
        LOGGER.info("Hardware VRS (Fragment Shading Rate): {}",
                this.supportsFragmentShadingRate ? String.format("Поддерживается (Texel Size: min %dx%d, max %dx%d)",
                        this.minShadingRateTexelWidth, this.minShadingRateTexelHeight,
                        this.maxShadingRateTexelWidth, this.maxShadingRateTexelHeight) : "Не поддерживается");
        LOGGER.info("Bindless Pipeline (Descriptor Indexing): {}",
                this.supportsDescriptorIndexing ? String.format("Поддерживается (NonUniform=%b, PartiallyBound=%b, VarCount=%b, RuntimeArray=%b)",
                        this.supportsNonUniformIndexing, this.supportsPartiallyBound, this.supportsVariableDescriptorCount, this.supportsRuntimeDescriptorArray) : "Не поддерживается");
        LOGGER.info("Hardware Ray Tracing (VK_KHR_ray_query): {}", this.supportsRayQuery ? "Поддерживается (RTAO / Contact Shadows)" : "Не поддерживается");
        LOGGER.info("Mesh Shaders (VK_EXT_mesh_shader): {}", this.supportsMeshShaders ? "Поддерживается (Meshlet Pipeline)" : "Не поддерживается");
        LOGGER.info("Low Latency (Reflex / Anti-Lag): {}", this.supportsLowLatency ? "Поддерживается" : "Эмулируется семафорами презентации");
        LOGGER.info("Расширения: Synchronization2={}, DynamicRendering={}, PushDescriptors={}",
                this.supportsSynchronization2, this.supportsDynamicRendering, this.supportsPushDescriptors);
        LOGGER.info("=====================================================================");
    }

    public GpuVendor getVendor() {
        return this.vendor;
    }

    public String getDeviceName() {
        return this.deviceName;
    }

    public int getVendorId() {
        return this.vendorId;
    }

    public int getDeviceId() {
        return this.deviceId;
    }

    public int getApiVersion() {
        return this.apiVersion;
    }

    public boolean isFragmentShadingRateSupported() {
        return this.supportsFragmentShadingRate;
    }

    public boolean isSynchronization2Supported() {
        return this.supportsSynchronization2;
    }

    public boolean isDynamicRenderingSupported() {
        return this.supportsDynamicRendering;
    }

    public boolean isPushDescriptorsSupported() {
        return this.supportsPushDescriptors;
    }

    public int getMinShadingRateTexelWidth() {
        return this.minShadingRateTexelWidth;
    }

    public int getMinShadingRateTexelHeight() {
        return this.minShadingRateTexelHeight;
    }

    public int getMaxShadingRateTexelWidth() {
        return this.maxShadingRateTexelWidth;
    }

    public int getMaxShadingRateTexelHeight() {
        return this.maxShadingRateTexelHeight;
    }

    public UpscalerType getPrimaryUpscaler() {
        return this.primaryUpscaler;
    }

    public List<UpscalerType> getAvailableUpscalers() {
        return this.availableUpscalers;
    }

    public boolean isFrameGenerationSupported() {
        return this.supportsFrameGeneration;
    }

    public boolean isMeshShadersSupported() {
        return this.supportsMeshShaders;
    }

    public boolean isRayQuerySupported() {
        return this.supportsRayQuery;
    }

    public boolean isAccelerationStructureSupported() {
        return this.supportsAccelerationStructure;
    }

    public boolean isLowLatencySupported() {
        return this.supportsLowLatency;
    }

    public boolean isDescriptorIndexingSupported() {
        return this.supportsDescriptorIndexing;
    }

    public boolean isNonUniformIndexingSupported() {
        return this.supportsNonUniformIndexing;
    }

    public boolean isPartiallyBoundSupported() {
        return this.supportsPartiallyBound;
    }

    public boolean isVariableDescriptorCountSupported() {
        return this.supportsVariableDescriptorCount;
    }

    public boolean isRuntimeDescriptorArraySupported() {
        return this.supportsRuntimeDescriptorArray;
    }
}
