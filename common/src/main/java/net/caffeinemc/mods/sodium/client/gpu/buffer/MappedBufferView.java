package net.caffeinemc.mods.sodium.client.gpu.buffer;

import java.nio.ByteBuffer;

/**
 * A cross-version abstraction over the mapped view of a GPU buffer.
 *
 * In MC 26.1, the actual type is GpuBuffer.MappedView (an interface).
 * In MC 26.2+, the actual type is GpuBufferSlice.MappedView (a record).
 * Both support data() and close(), so code should be written against this interface.
 */
public interface MappedBufferView extends AutoCloseable {
    ByteBuffer data();

    @Override
    void close();

    /**
     * Wraps any AutoCloseable + data()-having object into a MappedBufferView.
     * Works with both GpuBuffer.MappedView (26.1) and GpuBufferSlice.MappedView (26.2+).
     */
    static MappedBufferView wrap(Object nativeView) {
        // Both types provide data() via duck typing — use reflection is ugly.
        // Instead, code should call mapBuffer and assign with 'var', then
        // pass to helper methods. This interface is used for FIELD declarations only.
        throw new UnsupportedOperationException("Use var for local variables; cast for fields");
    }
}
