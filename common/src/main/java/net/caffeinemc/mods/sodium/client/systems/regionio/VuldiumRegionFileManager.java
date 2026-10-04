package net.caffeinemc.mods.sodium.client.systems.regionio;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Memory-mapped region file I/O with an inflater pool for chunk decompression.
 */
public final class VuldiumRegionFileManager {
    private static final Logger LOGGER = LogManager.getLogger("Vuldium/RegionIO");

    private static final int SECTOR_BYTES = 4096;
    private static final int MAX_POOLED_INFLATERS = 64;

    private static final ConcurrentHashMap<Path, MappedRegionFile> MAPPED_REGIONS = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<Inflater> INFLATER_POOL = new ConcurrentLinkedQueue<>();
    private static final ConcurrentHashMap<String, String> NBT_STRING_CACHE = new ConcurrentHashMap<>(4096);

    @FunctionalInterface
    public interface ExternalStreamHandler {
        DataInputStream create(ChunkPos pos, byte version) throws IOException;
    }

    private VuldiumRegionFileManager() {}

    /**
     * Reads chunk data via memory-mapped zero-copy slice and pooled decompression.
     */
    public static DataInputStream readMappedChunkStream(
            Path path,
            FileChannel fileChannel,
            int sectorNumber,
            int numSectors,
            ChunkPos pos,
            ExternalStreamHandler externalHandler
    ) {
        try {
            MappedRegionFile mappedRegion = getOrMapRegion(path, fileChannel);
            if (mappedRegion == null) {
                return null;
            }

            int sectorOffset = sectorNumber * SECTOR_BYTES;
            int totalBytes = numSectors * SECTOR_BYTES;

            ByteBuffer chunkSlice = mappedRegion.getSlice(sectorOffset, totalBytes);
            if (chunkSlice == null || chunkSlice.remaining() < 5) {
                return null;
            }

            int chunkLength = chunkSlice.getInt();
            byte versionByte = chunkSlice.get();

            // Handle oversized / external chunk files (.mcc)
            if ((versionByte & 128) != 0) {
                byte externalVersion = (byte) (versionByte & ~128);
                return externalHandler.create(pos, externalVersion);
            }

            if (chunkLength <= 0 || chunkLength > chunkSlice.remaining() + 1) {
                LOGGER.warn("[Vuldium/RegionIO] Invalid chunk payload length {} at {} in {}", chunkLength, pos, path);
                return null;
            }

            int payloadLength = chunkLength - 1;
            ByteBuffer payloadSlice = chunkSlice.slice();
            payloadSlice.limit(Math.min(payloadLength, payloadSlice.capacity()));

            InputStream byteStream = new ByteBufferBackedInputStream(payloadSlice);
            RegionFileVersion regionFileVersion = RegionFileVersion.fromId(versionByte);

            if (regionFileVersion == null) {
                LOGGER.error("[Vuldium/RegionIO] Unknown chunk compression version {} at {}", versionByte, pos);
                return null;
            }

            InputStream decompressedStream;
            // VERSION_DEFLATE (id = 2) is the standard Minecraft chunk compression format
            if (regionFileVersion == RegionFileVersion.VERSION_DEFLATE) {
                Inflater inflater = acquireInflater();
                decompressedStream = new PooledInflaterInputStream(byteStream, inflater);
            } else {
                decompressedStream = regionFileVersion.wrap(byteStream);
            }

            return new DataInputStream(decompressedStream);
        } catch (Exception e) {
            LOGGER.debug("[Vuldium/RegionIO] Fallback to standard read for chunk {} in {}: {}", pos, path, e.getMessage());
            return null;
        }
    }

    private static MappedRegionFile getOrMapRegion(Path path, FileChannel channel) {
        return MAPPED_REGIONS.compute(path, (p, existing) -> {
            try {
                if (existing != null && existing.isValid(channel)) {
                    return existing;
                }
                long size = channel.size();
                if (size < SECTOR_BYTES * 2) {
                    return null;
                }
                MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, size);
                return new MappedRegionFile(channel, buffer, size);
            } catch (IOException e) {
                LOGGER.debug("[Vuldium/RegionIO] Failed to memory-map {}: {}", path, e.getMessage());
                return null;
            }
        });
    }

    public static void onRegionClosed(Path path) {
        MappedRegionFile removed = MAPPED_REGIONS.remove(path);
        if (removed != null) {
            removed.flush();
        }
    }

    public static void onRegionFlushed(Path path) {
        MappedRegionFile region = MAPPED_REGIONS.get(path);
        if (region != null) {
            region.flush();
        }
    }

    public static Inflater acquireInflater() {
        Inflater inflater = INFLATER_POOL.poll();
        if (inflater == null) {
            return new Inflater();
        }
        inflater.reset();
        return inflater;
    }

    public static void releaseInflater(Inflater inflater) {
        if (inflater != null) {
            if (INFLATER_POOL.size() < MAX_POOLED_INFLATERS) {
                inflater.reset();
                INFLATER_POOL.offer(inflater);
            } else {
                inflater.end();
            }
        }
    }

    /**
     * Fast concurrent NBT string interning for deduplicating tag keys.
     */
    public static String internString(String s) {
        if (s == null) return null;
        if (s.length() > 64) return s;
        return NBT_STRING_CACHE.computeIfAbsent(s, String::intern);
    }

    private static final class MappedRegionFile {
        private final FileChannel channel;
        private final MappedByteBuffer mappedBuffer;
        private final long size;

        public MappedRegionFile(FileChannel channel, MappedByteBuffer mappedBuffer, long size) {
            this.channel = channel;
            this.mappedBuffer = mappedBuffer;
            this.size = size;
        }

        public boolean isValid(FileChannel activeChannel) throws IOException {
            return this.channel == activeChannel && this.channel.isOpen() && this.channel.size() == this.size;
        }

        public ByteBuffer getSlice(int offset, int length) {
            if (offset < 0 || offset + length > this.size) {
                return null;
            }
            ByteBuffer duplicate = this.mappedBuffer.duplicate();
            duplicate.position(offset);
            duplicate.limit(offset + length);
            return duplicate.slice();
        }

        public void flush() {
            try {
                this.mappedBuffer.force();
            } catch (Exception ignored) {}
        }
    }

    private static final class ByteBufferBackedInputStream extends InputStream {
        private final ByteBuffer buffer;

        public ByteBufferBackedInputStream(ByteBuffer buffer) {
            this.buffer = buffer;
        }

        @Override
        public int read() {
            if (!this.buffer.hasRemaining()) {
                return -1;
            }
            return this.buffer.get() & 0xFF;
        }

        @Override
        public int read(byte[] bytes, int off, int len) {
            if (!this.buffer.hasRemaining()) {
                return -1;
            }
            int readLen = Math.min(len, this.buffer.remaining());
            this.buffer.get(bytes, off, readLen);
            return readLen;
        }

        @Override
        public int available() {
            return this.buffer.remaining();
        }
    }

    private static final class PooledInflaterInputStream extends InflaterInputStream {
        private final Inflater pooledInflater;
        private boolean isClosed = false;

        public PooledInflaterInputStream(InputStream in, Inflater inflater) {
            super(in, inflater);
            this.pooledInflater = inflater;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                if (!this.isClosed) {
                    this.isClosed = true;
                    releaseInflater(this.pooledInflater);
                }
            }
        }
    }
}
