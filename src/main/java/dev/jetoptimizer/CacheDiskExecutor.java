package dev.jetoptimizer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One disk lane shared by both caches, separate from bounded detached recipe preparation. */
final class CacheDiskExecutor {
    static final ExecutorService INSTANCE = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "jetoptimizer-cache-disk");
        thread.setDaemon(true);
        return thread;
    });
    private CacheDiskExecutor() {}
}
