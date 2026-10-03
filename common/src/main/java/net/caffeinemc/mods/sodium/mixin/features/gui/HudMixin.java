package net.caffeinemc.mods.sodium.mixin.features.gui;

import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hud.class)
public abstract class HudMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    public abstract Font getFont();

    @Shadow
    public abstract boolean isHidden();

    @Inject(method = "extractVignette", at = @At("HEAD"), cancellable = true)
    private void sodium$cancelVignette(GuiGraphicsExtractor graphics, Entity entity, CallbackInfo ci) {
        if (!SodiumClientMod.options().details.vignette) {
            ci.cancel();
        }
    }

    @Inject(method = "extractSelectedItemName", at = @At("HEAD"), cancellable = true)
    private void sodium$cancelHeldItemTooltip(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (!SodiumClientMod.options().details.heldItemTooltips) {
            ci.cancel();
        }
    }

    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void sodium$renderHudOverlay(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (this.isHidden()) {
            return;
        }

        var extra = SodiumClientMod.options().extra;
        if (!extra.fpsHud && !extra.coordsHud) {
            return;
        }

        Font font = this.getFont();
        int y = 4;

        if (extra.fpsHud) {
            String fpsText = "FPS: " + this.minecraft.getFps();
            int width = font.width(fpsText);
            graphics.fill(3, y - 1, 5 + width, y + font.lineHeight, 0x80000000);
            graphics.text(font, fpsText, 4, y, 0xFF55FF55, false);
            y += font.lineHeight + 2;
        }

        if (extra.coordsHud && this.minecraft.player != null) {
            var player = this.minecraft.player;
            String coordsText = String.format("XYZ: %.1f / %.1f / %.1f", player.getX(), player.getY(), player.getZ());
            int width = font.width(coordsText);
            graphics.fill(3, y - 1, 5 + width, y + font.lineHeight, 0x80000000);
            graphics.text(font, coordsText, 4, y, 0xFFE0E0E0, false);
        }
    }
}
