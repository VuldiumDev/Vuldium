package net.caffeinemc.mods.sodium.mixin.features.gui;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.gui.options.InactivityFpsLimitMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FramerateLimitTracker.class)
public class FramerateLimitTrackerMixin {
    @Shadow
    private int framerateLimit;

    @Inject(method = "getThrottleReason", at = @At("HEAD"), cancellable = true)
    private void sodkam$onGetThrottleReason(CallbackInfoReturnable<FramerateLimitTracker.FramerateThrottleReason> cir) {
        if (SodiumClientMod.options().performance.inactivityFpsLimit == InactivityFpsLimitMode.OFF) {
            cir.setReturnValue(FramerateLimitTracker.FramerateThrottleReason.NONE);
        }
    }

    @Inject(method = "getFramerateLimit", at = @At("HEAD"), cancellable = true)
    private void sodkam$onGetFramerateLimit(CallbackInfoReturnable<Integer> cir) {
        if (SodiumClientMod.options().performance.inactivityFpsLimit == InactivityFpsLimitMode.OFF) {
            cir.setReturnValue(this.framerateLimit);
        }
    }

    @Inject(method = "isHeavilyThrottled", at = @At("HEAD"), cancellable = true)
    private void sodkam$onIsHeavilyThrottled(CallbackInfoReturnable<Boolean> cir) {
        if (SodiumClientMod.options().performance.inactivityFpsLimit == InactivityFpsLimitMode.OFF) {
            cir.setReturnValue(false);
        }
    }
}
