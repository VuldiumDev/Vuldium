package net.caffeinemc.mods.sodium.client.render.chunk.vulkan.upscale;

import net.caffeinemc.mods.sodium.client.gpu.device.vulkan.VuldiumDeviceContext;
import net.caffeinemc.mods.sodium.client.gpu.device.vulkan.capabilities.VuldiumDeviceCapabilities;
import net.caffeinemc.mods.sodium.client.gpu.device.vulkan.capabilities.UpscalerType;
import net.caffeinemc.mods.sodium.client.gpu.device.vulkan.sync.VuldiumSync2;
import net.caffeinemc.mods.sodium.client.render.chunk.vulkan.post.VuldiumVelocityPass;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;

/**
 * Центральный мост подсистемы масштабирования и постобработки Vuldium (Multi-AI Render Bridge).
 *
 * Архитектурные гарантии:
 * 1. Геометрия мира рендерится в пониженном разрешении (renderWidth x renderHeight).
 * 2. Апскейлер реконструирует кадр мира в нативное разрешение (targetWidth x targetHeight).
 * 3. UI-изоляция: ванильный интерфейс Minecraft (чат, инвентарь, экранные элементы)
 *    рисуется ПОВЕРХ масштабированного кадра в нативном экранном разрешении с нулевой потерей резкости.
 */
