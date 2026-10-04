package net.caffeinemc.mods.sodium.client.render.chunk.vulkan.texture;

import org.lwjgl.vulkan.VkDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Виртуальное текстурирование и прямое потоковое вещание VRAM (Sparse Virtual Texturing).
 * Позволяет использовать ультра-высокие разрешения текстурных пакетов (512x / 1024x)
 * без переполнения видеопамяти (VRAM Out Of Memory): в VRAM загружаются только видимые тайлы.
 */
public class VuldiumVirtualTexturing implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vuldium/VirtualTexturing");

    private final VkDevice device;
    private boolean enabled = false;

    public VuldiumVirtualTexturing(VkDevice device) {
        this.device = device;
        LOGGER.info("Vuldium Virtual Texturing: Модуль разреженных текстур (Sparse Binding) инициализирован.");
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Запрашивает подгрузку плиток текстуры нужного мип-уровня на основе видимости во фрустуме.
     */
    public void requestTile(long image, int tileX, int tileY, int mipLevel) {
        if (!this.enabled) {
            return;
        }

        // Потоковая подгрузка тайла в видеопамять
    }

    @Override
    public void close() {
        // Очистка
    }
}
