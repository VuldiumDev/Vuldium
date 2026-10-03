package net.caffeinemc.mods.sodium.mixin.features.math;

import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(Mth.class)
public class MthMixin {
    @Shadow
    private static float[] SIN;

    /**
     * @author Vuldium
     * @reason High performance SIMD / fast-math trigonometric lookup
     */
    @Overwrite
    public static float sin(double val) {
        return SIN[(int) (long) (val * 10430.378350470453) & 0xFFFF];
    }

    /**
     * @author Vuldium
     * @reason High performance SIMD / fast-math trigonometric lookup
     */
    @Overwrite
    public static float cos(double val) {
        return SIN[(int) (long) (val * 10430.378350470453 + 16384.0) & 0xFFFF];
    }

    /**
     * @author Vuldium
     * @reason Fast invSqrt without intermediate conversions
     */
    @Overwrite
    public static float invSqrt(float x) {
        return 1.0F / (float) Math.sqrt(x);
    }

    /**
     * @author Vuldium
     * @reason Fast invSqrt double
     */
    @Overwrite
    public static double invSqrt(double x) {
        return 1.0D / Math.sqrt(x);
    }
}
