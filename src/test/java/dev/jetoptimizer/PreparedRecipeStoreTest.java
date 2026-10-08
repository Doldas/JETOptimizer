package dev.jetoptimizer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.IntStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PreparedRecipeStoreTest {
    @TempDir Path directory;

    private static PreparedRecipeStore.Entry entry(String mod, String id, String payload, String modHash, List<String> fuel) {
        var roles = Map.of(0, List.of("{\"id\":\"minecraft:iron_ingot\",\"count\":1}"),
                1, List.of("{\"id\":\"minecraft:iron_block\",\"count\":1}"), 3, fuel);
        return new PreparedRecipeStore.Entry(new PreparedRecipeStore.Id("minecraft:smelting", mod + ":" + id),
                RecipeCacheHash.strings(List.of(payload, modHash, fuel.toString())), payload,
                Map.of("jei", "pinned", mod, modHash), roles);
    }
    private static PreparedRecipeStore.Entry entry(String id) { return entry("example", id, "{\"type\":\"minecraft:smelting\"}", "jar-v1", List.of("coal", "charcoal")); }

    @Test void freshLoadPreservesPayloadsRolesOrderingAndSharedBlocks() throws Exception {
        PreparedRecipeStore store = new PreparedRecipeStore();
        var first = entry("one"); var second = entry("two");
        assertEquals(2, store.putAll(List.of(first, second)));
        Path file = directory.resolve("cache/recipes.gz"); store.save(file);
        var loaded = PreparedRecipeStore.load(file);
        assertEquals(first, loaded.get(first.id(), first.fingerprint()));
        assertEquals(second, loaded.get(second.id(), second.fingerprint()));
        assertSame(loaded.get(first.id(), first.fingerprint()).roles().get(3), loaded.get(second.id(), second.fingerprint()).roles().get(3));
        assertThrows(UnsupportedOperationException.class, () -> loaded.get(first.id(), first.fingerprint()).roles().get(3).clear());
    }

    @Test void changedServerPayloadMissesAndReplacesOnlyThatRecipe() throws Exception {
        var first = entry("one"); var second = entry("two");
        var changed = entry("example", "one", "{\"result\":\"gold_block\"}", "jar-v1", List.of("coal", "charcoal"));
        PreparedRecipeStore store = new PreparedRecipeStore(); store.putAll(List.of(first, second));
        assertNull(store.get(first.id(), changed.fingerprint()));
        assertEquals(1, store.putAll(List.of(changed)));
        assertNull(store.get(first.id(), first.fingerprint()));
        assertEquals(changed, store.get(changed.id(), changed.fingerprint()));
        assertEquals(second, store.get(second.id(), second.fingerprint()));
    }

    @Test void modUpdatesInvalidateItsDependentsAndKeepUnrelatedEntries() {
        var first = entry("first", "a", "first", "hash-a", List.of("coal"));
        var second = entry("second", "b", "second", "hash-b", List.of("coal"));
        PreparedRecipeStore store = new PreparedRecipeStore(); store.putAll(List.of(first, second));
        assertEquals(1, store.invalidate(Map.of("jei", "pinned", "first", "updated", "second", "hash-b")));
        assertNull(store.get(first.id(), first.fingerprint()));
        assertEquals(second, store.get(second.id(), second.fingerprint()));
        assertEquals(1, store.invalidate(Map.of("jei", "new-version", "second", "hash-b")));
    }

    @Test void missingDependencyAlsoInvalidates() {
        PreparedRecipeStore store = new PreparedRecipeStore(); var first = entry("one"); store.putAll(List.of(first));
        assertEquals(1, store.invalidate(Map.of("jei", "pinned")));
    }

    @Test void changedTagsOrFuelsHaveDifferentKeys() {
        assertNotEquals(entry("one").fingerprint(), entry("example", "one", "{\"type\":\"minecraft:smelting\"}", "jar-v1", List.of("new_fuel")).fingerprint());
        assertNotEquals(RecipeCacheHash.strings(List.of("#tag", "iron", "copper")), RecipeCacheHash.strings(List.of("#tag", "iron", "tin")));
    }

    @Test void lookupReturnsOnlyCurrentAuthoritativeListAndMatchingFingerprints() {
        PreparedRecipeStore store = new PreparedRecipeStore(); var first = entry("one"); var second = entry("two"); store.putAll(List.of(first, second));
        assertEquals(Map.of(first.id(), first), store.lookupAll(Map.of(first.id(), first.fingerprint())));
        assertTrue(store.lookupAll(Map.of(first.id(), RecipeCacheHash.strings(List.of("changed")))).isEmpty());
        assertTrue(store.lookupAll(Map.of()).isEmpty()); // Deleted recipes are never injected from stored data.
    }

    @Test void lruEvictionReleasesSharedBlocksWithoutLosingOtherReferences() {
        PreparedRecipeStore store = new PreparedRecipeStore(2, 100_000);
        var first = entry("one"); var second = entry("two"); var third = entry("three");
        store.putAll(List.of(first, second)); store.get(first.id(), first.fingerprint()); store.putAll(List.of(third));
        assertNull(store.get(second.id(), second.fingerprint()));
        assertEquals(first, store.get(first.id(), first.fingerprint()));
        assertEquals(third, store.get(third.id(), third.fingerprint()));
        assertEquals(2, store.invalidate(Map.of())); assertEquals(128, store.retainedBytes());
    }

    @Test void oversizedReplacementKeepsPreviouslyValidEntry() {
        var first = entry("one");
        PreparedRecipeStore store = new PreparedRecipeStore(2, 2000); store.putAll(List.of(first));
        var oversized = entry("example", "one", "x".repeat(2500), "jar-v1", List.of("coal"));
        assertEquals(0, store.putAll(List.of(oversized)));
        assertEquals(first, store.get(first.id(), first.fingerprint()));
    }

    @Test void identicalAddsDoNotCreateAnotherDiskRevision() {
        PreparedRecipeStore store = new PreparedRecipeStore(); var first = entry("one");
        assertEquals(1, store.putAll(List.of(first))); long bytes = store.retainedBytes();
        assertEquals(0, store.putAll(List.of(first))); assertEquals(bytes, store.retainedBytes());
    }

    @Test void dictionaryAvoidsRepeatingLargeFuelListsForEveryRecipe() throws Exception {
        List<String> fuels = IntStream.range(0, 1000).mapToObj(index -> "{\"id\":\"mod:fuel_" + index + "\",\"count\":1}").toList();
        PreparedRecipeStore store = new PreparedRecipeStore();
        var recipes = IntStream.range(0, 500).mapToObj(index -> entry("example", "recipe" + index, "{}", "jar-v1", fuels)).toList();
        store.putAll(recipes);
        assertTrue(store.retainedBytes() < 300_000, "Fuel blocks should be retained once");
        Path file = directory.resolve("bulk.gz"); store.save(file);
        assertEquals(500, PreparedRecipeStore.load(file).size());
        assertTrue(Files.size(file) < 100_000);
    }

    @Test void gzipChecksumAndTruncationAreRejected() throws Exception {
        PreparedRecipeStore store = new PreparedRecipeStore(); store.putAll(List.of(entry("one")));
        Path file = directory.resolve("bad.gz"); store.save(file); byte[] valid = Files.readAllBytes(file);
        byte[] corrupt = valid.clone(); corrupt[corrupt.length - 8] ^= 1; Files.write(file, corrupt);
        assertThrows(IOException.class, () -> PreparedRecipeStore.load(file));
        Files.write(file, Arrays.copyOf(valid, valid.length - 5));
        assertThrows(IOException.class, () -> PreparedRecipeStore.load(file));
    }

    @Test void validChecksumDoesNotAllowUnsupportedFormatOrInvalidDictionaryReference() throws Exception {
        PreparedRecipeStore store = new PreparedRecipeStore(); store.putAll(List.of(entry("one")));
        Path file = directory.resolve("format.gz"); store.save(file);
        byte[] raw;
        try (var input = new GZIPInputStream(Files.newInputStream(file))) { raw = input.readAllBytes(); }
        byte[] changed = raw.clone(); java.nio.ByteBuffer.wrap(changed).putInt(4, 999); writeGzip(file, changed);
        assertThrows(IOException.class, () -> PreparedRecipeStore.load(file));
        changed = raw.clone(); java.nio.ByteBuffer.wrap(changed).putInt(changed.length - 4, Integer.MAX_VALUE); writeGzip(file, changed);
        assertThrows(IOException.class, () -> PreparedRecipeStore.load(file));
    }

    @Test void malformedCountsAreRejectedBeforeAllocatingClaimedCollections() throws Exception {
        Path file = directory.resolve("count.gz");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            output.writeInt(0x4a455452); output.writeInt(1);
            byte[] adapter = "jei-19.57.0.449-native-supplier-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            output.writeInt(adapter.length); output.write(adapter); output.writeInt(Integer.MAX_VALUE);
        }
        writeGzip(file, bytes.toByteArray());
        assertThrows(IOException.class, () -> PreparedRecipeStore.load(file));
    }

    @Test void savesReplaceFilesAndRemoveTemporaryFiles() throws Exception {
        Path file = directory.resolve("recipes.gz"); PreparedRecipeStore store = new PreparedRecipeStore();
        store.save(file); store.putAll(List.of(entry("one"))); store.save(file);
        assertEquals(1, PreparedRecipeStore.load(file).size());
        try (var files = Files.list(directory)) { assertEquals(List.of(file), files.toList()); }
        assertEquals(0, PreparedRecipeStore.load(directory.resolve("missing.gz")).size());
    }

    @Test void fingerprintsIncludeBoundariesOrderingAndExactUnicode() {
        assertNotEquals(RecipeCacheHash.strings(List.of("ab", "c")), RecipeCacheHash.strings(List.of("a", "bc")));
        assertNotEquals(RecipeCacheHash.strings(List.of("a", "b")), RecipeCacheHash.strings(List.of("b", "a")));
        assertNotEquals(RecipeCacheHash.strings(List.of("\ud800")), RecipeCacheHash.strings(List.of("\ufffd")));
    }

    @Test void actualFileHashDetectsChangesEvenWhenVersionIsUnchanged() throws Exception {
        Path file = directory.resolve("mod.jar"); Files.writeString(file, "version 1: first build"); String first = RecipeCacheHash.file(file);
        Files.writeString(file, "version 1: second build"); assertNotEquals(first, RecipeCacheHash.file(file));
    }

    private static void writeGzip(Path path, byte[] data) throws IOException {
        try (var output = new GZIPOutputStream(Files.newOutputStream(path))) { output.write(data); }
    }
}
