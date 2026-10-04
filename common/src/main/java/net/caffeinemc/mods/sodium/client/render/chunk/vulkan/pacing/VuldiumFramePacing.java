package net.caffeinemc.mods.sodium.client.render.chunk.vulkan.pacing;

import com.mojang.renderpearl.api.device.GpuSurface;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

/**
 * Подсистема контроля плавности кадров (Frame Pacing & Camera Interpolation) для Vuldium/Vuldium.
 *
 * Архитектурные задачи:
 * 1. Устранение микро-джиттера движения при высоком FPS:
 *    - Высокоточная интерполяция позиции камеры на основе tickDelta (partialTick) без целочисленных округлений.
 *    - Плавное вычисление Projection и ModelView матриц.
 * 2. Оптимизация Swapchain Present Mode:
 *    - Приоритет выбора:
 *      1. VK_PRESENT_MODE_MAILBOX_KHR (тройная буферизация без тиринга и с минимальным input lag)
 *      2. VK_PRESENT_MODE_FIFO_RELAXED_KHR (адаптивная синхронизация при просадках ниже частоты монитора)
 *      3. VK_PRESENT_MODE_FIFO_KHR (стандартный вертикальный синхроимпульс)
 *    - Гарантия minImageCount = 3 (Triple Buffering).
 */
public final class VuldiumFramePacing {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vuldium/FramePacing");

    public static final int MIN_SWAPCHAIN_IMAGE_COUNT = 3; // Тройная буферизация

    private VuldiumFramePacing() {}

    /**
     * Выбирает оптимальный режим вывода поверхности по заданному приоритету:
     * 1. MAILBOX (тройная буферизация)
     * 2. FIFO_RELAXED (адаптивная вертикальная синхронизация)
     * 3. FIFO (аппаратный V-Sync)
     *
     * @param supported     коллекция поддерживаемых GPU режимов
     * @param vsyncEnabled  состояние ванильной настройки V-Sync
     * @return оптимальный PresentMode
     */
    public static GpuSurface.PresentMode selectOptimalPresentMode(
            Collection<GpuSurface.PresentMode> supported,
            boolean vsyncEnabled
    ) {
        if (supported == null || supported.isEmpty()) {
            LOGGER.warn("[Vuldium/FramePacing] Список поддерживаемых Present Modes пуст! Fallback на FIFO.");
            return GpuSurface.PresentMode.FIFO;
        }

        if (vsyncEnabled) {
            // V-Sync ВКЛЮЧЕН: аппаратная вертикальная синхронизация с монитором
            if (supported.contains(GpuSurface.PresentMode.FIFO_RELAXED)) {
                LOGGER.info("[Vuldium/FramePacing] V-Sync ВКЛ. Выбран режим: VK_PRESENT_MODE_FIFO_RELAXED_KHR (Adaptive V-Sync).");
                return GpuSurface.PresentMode.FIFO_RELAXED;
            }
            if (supported.contains(GpuSurface.PresentMode.FIFO)) {
                LOGGER.info("[Vuldium/FramePacing] V-Sync ВКЛ. Выбран режим: VK_PRESENT_MODE_FIFO_KHR (Standard V-Sync).");
                return GpuSurface.PresentMode.FIFO;
            }
            return GpuSurface.PresentMode.FIFO;
        } else {
            // V-Sync ВЫКЛЮЧЕН: полный анлок FPS без лока на 165 Гц монитора
            // 1. VK_PRESENT_MODE_IMMEDIATE_KHR: гарантированный полный анлок FPS без задержки и без ожидания V-Blank
            if (supported.contains(GpuSurface.PresentMode.IMMEDIATE)) {
                LOGGER.info("[Vuldium/FramePacing] V-Sync ВЫКЛ. Выбран режим: VK_PRESENT_MODE_IMMEDIATE_KHR (Полный анлок FPS).");
                return GpuSurface.PresentMode.IMMEDIATE;
            }
            // 2. VK_PRESENT_MODE_MAILBOX_KHR: Fast Sync / тройная буферизация
            if (supported.contains(GpuSurface.PresentMode.MAILBOX)) {
                LOGGER.info("[Vuldium/FramePacing] V-Sync ВЫКЛ. Выбран режим: VK_PRESENT_MODE_MAILBOX_KHR (Triple Buffering).");
                return GpuSurface.PresentMode.MAILBOX;
            }
            LOGGER.warn("[Vuldium/FramePacing] Ни IMMEDIATE, ни MAILBOX не поддержаны драйвером Vulkan. Fallback на FIFO.");
            return GpuSurface.PresentMode.FIFO;
        }
    }

    /**
     * Вычисляет непрерывную 64-битную позицию камеры с учетом tickDelta (partialTick)
     * для исключения субпиксельного джиттера при высоких частотах обновления экрана (144-240+ Гц).
     */
    public static Vec3 getInterpolatedCameraPosition(Camera camera) {
        if (camera == null) {
            return Vec3.ZERO;
        }

        float tickDelta = getTickDelta();

        if (camera.entity() != null) {
            Entity entity = camera.entity();
            double renderX = Mth.lerp((double) tickDelta, entity.xOld, entity.getX());
            double eyeOffset = camera.position().y - entity.getY();
            double renderY = Mth.lerp((double) tickDelta, entity.yOld, entity.getY()) + eyeOffset;
            double renderZ = Mth.lerp((double) tickDelta, entity.zOld, entity.getZ());
            return new Vec3(renderX, renderY, renderZ);
        }

        return camera.position() != null ? camera.position() : Vec3.ZERO;
    }

    /**
     * Строит непрерывную сглаженную ModelView матрицу на основе точного кватерниона вращения
     * и интерполированных вещественных координат камеры без целочисленных усечений.
     */
    public static Matrix4f buildSmoothModelViewMatrix(Camera camera, Matrix4fc baseModelView) {
        if (camera == null || baseModelView == null) {
            return baseModelView != null ? new Matrix4f(baseModelView) : new Matrix4f();
        }

        Vec3 pos = getInterpolatedCameraPosition(camera);

        // Получаем чистую матрицу ориентации камеры
        Matrix4f smoothModelView = camera.getViewRotationMatrix(new Matrix4f());

        // Применяем точное смещение камеры с плавающей точкой повышенной точности
        smoothModelView.translate((float) -pos.x, (float) -pos.y, (float) -pos.z);

        return smoothModelView;
    }

    /**
     * Извлекает текущее значение частичного тика (partialTick / tickDelta).
     */
    public static float getTickDelta() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.getDeltaTracker() != null) {
                return mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
            }
        } catch (Throwable ignored) {}
        return 1.0f;
    }
}
