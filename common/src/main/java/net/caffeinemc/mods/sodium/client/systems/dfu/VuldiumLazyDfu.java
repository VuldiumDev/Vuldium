package net.caffeinemc.mods.sodium.client.systems.dfu;

import com.mojang.datafixers.DSL;
import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.serialization.Dynamic;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lazy DFU compiler for Vuldium.
 * Defers DFU schema compilation to JIT on demand.
 */
public final class VuldiumLazyDfu {
    private static final Logger LOGGER = LogManager.getLogger("Vuldium/LazyDFU");

    private static final AtomicLong OPTIMIZATIONS_PREVENTED = new AtomicLong(0);
    private static final AtomicLong FAST_PATH_UPDATES = new AtomicLong(0);
    private static final AtomicLong JIT_COMPILED_UPDATES = new AtomicLong(0);

    private VuldiumLazyDfu() {}

    /**
     * Intercepts and suppresses eager startup compilation of level schemas.
     */
    public static CompletableFuture<?> onOptimize(Set<DSL.TypeReference> types) {
        long count = OPTIMIZATIONS_PREVENTED.incrementAndGet();
        if (count == 1) LOGGER.info("[Vuldium/LazyDFU] Deferring eager DFU schema compilation to JIT.");
        LOGGER.debug("[Vuldium/LazyDFU] Suppressed eager DFU schema compilation #{} for {} types.", count, types != null ? types.size() : 0);
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Wraps a DataFixer with a lazy JIT-compiling proxy.
     */
    public static DataFixer wrap(DataFixer fixer) {
        if (fixer == null || fixer instanceof LazyDataFixer) {
            return fixer;
        }
        // Attached JIT proxy
        return new LazyDataFixer(fixer);
    }

    public static long getOptimizationsPrevented() {
        return OPTIMIZATIONS_PREVENTED.get();
    }

    public static long getFastPathUpdates() {
        return FAST_PATH_UPDATES.get();
    }

    public static long getJitCompiledUpdates() {
        return JIT_COMPILED_UPDATES.get();
    }

    public static final class LazyDataFixer implements DataFixer {
        private final DataFixer delegate;

        public LazyDataFixer(DataFixer delegate) {
            this.delegate = Objects.requireNonNull(delegate, "Delegate DataFixer cannot be null");
        }

        @Override
        public <T> Dynamic<T> update(DSL.TypeReference type, Dynamic<T> input, int versionFrom, int versionTo) {
            // Fast-path: When data is already at current or newer version (normal gameplay & freshly generated chunks)
            if (versionFrom >= versionTo) {
                FAST_PATH_UPDATES.incrementAndGet();
                return input;
            }

            // Legacy world migration: JIT compile and evaluate rules on demand
            long jitCount = JIT_COMPILED_UPDATES.incrementAndGet();
            if (jitCount <= 5 || jitCount % 100 == 0) {
                LOGGER.info("[Vuldium/LazyDFU] JIT migration requested for type '{}' (v{} -> v{}). Total migrations: {}",
                        type.typeName(), versionFrom, versionTo, jitCount);
            }
            return this.delegate.update(type, input, versionFrom, versionTo);
        }

        @Override
        public Schema getSchema(int key) {
            return this.delegate.getSchema(key);
        }

        public DataFixer getDelegate() {
            return this.delegate;
        }
    }
}
