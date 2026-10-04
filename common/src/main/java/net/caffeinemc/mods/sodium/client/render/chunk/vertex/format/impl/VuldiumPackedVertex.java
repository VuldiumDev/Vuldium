package net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.impl;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics;
import net.caffeinemc.mods.sodium.api.util.ColorARGB;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;

/**
 * Экстремально сжатый 16-байтный (128-битный) формат вершины Vuldium (Вектор В).
 * Идеально укладывается в 64-байтные кэш-линии GPU (L1/L2 Cache): ровно 4 вершины (1 квад) на кэш-линию.
 *
 * Раскладка памяти (16 байт):
 * - 0..3:   Packed Position (32 бита, R32_UINT / 10-10-10-2):
 *           X: 10 бит [0..1023] (0.0 .. 32.0 блоков внутри секции с шагом 1/32)
 *           Y: 10 бит [0..1023]
 *           Z: 10 бит [0..1023]
 *           Vertex Index: 2 бита [0..3]
 * - 4..7:   Packed Color (32 бита, RGBA8_UNORM):
 *           R, G, B, A с учетом затенения Ambient Occlusion (AO).
 * - 8..11:  Packed TexCoord (32 бита, RG16_UINT):
 *           U: 16 бит, V: 16 бит (квантованные UV-координаты).
 * - 12..15: Packed Light & Data (32 бита, RGBA8_UINT):
 *           Byte 0: Block Light (4 бита) | Sky Light (4 бита)
 *           Byte 1: Material Bits (8 бит)
 *           Byte 2: Section Local ID (нижние 8 бит)
 *           Byte 3: Normal / Facing Enum (3 бита) + Section Local ID (верхние 5 бит)
 */
public class VuldiumPackedVertex implements ChunkVertexType {
    public static final int STRIDE = 16;

    public static final VertexFormat VERTEX_FORMAT = VertexFormat.builder(0)
            .addAttribute("a_Position", GpuFormat.R32_UINT)
            .addAttribute("a_Color", GpuFormat.RGBA8_UNORM)
            .addAttribute("a_TexCoord", GpuFormat.RG16_UINT)
            .addAttribute("a_LightAndData", GpuFormat.RGBA8_UINT)
            .build();

    private static final float POSITION_OFFSET = 8.0f;
    private static final float POSITION_SCALE = 1023.0f / 32.0f;

    @Override
    public VertexFormat getVertexFormat() {
        return VERTEX_FORMAT;
    }

    @Override
    public ChunkVertexEncoder getEncoder() {
        return (ptr, materialBits, vertices, section) -> {
            float texCentroidU = 0.0f;
            float texCentroidV = 0.0f;

            for (var vertex : vertices) {
                texCentroidU += vertex.u;
                texCentroidV += vertex.v;
            }

            texCentroidU *= 0.25f;
            texCentroidV *= 0.25f;

            for (int i = 0; i < 4; i++) {
                var vertex = vertices[i];

                int px = quantizePosition(vertex.x);
                int py = quantizePosition(vertex.y);
                int pz = quantizePosition(vertex.z);

                int packedPos = (px & 0x3FF) |
                        ((py & 0x3FF) << 10) |
                        ((pz & 0x3FF) << 20) |
                        ((i & 0x3) << 30);

                int color = ColorARGB.mulRGB(vertex.color, vertex.ao);

                int u = encodeTexture(texCentroidU, vertex.u);
                int v = encodeTexture(texCentroidV, vertex.v);
                int packedUV = (u & 0xFFFF) | ((v & 0xFFFF) << 16);

                int blockLight = (vertex.light & 0xFF) >> 4;
                int skyLight = ((vertex.light >> 16) & 0xFF) >> 4;
                int packedLight = (blockLight & 0xF) | ((skyLight & 0xF) << 4);

                int packedData = (packedLight & 0xFF) |
                        ((materialBits & 0xFF) << 8) |
                        ((section & 0xFFFF) << 16);

                MemoryIntrinsics.putInt(ptr + 0L, packedPos);
                MemoryIntrinsics.putInt(ptr + 4L, color);
                MemoryIntrinsics.putInt(ptr + 8L, packedUV);
                MemoryIntrinsics.putInt(ptr + 12L, packedData);

                ptr += STRIDE;
            }

            return ptr;
        };
    }

    private static int quantizePosition(float pos) {
        int v = (int) ((pos + POSITION_OFFSET) * POSITION_SCALE);
        return Math.clamp(v, 0, 1023);
    }

    private static int encodeTexture(float center, float x) {
        int quant = (int) (x * 32767.0f);
        int sign = (x < center) ? 1 : 0;
        return (quant & 0x7FFF) | (sign << 15);
    }
}
