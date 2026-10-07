package dev.jetoptimizer;

import mezz.jei.modshade.net.mezzdev.bakedsubstring.BakedSubstringIndex;
import net.neoforged.fml.ModList;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Preloads pure search tables at client setup; never blocks a join on disk I/O. */
public final class PersistentSearchIndexCache {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "jetoptimizer-disk-cache");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile CompletableFuture<DiskSearchIndexStore> preloaded;
    private static Path file;
    private static volatile Boolean cachedEnabled;
    private static final AtomicBoolean WRITE_SCHEDULED = new AtomicBoolean();
    private static final AtomicLong REVISION = new AtomicLong();
    private static int hits;
    private static int misses;
    private static int notReady;

    private PersistentSearchIndexCache() {}

    public static final class Lookup {
        private final DiskSearchIndexStore.Key key;
        private final BakedSubstringIndex<?> result;
        private Lookup(DiskSearchIndexStore.Key key, BakedSubstringIndex<?> result) {
            this.key = key;
            this.result = result;
        }
        public BakedSubstringIndex<?> result() { return result; }
    }

    public static synchronized void initialize(Path path) {
        if (preloaded != null || !enabled()) return;
        file = path;
        preloaded = CompletableFuture.supplyAsync(() -> {
            long started = System.nanoTime();
            DiskSearchIndexStore store;
            try {
                store = DiskSearchIndexStore.load(path);
            } catch (IOException | RuntimeException failure) {
                JETOptimizer.LOGGER.warn("Disk search cache ignored; JEI will rebuild it: {}", failure.getMessage());
                store = new DiskSearchIndexStore();
            }
            JETOptimizer.LOGGER.info("Disk search cache preloaded: {} tables in {} ms", store.size(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            return store;
        }, IO);
    }

    public static void beginRuntime() {
        cachedEnabled = null;
        hits = misses = notReady = 0;
    }

    public static void reportRuntime() {
        if (enabled()) {
            JETOptimizer.LOGGER.info("Disk search cache: {} hits, {} misses, {} preload-not-ready bypasses", hits, misses, notReady);
        }
    }

    private static boolean enabled() {
        Boolean cached = cachedEnabled;
        if (cached != null) return cached;
        try {
            return cachedEnabled = JETOptimizerConfig.ENABLED.get()
                    && JETOptimizerConfig.RECONNECT_CACHE.get()
                    && ModList.get().getModContainerById("jei")
                    .map(container -> "19.57.0.449".equals(container.getModInfo().getVersion().toString()))
                    .orElse(false);
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    private static DiskSearchIndexStore readyStore() {
        CompletableFuture<DiskSearchIndexStore> future = preloaded;
        return future == null || !future.isDone() || future.isCompletedExceptionally() ? null : future.getNow(null);
    }

    public static Lookup lookup(List<String> keys, List<?> values) {
        if (!enabled()) return null;
        DiskSearchIndexStore store = readyStore();
        if (store == null) {
            notReady++;
            return null;
        }
        try {
            DiskSearchIndexStore.Key key = DiskSearchIndexStore.fingerprint(keys);
            DiskSearchIndexStore.Snapshot snapshot = store.get(key);
            if (snapshot == null) {
                misses++;
                if (JETOptimizerConfig.DEBUG_CACHE_INVALIDATION.get()) {
                    JETOptimizer.LOGGER.info("Disk search cache miss: {} ordered keys have no matching table", keys.size());
                }
                return new Lookup(key, null);
            }
            BakedSubstringIndex<?> restored = BakedSearchIndexBridge.restore(snapshot, keys, values);
            hits++;
            if (JETOptimizerConfig.DEBUG_CACHE.get()) {
                JETOptimizer.LOGGER.info("Disk search cache hit: {} keys, {} grams", keys.size(), snapshot.grams().size());
            }
            return new Lookup(key, restored);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            JETOptimizerProfiler.recordOptimizationFallback("disk search cache restore");
            return null;
        }
    }

    public static void capture(Lookup lookup, BakedSubstringIndex<?> built) {
        if (lookup == null || lookup.result != null || built == null || !enabled()) return;
        DiskSearchIndexStore store = readyStore();
        if (store == null) return;
        try {
            DiskSearchIndexStore.Snapshot snapshot = BakedSearchIndexBridge.capture(lookup.key, built);
            if (store.put(snapshot)) {
                REVISION.incrementAndGet();
                scheduleWrite(store);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            JETOptimizerProfiler.recordOptimizationFallback("disk search cache capture");
        }
    }

    private static void scheduleWrite(DiskSearchIndexStore store) {
        if (!WRITE_SCHEDULED.compareAndSet(false, true)) return;
        IO.execute(() -> {
            long revision = REVISION.get();
            try {
                store.save(file);
            } catch (IOException | RuntimeException failure) {
                JETOptimizer.LOGGER.warn("Could not save disk search cache; memory reuse remains available: {}", failure.getMessage());
            } finally {
                WRITE_SCHEDULED.set(false);
                // Coalesce changes that arrived during a write, without queuing one task per prefix.
                if (REVISION.get() != revision) scheduleWrite(store);
            }
        });
    }
}
