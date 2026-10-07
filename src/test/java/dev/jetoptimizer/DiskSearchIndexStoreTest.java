package dev.jetoptimizer;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import mezz.jei.modshade.net.mezzdev.bakedsubstring.BakedSubstringIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

class DiskSearchIndexStoreTest {
    @TempDir Path directory;

    private static <T> BakedSubstringIndex<T> build(List<String> keys, List<T> values) {
        var builder = BakedSubstringIndex.<T>builder();
        for (int i = 0; i < keys.size(); i++) builder.put(keys.get(i), values.get(i));
        return builder.build();
    }

    private static DiskSearchIndexStore.Snapshot snapshot(String word) throws ReflectiveOperationException {
        return BakedSearchIndexBridge.capture(DiskSearchIndexStore.fingerprint(List.of(word)), build(List.of(word), List.of(new Object())));
    }

    @Test void roundTripRebindsOnlyCurrentRuntimeValues() throws Exception {
        List<String> keys = List.of("copper", "copper wire", "iron", "");
        Object old = new Object();
        var snapshot = BakedSearchIndexBridge.capture(DiskSearchIndexStore.fingerprint(keys), build(keys, List.of(old, old, old, old)));
        DiskSearchIndexStore store = new DiskSearchIndexStore();
        assertTrue(store.put(snapshot));
        Path file = directory.resolve("cache/index.bin");
        store.save(file);

        DiskSearchIndexStore freshProcess = DiskSearchIndexStore.load(file);
        Object first = new Object(), second = new Object();
        List<Object> current = List.of(first, second, second, first);
        var restored = BakedSearchIndexBridge.restore(freshProcess.get(DiskSearchIndexStore.fingerprint(keys)), keys, current);
        var original = build(keys, current);
        for (String query : List.of("", "c", "co", "copper", "wire", "iron", "absent")) {
            assertEquals(new ArrayList<>(original.getSearchResults(query)), new ArrayList<>(restored.getSearchResults(query)), query);
            for (Object result : restored.getSearchResults(query)) assertTrue(result == first || result == second);
        }
    }

    @Test void randomizedRestoredQueriesMatchNativeJei() throws Exception {
        Random random = new Random(235987);
        String alphabet = "aab c\u00e5\u00f6\u4e2d\ud83d\ude00";
        for (int trial = 0; trial < 100; trial++) {
            List<String> keys = new ArrayList<>();
            List<Object> oldValues = new ArrayList<>();
            List<Object> currentValues = new ArrayList<>();
            Object repeated = new Object();
            for (int i = 0; i < 80; i++) {
                StringBuilder word = new StringBuilder();
                for (int c = 0, length = random.nextInt(24); c < length; c++) word.append(alphabet.charAt(random.nextInt(alphabet.length())));
                keys.add(word.toString());
                oldValues.add(new Object());
                currentValues.add(random.nextBoolean() ? repeated : new Object());
            }
            DiskSearchIndexStore store = new DiskSearchIndexStore();
            var key = DiskSearchIndexStore.fingerprint(keys);
            store.put(BakedSearchIndexBridge.capture(key, build(keys, oldValues)));
            Path file = directory.resolve("random.bin");
            store.save(file);
            var restored = BakedSearchIndexBridge.restore(DiskSearchIndexStore.load(file).get(key), keys, currentValues);
            var nativeIndex = build(keys, currentValues);
            for (int query = 0; query < 200; query++) {
                String word = keys.get(random.nextInt(keys.size()));
                int start = random.nextInt(word.length() + 1);
                String substring = word.substring(start, start + random.nextInt(word.length() - start + 1));
                assertEquals(new ArrayList<>(nativeIndex.getSearchResults(substring)), new ArrayList<>(restored.getSearchResults(substring)));
            }
        }
    }

    @Test void identityDeduplicationIsRecomputedForEachRuntime() throws Exception {
        List<String> keys = List.of("abc", "abd");
        Object repeated = new Object();
        var cached = BakedSearchIndexBridge.capture(DiskSearchIndexStore.fingerprint(keys), build(keys, List.of(repeated, repeated)));
        List<Object> unique = List.of(new Object(), new Object());
        assertEquals(2, BakedSearchIndexBridge.restore(cached, keys, unique).getSearchResults("ab").size());
        var uniqueSnapshot = BakedSearchIndexBridge.capture(DiskSearchIndexStore.fingerprint(keys), build(keys, unique));
        assertEquals(1, BakedSearchIndexBridge.restore(uniqueSnapshot, keys, List.of(repeated, repeated)).getSearchResults("ab").size());
    }

