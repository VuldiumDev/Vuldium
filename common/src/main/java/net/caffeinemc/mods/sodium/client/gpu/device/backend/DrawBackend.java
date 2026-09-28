package net.caffeinemc.mods.sodium.client.gpu.device.backend;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;

public enum DrawBackend {
    OPENGL,
    VK_MULTIDRAW,
    VK_INDIRECT;

    public static final DrawBackend BACKEND = chooseBackend();

    private static DrawBackend chooseBackend() {
        return DrawBackend.OPENGL;
    }

    public String getName() {
        return switch (this) {
            case OPENGL -> "gl_multidraw (sodium)";
            case VK_MULTIDRAW -> "ext_multidraw (sodium)";
            case VK_INDIRECT -> "indirect (sodium)";
        };
    }
}
