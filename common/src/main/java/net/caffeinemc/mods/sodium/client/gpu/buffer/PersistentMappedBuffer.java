package net.caffeinemc.mods.sodium.client.gpu.buffer;

import java.nio.ByteBuffer;

/**
 * Cross-version abstraction for a persistently-mapped GPU buffer.
 * Wraps either GpuBuffer.MappedView (MC 26.1) or GpuBufferSlice.MappedView (MC 26.2+).
 */
public final class PersistentMappedBuffer implements AutoCloseable {
    private final AutoCloseable view;
    private final ByteBuffer data;

    public PersistentMappedBuffer(AutoCloseable view, ByteBuffer data) {
        this.view = view;
        this.data = data;
    }

    public ByteBuffer data() {
        return this.data;
    }

    @Override
    public void close() {
        try {
            this.view.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
