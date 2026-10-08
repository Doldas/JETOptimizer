package dev.jetoptimizer;

import java.util.List;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/** Ordered, bounded batches. Callers must submit detached data, never game objects or callbacks. */
final class RecipeCacheWorkers implements AutoCloseable {
    private final int workers;
    private final ThreadPoolExecutor executor;

    RecipeCacheWorkers(int workers) {
        if (workers < 2 || workers > 4) throw new IllegalArgumentException("Expected 2-4 workers");
        this.workers = workers;
        AtomicInteger threadIds = new AtomicInteger();
        executor = new ThreadPoolExecutor(workers, workers, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(workers), task -> {
                    Thread thread = new Thread(task, "jetoptimizer-recipe-worker-" + threadIds.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, (task, pool) -> {
                    if (pool.isShutdown()) throw new RejectedExecutionException("Recipe workers are closed");
                    task.run(); // Bounded backpressure instead of an unbounded task queue.
                });
        executor.allowCoreThreadTimeOut(true);
    }

    <I, O> List<O> mapOrdered(List<I> input, Function<I, O> operation) {
        if (executor.isShutdown()) throw new RejectedExecutionException("Recipe workers are closed");
        if (input.size() < 128) return input.stream().map(operation).toList();
        int chunk = (input.size() + workers - 1) / workers;
        List<CompletableFuture<List<O>>> tasks = IntStream.range(0, workers)
                .map(index -> index * chunk).filter(start -> start < input.size())
                .mapToObj(start -> CompletableFuture.supplyAsync(() ->
                        input.subList(start, Math.min(input.size(), start + chunk)).stream().map(operation).toList(), executor))
                .toList();
        // Encounter order is retained even if later chunks finish first. Wait for all submitted work
        // before propagating failure, so callers can safely fall back without outstanding tasks.
        CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
        return tasks.stream().flatMap(task -> task.join().stream()).toList();
    }

    @Override public void close() { executor.shutdown(); }
}
