package net.caffeinemc.mods.sodium.client.gui;

import net.caffeinemc.mods.sodium.api.util.ColorARGB;
import net.minecraft.util.Mth;

// colors in ARGB format
public class Colors {
    public static final int THEME = 0xFFFF4D15;
    public static final int THEME_LIGHTER = 0xFFFF8A50;
    public static final int THEME_DARKER = 0xFFD83B10;
    public static final int FOREGROUND = 0xFFFFFFFF;
    public static final int FOREGROUND_DISABLED = 0xFFAAAAAA;

    public static final int BACKGROUND_LIGHT = 0x40160805;
    public static final int BACKGROUND_MEDIUM = 0x60160805;
    public static final int BACKGROUND_HOVER = 0xE0220B07;
    public static final int BACKGROUND_OVERLAY = 0xEA1A0806;
    public static final int BACKGROUND_DEFAULT = 0x90140604;
    public static final int BACKGROUND_DARKER = 0xB0100403;
    public static final int BACKGROUND_HIGHLIGHT = 0x18FF5520;

    public static final int BUTTON_BORDER = 0x90FF4D15;

    // Vuldium Vulkan Aesthetic Tokens
    public static final int VULKAN_ACCENT = 0xFFFF5520;
    public static final int VULKAN_BADGE_BG = 0x80180805;
    public static final int VULKAN_STATUS_GREEN = 0xFF3DDC84;
    public static final int PANEL_DIVIDER = 0x35FF5520;
    public static final int PANEL_BORDER_SUBTLE = 0x22FFFFFF;
    public static final int BACKDROP_TOP = 0xF40A0302;
    public static final int BACKDROP_CENTER = 0xEA170704;
    public static final int BACKDROP_BOTTOM = 0xF40A0302;

    private static final float LIGHTEN_FACTOR = 0.3f;
    private static final float DARKEN_FACTOR = -0.23f;

    public static int darken(int color) {
        return adjust(color, DARKEN_FACTOR);
    }

    public static int lighten(int color) {
        return adjust(color, LIGHTEN_FACTOR);
    }

    public static int adjust(int color, float factor) {
        float[] hsv = ColorARGB.toHSV(color);
        var s = Mth.clamp(hsv[1] * (1 - Math.abs(factor)), 0, 1);
        var b = Mth.clamp(hsv[2] * (1 + factor), 0, 1);
        return ColorARGB.transferAlpha(ColorARGB.fromHSV(hsv[0], s, b), color);
    }

    public static int constrainColorHSV(int color, float minSaturation, float minBrightness) {
        float[] hsv = ColorARGB.toHSV(color);
        hsv[1] = Math.max(hsv[1], minSaturation);
        hsv[2] = Math.max(hsv[2], minBrightness);
        return ColorARGB.fromHSV(hsv);
    }
}
