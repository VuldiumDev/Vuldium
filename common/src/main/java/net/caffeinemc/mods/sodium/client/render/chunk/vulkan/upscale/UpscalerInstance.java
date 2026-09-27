package net.caffeinemc.mods.sodium.client.render.chunk.vulkan.upscale;

import net.caffeinemc.mods.sodium.client.gpu.device.vulkan.capabilities.UpscalerType;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Унифицированный интерфейс узла апскейлинга (Multi-AI Render Node).
 * Поддерживает как пространственно-временной FSR (Vulkan Compute),
 * так и нативные биндинги DLSS/XeSS/FSR SDK.
 */
public interface UpscalerInstance extends AutoCloseable {

    /**
     * Инициализирует ресурсы под заданное входное и выходное разрешения.
     */
    void init(int renderWidth, int renderHeight, int targetWidth, int targetHeight);

    /**
     * Записывает вызовы масштабирования в командный буфер Vulkan.
     *
     * @param cmd             активный командный буфер
     * @param colorInView     ImageView входного буфера мира (renderWidth x renderHeight)
     * @param depthInView     ImageView буфера глубины мира
     * @param velocityInView  ImageView буфера экранной скорости (Motion Vectors)
     * @param colorOutView    ImageView выходного буфера целевого разрешения (targetWidth x targetHeight)
     * @param jitterX         субпиксельное смещение камеры по X
     * @param jitterY         субпиксельное смещение камеры по Y
     * @param sharpness       коэффициент резкости [0.0 .. 1.0]
     * @param resetHistory    флаг сброса истории (при телепортации игрока или загрузке мира)
     */
    void dispatch(
            VkCommandBuffer cmd,
            long colorInView,
            long depthInView,
            long velocityInView,
            long colorOutView,
            float jitterX,
            float jitterY,
            float sharpness,
            boolean resetHistory
    );

    /**
     * Освобождает нативные ресурсы.
     */
    void destroy();

    /**
     * @return тип апскейлера
     */
    UpscalerType getType();

    /**
     * @return доступен ли апскейлер на текущем оборудовании
     */
    boolean isSupported();

    @Override
    default void close() {
        this.destroy();
    }
}
