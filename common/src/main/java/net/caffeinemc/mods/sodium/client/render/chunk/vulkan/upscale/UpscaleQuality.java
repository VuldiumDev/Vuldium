package net.caffeinemc.mods.sodium.client.render.chunk.vulkan.upscale;

/**
 * Профили качества динамического масштабирования (Multi-AI Render).
 */
public enum UpscaleQuality {
    NATIVE(1.0f, "Off"),
    ULTRA_QUALITY_PLUS(1.11f, "Ultra Q+ (90%)"),
    ULTRA_QUALITY(1.18f, "Ultra Q (85%)"),
    QUALITY_HIGH(1.30f, "Quality+ (77%)"),
    QUALITY(1.5f, "Quality (67%)"),
    BALANCED(1.7f, "Balanced (59%)"),
    PERFORMANCE(2.0f, "Perf (50%)"),
    ULTRA_PERFORMANCE(3.0f, "Ultra (33%)");

    private final float scaleFactor;
    private final String displayName;

    UpscaleQuality(float scaleFactor, String displayName) {
        this.scaleFactor = scaleFactor;
        this.displayName = displayName;
    }

    public float getScaleFactor() {
        return this.scaleFactor;
    }

    public String getDisplayName() {
        return this.displayName;
    }

    public int getRenderWidth(int targetWidth) {
        return Math.max(16, (int) Math.round(targetWidth / this.scaleFactor));
    }

    public int getRenderHeight(int targetHeight) {
        return Math.max(16, (int) Math.round(targetHeight / this.scaleFactor));
    }
}
