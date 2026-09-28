package net.caffeinemc.mods.sodium.client.gpu.arena.staging;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;

import java.nio.ByteBuffer;

public class MojangStagingBuffer implements StagingBuffer {
    private final MappedStagingBuffer staging;

    public MojangStagingBuffer(int size) {
        this.staging = null;
    }

    @Override
    public void enqueueCopy(ByteBuffer data, GpuBuffer dst, long writeOffset) {
        if (this.staging == null) {
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(dst.slice(writeOffset, data.remaining()), data);
        } else {
            this.staging.enqueueCopy(data, dst, writeOffset);
        }
    }

    @Override
    public void flush() {
        if (this.staging != null) this.staging.flush();
    }

    @Override
    public void delete() {
        if (this.staging != null) this.staging.delete();
    }

    @Override
    public void flip() {
        if (this.staging != null) this.staging.flip();
    }

    @Override
    public long getUploadSizeLimit(long frameDuration) {
        return this.staging == null ? Long.MAX_VALUE : this.staging.getUploadSizeLimit(frameDuration);
    }
}
