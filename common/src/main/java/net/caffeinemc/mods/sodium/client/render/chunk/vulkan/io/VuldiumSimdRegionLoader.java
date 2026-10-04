package net.caffeinemc.mods.sodium.client.render.chunk.vulkan.io;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.zip.DataFormatException;

/**
 * Асинхронный SIMD-загрузчик регионов мира (Zero-Copy Native IO).
 *
 * Обертка высокого уровня над {@link VuldiumNativeRegionReader}, реализующая
 * аппаратную декомпрессию через libdeflate / FFM API без затрат памяти JVM Heap.
 */
public class VuldiumSimdRegionLoader implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vuldium/SimdRegionLoader");

    private final VuldiumNativeRegionReader nativeReader;
    private boolean isClosed = false;

    public VuldiumSimdRegionLoader() {
        this.nativeReader = new VuldiumNativeRegionReader();
        LOGGER.info("Vuldium Zero-Copy SIMD Region Loader инициализирован (FFM Native Region Reader).");
    }

    /**
     * Отображает .mca файл региона в виртуальное адресное пространство процесса (Zero-Copy).
     */
    public MemorySegment getOrMapRegion(Path mcaPath) throws IOException {
        return this.nativeReader.getOrMapRegion(mcaPath);
    }

    /**
     * Мгновенно извлекает сжатые данные чанка из заголовка секторов MCA и декомпрессирует их.
     */
    public int loadChunkData(Path mcaPath, int localX, int localZ, ByteBuffer outBuffer) throws IOException, DataFormatException {
        return this.nativeReader.decompressChunkToBuffer(mcaPath, localX, localZ, outBuffer);
    }

    public VuldiumNativeRegionReader getNativeReader() {
        return this.nativeReader;
    }

    public long getNativeRingBufferAddress() {
        return this.nativeReader.getPinnedRingBufferAddress();
    }

    @Override
    public synchronized void close() {
        if (this.isClosed) {
            return;
        }

        this.nativeReader.close();
        this.isClosed = true;
        LOGGER.info("Vuldium Zero-Copy SIMD Region Loader успешно закрыт.");
    }
}