    @Test void keysIncludeOrderingBoundariesAndExactUtf16() {
        assertNotEquals(DiskSearchIndexStore.fingerprint(List.of("a", "b")), DiskSearchIndexStore.fingerprint(List.of("b", "a")));
        assertNotEquals(DiskSearchIndexStore.fingerprint(List.of("ab", "c")), DiskSearchIndexStore.fingerprint(List.of("a", "bc")));
        assertNotEquals(DiskSearchIndexStore.fingerprint(List.of("\ud800")), DiskSearchIndexStore.fingerprint(List.of("\ufffd")));
        assertNotEquals(DiskSearchIndexStore.fingerprint(List.of()), DiskSearchIndexStore.fingerprint(List.of("")));
    }

    @Test void changedStringsMissEvenAtSameIngredientCount() throws Exception {
        DiskSearchIndexStore store = new DiskSearchIndexStore();
        store.put(snapshot("copper"));
        Path file = directory.resolve("changed.bin");
        store.save(file);
        var loaded = DiskSearchIndexStore.load(file);
        assertNotNull(loaded.get(DiskSearchIndexStore.fingerprint(List.of("copper"))));
        assertNull(loaded.get(DiskSearchIndexStore.fingerprint(List.of("silver"))));
    }

    @Test void boundedLruKeepsRecentlyUsedTables() throws Exception {
        DiskSearchIndexStore store = new DiskSearchIndexStore(2, 1024 * 1024);
        var first = snapshot("first");
        var second = snapshot("second");
        var third = snapshot("third");
        store.put(first); store.put(second); store.get(first.key()); store.put(third);
        assertNotNull(store.get(first.key()));
        assertNull(store.get(second.key()));
        assertNotNull(store.get(third.key()));
        DiskSearchIndexStore tiny = new DiskSearchIndexStore(2, 128);
        assertFalse(tiny.put(first));
        assertEquals(0, tiny.size());
    }

    @Test void missingFileAndEmptyIndexAreSupported() throws Exception {
        assertEquals(0, DiskSearchIndexStore.load(directory.resolve("missing.bin")).size());
        DiskSearchIndexStore store = new DiskSearchIndexStore();
        var key = DiskSearchIndexStore.fingerprint(List.of());
        store.put(BakedSearchIndexBridge.capture(key, BakedSubstringIndex.builder().build()));
        Path file = directory.resolve("empty.bin");
        store.save(file);
        assertTrue(BakedSearchIndexBridge.restore(DiskSearchIndexStore.load(file).get(key), List.of(), List.of()).getAllElements().isEmpty());
    }

    @Test void checksumTruncationAndFormatFailuresAreRejected() throws Exception {
        DiskSearchIndexStore store = new DiskSearchIndexStore();
        store.put(snapshot("copper"));
        Path file = directory.resolve("bad.bin");
        store.save(file);
        byte[] valid = Files.readAllBytes(file);
        byte[] changed = valid.clone(); changed[20] ^= 1;
        Files.write(file, changed);
        assertThrows(IOException.class, () -> DiskSearchIndexStore.load(file));
        Files.write(file, Arrays.copyOf(valid, valid.length - 3));
        assertThrows(IOException.class, () -> DiskSearchIndexStore.load(file));
        byte[] unsupported = valid.clone(); ByteBuffer.wrap(unsupported).putInt(4, 999);
        repairChecksum(unsupported); Files.write(file, unsupported);
        assertThrows(IOException.class, () -> DiskSearchIndexStore.load(file));
    }

    @Test void invalidPostingIndicesAreRejectedEvenWithValidChecksum() throws Exception {
        var grams = new Long2ObjectOpenHashMap<int[]>();
        grams.put(1L, new int[]{-1});
        DiskSearchIndexStore store = new DiskSearchIndexStore();
        store.put(new DiskSearchIndexStore.Snapshot(DiskSearchIndexStore.fingerprint(List.of("a")), grams));
        Path file = directory.resolve("bad-posting.bin"); store.save(file);
        assertThrows(IOException.class, () -> DiskSearchIndexStore.load(file));
    }

    @Test void savesReplaceAtomicallyAndRemoveTemporaryFiles() throws Exception {
        DiskSearchIndexStore store = new DiskSearchIndexStore();
        Path file = directory.resolve("replace.bin");
        store.put(snapshot("first")); store.save(file);
        store.put(snapshot("second")); store.save(file);
        assertEquals(2, DiskSearchIndexStore.load(file).size());
        try (var files = Files.list(directory)) { assertEquals(List.of(file), files.toList()); }
    }

    private static void repairChecksum(byte[] file) {
        CRC32 checksum = new CRC32(); checksum.update(file, 0, file.length - 8);
        ByteBuffer.wrap(file).putLong(file.length - 8, checksum.getValue());
    }
}
