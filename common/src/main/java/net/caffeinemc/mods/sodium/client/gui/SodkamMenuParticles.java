package net.caffeinemc.mods.sodium.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Атмосферная система парящих осенних листьев для меню настроек Sodkam.
 * Обеспечивает плавное покачивание, физику ветра и реакцию на курсор мыши.
 */
public class SodkamMenuParticles {
    public static final Identifier LEAVES_TEXTURE = Identifier.fromNamespaceAndPath("sodium", "textures/gui/sodkam_leaves.png");
    private static final int TEXTURE_SIZE = 16;
    private static final int PARTICLE_COUNT = 34;

    public record LeafSprite(int u, int v, int w, int h) {}

    private static final LeafSprite[] SPRITES = new LeafSprite[] {
            new LeafSprite(1, 0, 4, 3),   // small top left
            new LeafSprite(8, 1, 2, 3),   // tiny stem
            new LeafSprite(13, 1, 3, 3),  // small top right
            new LeafSprite(0, 4, 2, 3),   // left leaf
            new LeafSprite(4, 4, 4, 4),   // center blocky leaf
            new LeafSprite(1, 8, 4, 5),   // elongated leaf
            new LeafSprite(8, 8, 2, 3),   // tiny leaf
            new LeafSprite(13, 8, 3, 4),  // right leaf
            new LeafSprite(9, 10, 4, 4),  // center bottom leaf
            new LeafSprite(0, 12, 4, 3),  // bottom left leaf
            new LeafSprite(6, 12, 2, 3),  // bottom tiny leaf
            new LeafSprite(12, 13, 3, 3)  // bottom right leaf
    };

    private static class Particle {
        float x;
        float y;
        float baseSpeedY;
        float amplitude;
        float frequency;
        float phase;
        float driftX;
        float scale;
        float alpha;
        int spriteIndex;
        float mousePushX;
        float mousePushY;
    }

    private final List<Particle> particles = new ArrayList<>();
    private final Random random = new Random(42);
    private int screenWidth = 0;
    private int screenHeight = 0;
    private float lastMouseX = -1;
    private float lastMouseY = -1;
    private long lastTimeNanos = 0;

    public void init(int width, int height) {
        this.screenWidth = width;
        this.screenHeight = height;
        this.particles.clear();

        for (int i = 0; i < PARTICLE_COUNT; i++) {
            Particle p = new Particle();
            this.respawnParticle(p, true);
            this.particles.add(p);
        }
        this.lastTimeNanos = System.nanoTime();
    }

    private void respawnParticle(Particle p, boolean randomY) {
        p.x = this.random.nextFloat() * (this.screenWidth > 0 ? this.screenWidth : 400);
        p.y = randomY ? (this.random.nextFloat() * (this.screenHeight > 0 ? this.screenHeight : 300)) : -15.0f;
        p.baseSpeedY = 0.30f + this.random.nextFloat() * 0.40f;
        p.amplitude = 7.0f + this.random.nextFloat() * 15.0f;
        p.frequency = 0.012f + this.random.nextFloat() * 0.016f;
        p.phase = this.random.nextFloat() * ((float) Math.PI * 2.0f);
        p.driftX = -0.12f + this.random.nextFloat() * 0.28f;
        p.scale = 1.3f + this.random.nextFloat() * 1.1f;
        p.alpha = 0.30f + this.random.nextFloat() * 0.45f;
        p.spriteIndex = this.random.nextInt(SPRITES.length);
        p.mousePushX = 0;
        p.mousePushY = 0;
    }

    public void update(int mouseX, int mouseY) {
        if (this.screenWidth <= 0 || this.screenHeight <= 0) {
            return;
        }

        long now = System.nanoTime();
        float deltaTicks = 1.0f;
        if (this.lastTimeNanos > 0) {
            float elapsedSec = (now - this.lastTimeNanos) / 1_000_000_000.0f;
            deltaTicks = Math.min(elapsedSec * 60.0f, 3.0f);
        }
        this.lastTimeNanos = now;

        float mouseSpeedX = 0;
        float mouseSpeedY = 0;
        if (this.lastMouseX >= 0 && this.lastMouseY >= 0) {
            mouseSpeedX = (mouseX - this.lastMouseX);
            mouseSpeedY = (mouseY - this.lastMouseY);
        }
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;

        for (Particle p : this.particles) {
            // Реакция на движение курсора (отталкивание ветром)
            float dx = (p.x - mouseX);
            float dy = (p.y - mouseY);
            float distSq = dx * dx + dy * dy;
            if (distSq < 60.0f * 60.0f && distSq > 1.0f) {
                float dist = (float) Math.sqrt(distSq);
                float force = (1.0f - dist / 60.0f) * 0.6f;
                p.mousePushX += (dx / dist) * force + mouseSpeedX * 0.04f;
                p.mousePushY += (dy / dist) * force + mouseSpeedY * 0.04f;
            }

            p.mousePushX *= 0.93f;
            p.mousePushY *= 0.93f;

            p.y += (p.baseSpeedY + p.mousePushY) * deltaTicks;
            p.x += (p.driftX + p.mousePushX) * deltaTicks;

            if (p.y > this.screenHeight + 15 || p.x < -30 || p.x > this.screenWidth + 30) {
                this.respawnParticle(p, false);
            }
        }
    }

    public void render(GuiGraphicsExtractor graphics, float delta) {
        if (this.particles.isEmpty()) {
            return;
        }

        long timeMillis = System.currentTimeMillis();

        for (Particle p : this.particles) {
            LeafSprite sprite = SPRITES[p.spriteIndex];

            // Волнообразное покачивание листа по оси X
            float wave = Mth.sin(timeMillis * p.frequency * 0.05f + p.phase) * p.amplitude;
            float currentX = p.x + wave;
            float currentY = p.y;

            int drawW = Math.max(1, Math.round(sprite.w() * p.scale));
            int drawH = Math.max(1, Math.round(sprite.h() * p.scale));
            int drawX = Math.round(currentX);
            int drawY = Math.round(currentY);

            // Мягкая прозрачность
            int alphaInt = Mth.clamp((int) (p.alpha * 255.0f), 0, 255);
            int tint = (alphaInt << 24) | 0x00FFFFFF;

            graphics.blit(
                    RenderPipelines.GUI_TEXTURED,
                    LEAVES_TEXTURE,
                    drawX, drawY,
                    (float) sprite.u(), (float) sprite.v(),
                    drawW, drawH,
                    sprite.w(), sprite.h(),
                    TEXTURE_SIZE, TEXTURE_SIZE,
                    tint
            );
        }
    }
}
