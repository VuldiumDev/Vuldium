package net.caffeinemc.mods.sodium.mixin.features.particles;

import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ParticleEngine.class)
public class ParticleEngineMixin {
    @Inject(method = "createParticle", at = @At("HEAD"), cancellable = true)
    private void sodium$filterParticle(ParticleOptions options, double x, double y, double z, double xSpeed, double ySpeed, double zSpeed, CallbackInfoReturnable<Particle> cir) {
        if (!shouldSpawnParticle(options)) {
            cir.setReturnValue(null);
        }
    }

    @Unique
    private static boolean shouldSpawnParticle(ParticleOptions options) {
        if (options == null) {
            return true;
        }
        var pOpts = SodiumClientMod.options().particles;
        ParticleType<?> type = options.getType();
        if (type == null) {
            return true;
        }
        Identifier id = BuiltInRegistries.PARTICLE_TYPE.getKey(type);
        if (id == null) {
            return true;
        }

        String path = id.getPath();
        if (path.contains("rain") || path.contains("splash") || path.contains("fishing")) {
            return pOpts.rainSplash;
        }
        if (path.contains("smoke")) {
            return pOpts.smoke;
        }
        if (path.contains("block") || path.contains("dust")) {
            return pOpts.blockBreak;
        }
        if (path.contains("firework") || path.contains("flash")) {
            return pOpts.fireworks;
        }
        if (path.contains("effect") || path.contains("witch") || path.contains("potion")) {
            return pOpts.potions;
        }
        if (path.contains("explosion")) {
            return pOpts.explosions;
        }
        if (path.contains("drip") || path.contains("falling_") || path.contains("landing_")) {
            return pOpts.drips;
        }
        return pOpts.other;
    }
}
