package net.caffeinemc.mods.sodium.mixin.features.dfu;

import com.mojang.datafixers.DSL;
import com.mojang.datafixers.DataFixer;
import net.caffeinemc.mods.sodium.client.systems.dfu.VuldiumLazyDfu;
import net.minecraft.util.datafix.DataFixers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

@Mixin(DataFixers.class)
public abstract class DataFixersMixin {
    @Inject(method = "optimize", at = @At("HEAD"), cancellable = true)
    private static void cancelEagerOptimization(Set<DSL.TypeReference> types, CallbackInfoReturnable<CompletableFuture<?>> cir) {
        cir.setReturnValue(VuldiumLazyDfu.onOptimize(types));
    }

    @Inject(method = "getDataFixer", at = @At("RETURN"), cancellable = true)
    private static void wrapDataFixer(CallbackInfoReturnable<DataFixer> cir) {
        cir.setReturnValue(VuldiumLazyDfu.wrap(cir.getReturnValue()));
    }
}
