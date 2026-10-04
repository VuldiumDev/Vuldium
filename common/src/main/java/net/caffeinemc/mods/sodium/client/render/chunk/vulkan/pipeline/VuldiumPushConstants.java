package net.caffeinemc.mods.sodium.client.render.chunk.vulkan.pipeline;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;

/**
 * Структура Push Constants для шейдерного пайплайна Vuldium.
 * Размер: 128 байт (гарантированный минимум аппаратной поддержки Vulkan 1.0+).
 *
 * Раскладка памяти:
 * - 0..63:   Matrix4f u_ModelViewProjectionMatrix (16 x float = 64 байта)
 * - 64..75:  vec3 u_RegionOffset (3 x float = 12 байт)
 * - 76..79:  int u_CurrentTime (1 x int = 4 байта)
 * - 80..95:  vec4 u_FogColor (4 x float = 16 байт)
 * - 96..99:  float u_FogStart (1 x float = 4 байта)
 * - 100..103: float u_FogEnd (1 x float = 4 байта)
 * - 104..107: int u_FogShape (1 x int = 4 байта: 0 = сфера, 1 = цилиндр)
 * - 108..111: float u_TexCoordShrink (1 x float = 4 байта)
 * - 112..115: uint u_RegionID (1 x int = 4 байта)
 * - 116..127: Выравнивание / Резерв (12 байт)
 */
public final class VuldiumPushConstants {
    public static final int SIZE = 128;

    private static final int OFFSET_MVP = 0;
    private static final int OFFSET_REGION_OFFSET = 64;
    private static final int OFFSET_CURRENT_TIME = 76;
    private static final int OFFSET_FOG_COLOR = 80;
    private static final int OFFSET_FOG_START = 96;
    private static final int OFFSET_FOG_END = 100;
    private static final int OFFSET_FOG_SHAPE = 104;
    private static final int OFFSET_TEX_COORD_SHRINK = 108;
    private static final int OFFSET_REGION_ID = 112;

    private final ByteBuffer buffer;
    private final long address;

    public VuldiumPushConstants() {
        this.buffer = MemoryUtil.memAlloc(SIZE);
        this.address = MemoryUtil.memAddress(this.buffer);
        MemoryUtil.memSet(this.address, 0, SIZE);
    }

    public void setMvpMatrix(Matrix4f mvp) {
        mvp.get(0, this.buffer);
    }

    public void setRegionOffset(float x, float y, float z) {
        this.buffer.putFloat(OFFSET_REGION_OFFSET, x);
        this.buffer.putFloat(OFFSET_REGION_OFFSET + 4, y);
        this.buffer.putFloat(OFFSET_REGION_OFFSET + 8, z);
    }

    public void setRegionOffset(double x, double y, double z) {
        this.buffer.putFloat(OFFSET_REGION_OFFSET, (float) x);
        this.buffer.putFloat(OFFSET_REGION_OFFSET + 4, (float) y);
        this.buffer.putFloat(OFFSET_REGION_OFFSET + 8, (float) z);
    }

    public void setCurrentTime(int time) {
        this.buffer.putInt(OFFSET_CURRENT_TIME, time);
    }

    public void setFogColor(float r, float g, float b, float a) {
        this.buffer.putFloat(OFFSET_FOG_COLOR, r);
        this.buffer.putFloat(OFFSET_FOG_COLOR + 4, g);
        this.buffer.putFloat(OFFSET_FOG_COLOR + 8, b);
        this.buffer.putFloat(OFFSET_FOG_COLOR + 12, a);
    }

    public void setFogParameters(float start, float end, int shape) {
        this.buffer.putFloat(OFFSET_FOG_START, start);
        this.buffer.putFloat(OFFSET_FOG_END, end);
        this.buffer.putInt(OFFSET_FOG_SHAPE, shape);
    }

    public void setTexCoordShrink(float shrink) {
        this.buffer.putFloat(OFFSET_TEX_COORD_SHRINK, shrink);
    }

    public void setRegionId(int regionId) {
        this.buffer.putInt(OFFSET_REGION_ID, regionId);
    }

    /**
     * Записывает Push Constants в командный буфер.
     *
     * @param cmdBuf         активный VkCommandBuffer
     * @param pipelineLayout нативный дескриптор VkPipelineLayout
     */
    public void flush(VkCommandBuffer cmdBuf, long pipelineLayout) {
        VK10.vkCmdPushConstants(
                cmdBuf,
                pipelineLayout,
                VK10.VK_SHADER_STAGE_VERTEX_BIT | VK10.VK_SHADER_STAGE_FRAGMENT_BIT,
                0,
                this.buffer
        );
    }

    public void free() {
        MemoryUtil.memFree(this.buffer);
    }
}
