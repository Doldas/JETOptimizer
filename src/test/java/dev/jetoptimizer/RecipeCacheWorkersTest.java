package dev.jetoptimizer;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class RecipeCacheWorkersTest {
    @Test void batchesPreserveOrderAndUseAtMostThreeWorkerThreads() {
        try (var workers = new RecipeCacheWorkers(3)) {
            var input = IntStream.range(0, 3000).boxed().toList();
            Set<String> threads = ConcurrentHashMap.newKeySet();
            AtomicInteger active = new AtomicInteger(), maximum = new AtomicInteger();
            var actual = workers.mapOrdered(input, value -> {
                threads.add(Thread.currentThread().getName());
                maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                try { return value * 2; } finally { active.decrementAndGet(); }
            });
            assertEquals(input.stream().map(value -> value * 2).toList(), actual);
            assertTrue(threads.stream().allMatch(name -> name.startsWith("jetoptimizer-recipe-worker")));
            assertTrue(maximum.get() <= 3);
        }
    }

    @Test void smallBatchesStayOnCallerAndEmptyBatchesAreSupported() {
        try (var workers = new RecipeCacheWorkers(3)) {
            String caller = Thread.currentThread().getName();
            assertEquals(List.of(caller, caller), workers.mapOrdered(List.of(1, 2), value -> Thread.currentThread().getName()));
            assertEquals(List.of(), workers.mapOrdered(List.of(), value -> value));
        }
    }

    @Test void workerFailureFinishesSubmittedChunksBeforeFallingBack() {
        try (var workers = new RecipeCacheWorkers(3)) {
            AtomicInteger finished = new AtomicInteger();
            assertThrows(CompletionException.class, () -> workers.mapOrdered(IntStream.range(0, 300).boxed().toList(), value -> {
                if (value == 0) throw new IllegalArgumentException("invalid detached recipe");
                finished.incrementAndGet(); return value;
            }));
            assertEquals(200, finished.get());
            assertEquals(List.of(2), workers.mapOrdered(List.of(1), value -> value + 1));
        }
    }

    @Test void invalidWorkerLimitsAndShutdownRejectWithoutHanging() {
        assertThrows(IllegalArgumentException.class, () -> new RecipeCacheWorkers(1));
        assertThrows(IllegalArgumentException.class, () -> new RecipeCacheWorkers(5));
        var workers = new RecipeCacheWorkers(3); workers.close();
        assertThrows(RejectedExecutionException.class, () -> workers.mapOrdered(IntStream.range(0, 300).boxed().toList(), value -> value));
    }
}
