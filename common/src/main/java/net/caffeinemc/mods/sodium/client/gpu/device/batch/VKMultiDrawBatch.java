package net.caffeinemc.mods.sodium.client.gpu.device.batch;

import net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics;
import net.caffeinemc.mods.sodium.client.gpu.device.context.DrawContext;
import net.caffeinemc.mods.sodium.client.util.UInt32;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkMultiDrawIndexedInfoEXT;

public final class VKMultiDrawBatch extends MultiDrawBatch {
    private static final int COMMAND_INT_STRIDE = (VkMultiDrawIndexedInfoEXT.SIZEOF) / 4;
    private final long pCommands;

    public VKMultiDrawBatch(int capacity) {
        this.pCommands = MemoryUtil.nmemAlignedAlloc(32, (long) VkMultiDrawIndexedInfoEXT.SIZEOF * capacity);
        MemoryUtil.memSet(this.pCommands, 0x0, (long) VkMultiDrawIndexedInfoEXT.SIZEOF * capacity);
    }

    @Override
    public void put(int size, int elementCount, int baseVertex, long elementOffset) {
        MemoryIntrinsics.putInt(this.pCommands + ((long) size * VkMultiDrawIndexedInfoEXT.SIZEOF) + VkMultiDrawIndexedInfoEXT.INDEXCOUNT, elementCount);
        MemoryIntrinsics.putInt(this.pCommands + ((long) size * VkMultiDrawIndexedInfoEXT.SIZEOF) + VkMultiDrawIndexedInfoEXT.VERTEXOFFSET, UInt32.uncheckedDowncast(baseVertex));
        MemoryIntrinsics.putInt(this.pCommands + ((long) size * VkMultiDrawIndexedInfoEXT.SIZEOF) + VkMultiDrawIndexedInfoEXT.FIRSTINDEX, UInt32.uncheckedDowncast(elementOffset));

        this.updateMaxElementCount(elementCount);
    }

    @Override
    public void draw(DrawContext context) {
        if (this.size <= 0) {
            return;
        }
        for (int i = 0; i < this.size; i++) {
            long cmd = this.pCommands + ((long) i * VkMultiDrawIndexedInfoEXT.SIZEOF);
            int indexCount = MemoryIntrinsics.getInt(cmd + VkMultiDrawIndexedInfoEXT.INDEXCOUNT);
            int vertexOffset = MemoryIntrinsics.getInt(cmd + VkMultiDrawIndexedInfoEXT.VERTEXOFFSET);
            int firstIndex = MemoryIntrinsics.getInt(cmd + VkMultiDrawIndexedInfoEXT.FIRSTINDEX);
            context.getPass().drawIndexed(indexCount, 1, firstIndex, vertexOffset);
        }
    }

    @Override
    public void delete() {
        MemoryUtil.nmemAlignedFree(this.pCommands);
    }
}
