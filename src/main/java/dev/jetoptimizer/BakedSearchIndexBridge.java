package dev.jetoptimizer;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import mezz.jei.modshade.net.mezzdev.bakedsubstring.BakedSubstringIndex;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.*;

/** Exact JEI-version adapter. Only primitive postings are shared; live values are always rebound. */
final class BakedSearchIndexBridge {
    private static final Constructor<?> CONSTRUCTOR;
    private static final Field GRAMS;

    static {
        try {
            CONSTRUCTOR = BakedSubstringIndex.class.getDeclaredConstructor(
                    String[].class, Object[].class, Long2ObjectOpenHashMap.class, boolean.class);
            CONSTRUCTOR.setAccessible(true);
            GRAMS = BakedSubstringIndex.class.getDeclaredField("entriesByGram");
            GRAMS.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @SuppressWarnings("unchecked")
    static DiskSearchIndexStore.Snapshot capture(DiskSearchIndexStore.Key key, BakedSubstringIndex<?> index)
            throws IllegalAccessException {
        return new DiskSearchIndexStore.Snapshot(key, (Long2ObjectOpenHashMap<int[]>) GRAMS.get(index));
    }

    @SuppressWarnings("unchecked")
    static <T> BakedSubstringIndex<T> restore(DiskSearchIndexStore.Snapshot snapshot, List<String> keys, List<T> values)
            throws ReflectiveOperationException {
        if (keys.size() != values.size() || keys.size() != snapshot.key().keyCount()) {
            throw new IllegalArgumentException("Search key/value size mismatch");
        }
        Set<T> unique = Collections.newSetFromMap(new IdentityHashMap<>());
        values.forEach(Objects::requireNonNull);
        unique.addAll(values);
        boolean duplicates = unique.size() != values.size();
        return (BakedSubstringIndex<T>) CONSTRUCTOR.newInstance(
                keys.toArray(String[]::new), values.toArray(), snapshot.grams(), duplicates);
    }
}
