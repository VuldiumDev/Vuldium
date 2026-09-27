package net.caffeinemc.mods.sodium.mixin.workarounds.window_minimized_state;

import com.mojang.blaze3d.platform.Window;
import net.caffeinemc.mods.sodium.client.compatibility.workarounds.Workarounds;
import net.minecraft.client.renderer.GameRenderer;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Unique
    private final boolean sodium$redirectWindowMinimizedState =
            Workarounds.isWorkaroundEnabled(Workarounds.Reference.INTEL_FRAMEBUFFER_BLIT_CRASH_WHEN_UNFOCUSED);

    @Redirect(method = "extractWindow",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;isIconified()Z"))
    private boolean redirectWindowMinimized(Window window) {
        return window.isIconified() || GLFW.glfwGetWindowAttrib(window.handle(), GLFW.GLFW_ICONIFIED) != 0;
    }
}
