package net.caffeinemc.mods.sodium.mixin.features.extra;

import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.minecraft.client.gui.components.toasts.AdvancementToast;
import net.minecraft.client.gui.components.toasts.RecipeToast;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.gui.components.toasts.TutorialToast;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ToastManager.class)
public class ToastManagerMixin {
    @Inject(method = "addToast", at = @At("HEAD"), cancellable = true)
    private void sodium$filterToast(Toast toast, CallbackInfo ci) {
        var extra = SodiumClientMod.options().extra;
        if (!extra.advancementToasts && toast instanceof AdvancementToast) {
            ci.cancel();
            return;
        }
        if (!extra.recipeToasts && toast instanceof RecipeToast) {
            ci.cancel();
            return;
        }
        if (!extra.systemToasts && (toast instanceof SystemToast || toast instanceof TutorialToast)) {
            ci.cancel();
        }
    }
}
