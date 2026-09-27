package net.caffeinemc.mods.sodium.mixin.workarounds.context_creation;

import com.mojang.blaze3d.platform.Window;
import net.caffeinemc.mods.sodium.client.compatibility.workarounds.amd.AmdWorkarounds;
import net.caffeinemc.mods.sodium.client.compatibility.workarounds.nvidia.NvidiaWorkarounds;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Window.class)
public class WindowMixin {
    @Redirect(
            method = "createGlfwWindow",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J"),
            expect = 0,
            require = 0)
    private static long wrapGlfwCreateWindow(int w, int h, CharSequence title, long monitor, long share) {
        NvidiaWorkarounds.applyEnvironmentChanges();
        AmdWorkarounds.applyEnvironmentChanges();

        long handles;

        try {
            handles = GLFW.glfwCreateWindow(w, h, title, monitor, share);
        } finally {
            NvidiaWorkarounds.undoEnvironmentChanges();
            AmdWorkarounds.undoEnvironmentChanges();
        }

        return handles;
    }
}
