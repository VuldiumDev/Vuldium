package com.mojang.blaze3d.textures;

/**
 * Compatibility shim for MC 26.2+ where TextureFormat was removed.
 * In MC 26.1, this class exists as a real MC class and this shim is NOT compiled.
 * In MC 26.2+, TextureFormat was removed; the equivalent is GpuFormat.
 *
 * Code that uses TextureFormat.RED8I should migrate to GpuFormat.R8_SINT + BindGroupLayout.
 * This shim exists so that references to TextureFormat.RED8I compile in 26.2+ without errors.
 * At runtime, the field value is irrelevant since withUniform(TextureFormat) no longer exists.
 */
public enum TextureFormat {
    // In 26.1: RED8I = 1-channel 8-bit signed int texel buffer format
    RED8I;
}
