package net.caffeinemc.mods.sodium.client.systems.worldgen;

import net.minecraft.SharedConstants;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Multithreaded Async WorldGen Dispatcher for Vuldium.
 * Offloads heavy world generation pipeline stages (BIOMES, TERRAIN/NOISE, FEATURES)
 * from the synchronous server thread to a dedicated high-priority worker pool.
 */
public final class SodkamAsyncChunkExecutor {
    private static final Logger LOGGER = LogManager.getLogger("Vuldium/AsyncWorldgen");

    private static final int PARALLELISM = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(1);

    private static final ForkJoinPool WORLDGEN_POOL = new ForkJoinPool(
            PARALLELISM,
            pool -> {
                ForkJoinWorkerThread worker = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
                worker.setName("Vuldium-WorldGen-Worker-" + THREAD_COUNTER.getAndIncrement());
                worker.setDaemon(true);
                worker.setPriority(Thread.NORM_PRIORITY - 1);
                return worker;
            },
            (thread, throwable) -> LOGGER.error("Uncaught exception in WorldGen worker: {}", thread.getName(), throwable),
            true // asyncMode: FIFO execution queue for optimal chunk scheduling
    );

    static {
        LOGGER.info("[Vuldium] Initialized Async WorldGen Engine with {} worker threads.", PARALLELISM);
    }

    private SodkamAsyncChunkExecutor() {}

    public static Executor getExecutor() {
        return WORLDGEN_POOL;
    }

    public static <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, WORLDGEN_POOL);
    }

    public static CompletableFuture<Void> runAsync(Runnable runnable) {
        return CompletableFuture.runAsync(runnable, WORLDGEN_POOL);
    }

    /**
     * Executes feature generation (applyBiomeDecoration + border ticks) asynchronously
     * on the dedicated worker pool, unblocking the server thread during exploration and world loading.
     */
    public static CompletableFuture<ChunkAccess> executeFeaturesAsync(
            WorldGenContext context,
            ChunkStep step,
            StaticCache2D<GenerationChunkHolder> cache,
            ChunkAccess chunk
    ) {
        return CompletableFuture.supplyAsync(() -> {
            WorldGenRegion worldGenRegion = new WorldGenRegion(context.level(), cache, step, chunk);
            if (!SharedConstants.DEBUG_DISABLE_FEATURES) {
                StructureManager structureManager = context.level().structureManager().forWorldGenRegion(worldGenRegion);
                context.generator().applyBiomeDecoration(worldGenRegion, chunk, structureManager);
            }
            Blender.generateBorderTicks(worldGenRegion, chunk);
            return chunk;
        }, WORLDGEN_POOL);
    }

    public static int getParallelism() {
        return PARALLELISM;
    }

    public static int getActiveThreadCount() {
        return WORLDGEN_POOL.getActiveThreadCount();
    }

    public static long getQueuedTaskCount() {
        return WORLDGEN_POOL.getQueuedTaskCount();
    }
}