public class VuldiumUpscaleBridge implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vuldium/UpscaleBridge");

    private final VuldiumDeviceContext context;
    private final VkDevice device;
    private final VuldiumDeviceCapabilities capabilities;
    private final VuldiumSync2 sync2;

    private final VuldiumVelocityPass velocityPass;
    private final JitterHelper jitterHelper = new JitterHelper();

    private UpscaleQuality quality = UpscaleQuality.QUALITY;
    private float sharpness = 0.35f;

    private int renderWidth = 0;
    private int renderHeight = 0;
    private int targetWidth = 0;
    private int targetHeight = 0;

    // Буфер рендеринга мира (Render Resolution)
    private long worldImage = VK10.VK_NULL_HANDLE;
    private long worldMemory = VK10.VK_NULL_HANDLE;
    private long worldImageView = VK10.VK_NULL_HANDLE;

    // Масштабированный буфер мира (Target Resolution, до отрисовки UI)
    private long upscaledImage = VK10.VK_NULL_HANDLE;
    private long upscaledMemory = VK10.VK_NULL_HANDLE;
    private long upscaledImageView = VK10.VK_NULL_HANDLE;

    private UpscalerInstance activeUpscaler;
    private boolean isClosed = false;

    public VuldiumUpscaleBridge(VuldiumDeviceContext context) {
        this.context = context;
        this.device = context.getLogicalDevice();
        this.capabilities = new VuldiumDeviceCapabilities(context);
        this.sync2 = new VuldiumSync2(context);
        this.velocityPass = new VuldiumVelocityPass(context);

        this.capabilities.logSummary();

        // По умолчанию подключаем высокопроизводительный FSR Compute Upscaler
        this.activeUpscaler = new VuldiumFsrComputeUpscaler(context);

        LOGGER.info("Vuldium Upscale Bridge успешно запущен. Активный узел: {}", this.activeUpscaler.getType().getDisplayName());
    }

    /**
     * Проверяет и адаптирует внутренние буферы рендеринга под текущее разрешение окна.
     */
    public synchronized void ensureResolution(int targetW, int targetH, UpscaleQuality targetQuality) {
        if (this.targetWidth == targetW && this.targetHeight == targetH && this.quality == targetQuality && this.worldImage != VK10.VK_NULL_HANDLE) {
            return;
        }

        this.quality = targetQuality;
        this.targetWidth = targetW;
        this.targetHeight = targetH;
        this.renderWidth = targetQuality.getRenderWidth(targetW);
        this.renderHeight = targetQuality.getRenderHeight(targetH);

        this.destroyImageBuffers();
        this.createImageBuffers();

        this.velocityPass.ensureSize(this.renderWidth, this.renderHeight);
        this.activeUpscaler.init(this.renderWidth, this.renderHeight, this.targetWidth, this.targetHeight);

        LOGGER.info("Vuldium Upscale Bridge: геометрия мира {}x{} -> апскейл {}x{} (Профиль: {})",
                this.renderWidth, this.renderHeight, this.targetWidth, this.targetHeight, this.quality.getDisplayName());
    }

    private void createImageBuffers() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 1. Создание буфера мира (renderWidth x renderHeight, RGBA8_UNORM)
            this.worldImage = this.createVkImage(stack, this.renderWidth, this.renderHeight,
                    VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK10.VK_IMAGE_USAGE_SAMPLED_BIT);
            this.worldMemory = this.allocateAndBindMemory(stack, this.worldImage);
            this.worldImageView = this.createVkImageView(stack, this.worldImage);

            // 2. Создание буфера масштабированного кадра (targetWidth x targetHeight, RGBA8_UNORM)
            this.upscaledImage = this.createVkImage(stack, this.targetWidth, this.targetHeight,
                    VK10.VK_IMAGE_USAGE_STORAGE_BIT | VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK10.VK_IMAGE_USAGE_SAMPLED_BIT | VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
            this.upscaledMemory = this.allocateAndBindMemory(stack, this.upscaledImage);
            this.upscaledImageView = this.createVkImageView(stack, this.upscaledImage);
        }
    }

    private long createVkImage(MemoryStack stack, int w, int h, int usage) {
        VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                .imageType(VK10.VK_IMAGE_TYPE_2D)
                .format(VK10.VK_FORMAT_R8G8B8A8_UNORM)
                .mipLevels(1)
                .arrayLayers(1)
                .samples(VK10.VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK10.VK_IMAGE_TILING_OPTIMAL)
                .usage(usage)
                .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED);
        imageInfo.extent().set(w, h, 1);

        LongBuffer pImage = stack.mallocLong(1);
        VK10.vkCreateImage(this.device, imageInfo, null, pImage);
        return pImage.get(0);
    }

    private long allocateAndBindMemory(MemoryStack stack, long image) {
        VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
        VK10.vkGetImageMemoryRequirements(this.device, image, memReqs);

        int memTypeIndex = this.context.findMemoryTypeIndex(memReqs.memoryTypeBits(), VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(memReqs.size())
                .memoryTypeIndex(memTypeIndex);

        LongBuffer pMem = stack.mallocLong(1);
        VK10.vkAllocateMemory(this.device, allocInfo, null, pMem);
        long mem = pMem.get(0);

        VK10.vkBindImageMemory(this.device, image, mem, 0);
        return mem;
    }

    private long createVkImageView(MemoryStack stack, long image) {
        VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                .image(image)
                .viewType(VK10.VK_IMAGE_VIEW_TYPE_2D)
                .format(VK10.VK_FORMAT_R8G8B8A8_UNORM);
        viewInfo.subresourceRange()
                .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1);

        LongBuffer pView = stack.mallocLong(1);
        VK10.vkCreateImageView(this.device, viewInfo, null, pView);
        return pView.get(0);
    }

    /**
     * Обновляет историю матриц для генератора векторов движения.
     */
    public void updateMatrices(Matrix4f projection, Matrix4f modelView, double camX, double camY, double camZ) {
        this.velocityPass.updateMatrices(projection, modelView, camX, camY, camZ);
    }

    /**
     * Возвращает субпиксельное смещение камеры (Jitter) для текущего кадра.
     */
    public JitterHelper.JitterOffset getNextJitter() {
        if (this.quality == UpscaleQuality.NATIVE) {
            return new JitterHelper.JitterOffset(0.0f, 0.0f);
        }
        return this.jitterHelper.nextJitter(this.renderWidth, this.renderHeight);
    }

    /**
     * Выполняет полный цикл апскейлинга мира:
     * 1. Реконструкция векторов движения в VuldiumVelocityPass.
     * 2. Выполнение масштабирования через activeUpscaler (EASU + RCAS).
     * 3. Подготовка буфера для наложения нативного интерфейса.
     *
     * @param cmd          активный VkCommandBuffer
     * @param depthView    ImageView буфера глубины мира
     * @param resetHistory флаг сброса истории
     */
    public void executeUpscale(VkCommandBuffer cmd, long depthView, boolean resetHistory) {
        if (this.quality == UpscaleQuality.NATIVE) {
            return;
        }

        // 1. Реконструкция Motion Vectors на GPU
        this.velocityPass.recordVelocityReconstruction(cmd, depthView, this.renderWidth, this.renderHeight);

        // Барьер: запись векторов движения -> чтение апскейлером
        this.sync2.barrierComputeToCompute(cmd);

        // 2. Вызов ядра апскейлера (FSR / DLSS / XeSS)
        var jitter = this.jitterHelper.nextJitter(this.renderWidth, this.renderHeight);
        this.activeUpscaler.dispatch(
                cmd,
                this.worldImageView,
                depthView,
                this.velocityPass.getVelocityImageView(),
                this.upscaledImageView,
                jitter.x(),
                jitter.y(),
                this.sharpness,
                resetHistory
        );

        // 3. Барьер: выходной буфер апскейлера -> готов к композиции и отрисовке UI
        this.sync2.barrierComputeToColorAttachment(cmd, this.upscaledImage);
    }

    public long getWorldImageView() {
        return this.worldImageView;
    }

    public long getUpscaledImageView() {
        return (this.quality == UpscaleQuality.NATIVE) ? this.worldImageView : this.upscaledImageView;
    }

    public int getRenderWidth() {
        return this.renderWidth;
    }

    public int getRenderHeight() {
        return this.renderHeight;
    }

    public int getTargetWidth() {
        return this.targetWidth;
    }

    public int getTargetHeight() {
        return this.targetHeight;
    }

    public UpscaleQuality getQuality() {
        return this.quality;
    }

    public void setQuality(UpscaleQuality quality) {
        this.quality = quality;
    }

    public void setSharpness(float sharpness) {
        this.sharpness = Math.max(0.0f, Math.min(1.0f, sharpness));
    }

    public VuldiumDeviceCapabilities getCapabilities() {
        return this.capabilities;
    }

    public VuldiumVelocityPass getVelocityPass() {
        return this.velocityPass;
    }

    private void destroyImageBuffers() {
        if (this.worldImageView != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyImageView(this.device, this.worldImageView, null);
            this.worldImageView = VK10.VK_NULL_HANDLE;
        }
        if (this.worldImage != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyImage(this.device, this.worldImage, null);
            this.worldImage = VK10.VK_NULL_HANDLE;
        }
        if (this.worldMemory != VK10.VK_NULL_HANDLE) {
            VK10.vkFreeMemory(this.device, this.worldMemory, null);
            this.worldMemory = VK10.VK_NULL_HANDLE;
        }

        if (this.upscaledImageView != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyImageView(this.device, this.upscaledImageView, null);
            this.upscaledImageView = VK10.VK_NULL_HANDLE;
        }
        if (this.upscaledImage != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyImage(this.device, this.upscaledImage, null);
            this.upscaledImage = VK10.VK_NULL_HANDLE;
        }
        if (this.upscaledMemory != VK10.VK_NULL_HANDLE) {
            VK10.vkFreeMemory(this.device, this.upscaledMemory, null);
            this.upscaledMemory = VK10.VK_NULL_HANDLE;
        }
    }

    @Override
    public synchronized void close() {
        if (this.isClosed) {
            return;
        }

        this.context.waitIdle();
        this.destroyImageBuffers();

        if (this.activeUpscaler != null) {
            this.activeUpscaler.destroy();
        }
        if (this.velocityPass != null) {
            this.velocityPass.close();
        }

        this.isClosed = true;
        LOGGER.info("Vuldium Upscale Bridge успешно закрыт.");
    }
}
