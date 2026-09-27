package net.caffeinemc.mods.sodium.client.render.chunk.vulkan.upscale;

/**
 * Генератор субпиксельного джиттера камеры на базе квазислучайной последовательности Халтона (Halton Sequence 2, 3).
 * Необходим для устранения алиасинга и реконструкции деталей субпиксельного уровня в темпоральных апскейлерах.
 */
public final class JitterHelper {
    private static final int DEFAULT_PHASE_COUNT = 16;
    private int currentPhase = 0;

    public record JitterOffset(float x, float y) {}

    public static float halton(int index, int base) {
        float result = 0.0f;
        float f = 1.0f / (float) base;
        int i = index;
        while (i > 0) {
            result += f * (float) (i % base);
            i /= base;
            f /= (float) base;
        }
        return result;
    }

    /**
     * Вычисляет следующее субпиксельное смещение проекции в диапазоне [-0.5 .. +0.5] пикселя.
     *
     * @param renderWidth  ширина буфера рендеринга
     * @param renderHeight высота буфера рендеринга
     * @return нормализованное смещение проекции камеры
     */
    public JitterOffset nextJitter(int renderWidth, int renderHeight) {
        this.currentPhase = (this.currentPhase + 1) % DEFAULT_PHASE_COUNT;

        float jx = (halton(this.currentPhase + 1, 2) - 0.5f) / (float) renderWidth;
        float jy = (halton(this.currentPhase + 1, 3) - 0.5f) / (float) renderHeight;

        return new JitterOffset(jx, jy);
    }

    public void reset() {
        this.currentPhase = 0;
    }
}
