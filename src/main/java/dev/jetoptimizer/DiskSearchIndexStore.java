package dev.jetoptimizer;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.CRC32;
import java.util.zip.CheckedOutputStream;

/** Bounded persistence for deterministic gram postings only: no ingredient or world objects. */
final class DiskSearchIndexStore {
    static final int MAX_ENTRIES = 16;
    static final int MAX_KEYS = 2_000_000;
    static final int MAX_GRAMS = 262_144;
    static final long MAX_FILE_BYTES = 64L * 1024 * 1024;
    private static final int MAGIC = 0x4a455449;
    private static final int FORMAT = 1;
    private static final String ALGORITHM = "jei-19.57.0.449-grams-v1";
    private static final int HEADER_BUDGET = 128;

    record Key(int keyCount, String digest) {
        Key {
            if (keyCount < 0 || keyCount > MAX_KEYS || digest.length() != 64) {
                throw new IllegalArgumentException("Invalid search cache key");
            }
            HexFormat.of().parseHex(digest);
        }
    }

    static final class Snapshot {
        private final Key key;
        private final Long2ObjectOpenHashMap<int[]> grams;
        private final long bytes;

        Snapshot(Key key, Long2ObjectOpenHashMap<int[]> grams) {
            if (grams.size() > MAX_GRAMS) throw new IllegalArgumentException("Gram limit exceeded");
            this.key = key;
            // JEI's completed index never mutates its private gram table or posting arrays.
            // Retaining this table retains only longs and ints, never its keys or runtime values.
            this.grams = grams;
            long size = 40;
            for (int[] postings : grams.values()) size += 12L + 4L * postings.length;
            this.bytes = size;
        }

        Key key() { return key; }
        Long2ObjectOpenHashMap<int[]> grams() { return grams; }
        long bytes() { return bytes; }
    }

    private final LinkedHashMap<Key, Snapshot> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final int entryLimit;
    private final long byteLimit;
    private long bytes;

    DiskSearchIndexStore() { this(MAX_ENTRIES, MAX_FILE_BYTES); }

    DiskSearchIndexStore(int entryLimit, long byteLimit) {
        if (entryLimit < 1 || entryLimit > MAX_ENTRIES || byteLimit < HEADER_BUDGET || byteLimit > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("Invalid cache budget");
        }
        this.entryLimit = entryLimit;
        this.byteLimit = byteLimit;
    }

    synchronized Snapshot get(Key key) { return entries.get(key); }
    synchronized int size() { return entries.size(); }

    synchronized boolean put(Snapshot snapshot) {
        if (snapshot.bytes() + HEADER_BUDGET > byteLimit || entries.containsKey(snapshot.key())) return false;
        entries.put(snapshot.key(), snapshot);
        bytes += snapshot.bytes();
        var iterator = entries.entrySet().iterator();
        while (entries.size() > entryLimit || bytes + HEADER_BUDGET > byteLimit) {
            bytes -= iterator.next().getValue().bytes();
            iterator.remove();
        }
        return true;
    }

    static Key fingerprint(List<String> keys) {
        if (keys.size() > MAX_KEYS) throw new IllegalArgumentException("Key limit exceeded");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[4096];
            updateInt(digest, keys.size());
            for (String key : keys) {
                Objects.requireNonNull(key);
                updateInt(digest, key.length());
                int offset = 0;
                for (int i = 0; i < key.length(); i++) {
                    char character = key.charAt(i);
                    buffer[offset++] = (byte) (character >>> 8);
                    buffer[offset++] = (byte) character;
                    if (offset == buffer.length) {
                        digest.update(buffer);
                        offset = 0;
                    }
                }
                digest.update(buffer, 0, offset);
            }
            return new Key(keys.size(), HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    static DiskSearchIndexStore load(Path path) throws IOException {
        if (!Files.exists(path)) return new DiskSearchIndexStore();
        long length = Files.size(path);
        if (length < 24 || length > MAX_FILE_BYTES) throw new IOException("Invalid cache file size");
        byte[] file;
        try (InputStream input = Files.newInputStream(path)) {
            file = input.readNBytes((int) MAX_FILE_BYTES + 1);
        }
        if (file.length != length) throw new IOException("Cache file changed during read");
        CRC32 checksum = new CRC32();
        checksum.update(file, 0, file.length - Long.BYTES);
        try (DataInputStream footer = new DataInputStream(new ByteArrayInputStream(file, file.length - 8, 8))) {
            if (footer.readLong() != checksum.getValue()) throw new IOException("Cache checksum mismatch");
        }
        DiskSearchIndexStore store = new DiskSearchIndexStore();
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(file, 0, file.length - 8))) {
            if (input.readInt() != MAGIC || input.readInt() != FORMAT || !ALGORITHM.equals(input.readUTF())) {
                throw new IOException("Unsupported cache format or JEI algorithm");
            }
            int entryCount = bounded(input.readInt(), MAX_ENTRIES, "entry count");
            for (int entry = 0; entry < entryCount; entry++) {
                byte[] digest = new byte[32];
                input.readFully(digest);
                int keyCount = bounded(input.readInt(), MAX_KEYS, "key count");
                Key key = new Key(keyCount, HexFormat.of().formatHex(digest));
                int gramCount = bounded(input.readInt(), Math.min(MAX_GRAMS, input.available() / 12), "gram count");
                Long2ObjectOpenHashMap<int[]> grams = new Long2ObjectOpenHashMap<>(gramCount);
                for (int gram = 0; gram < gramCount; gram++) {
                    long encoded = input.readLong();
                    if (grams.containsKey(encoded)) throw new IOException("Duplicate gram");
                    int count = bounded(input.readInt(), Math.min(keyCount, input.available() / 4), "posting count");
                    if (count == 0) throw new IOException("Empty gram postings");
                    int[] postings = new int[count];
                    int previous = -1;
                    for (int index = 0; index < count; index++) {
                        int posting = input.readInt();
                        if (posting <= previous || posting >= keyCount) throw new IOException("Invalid posting index");
                        postings[index] = posting;
                        previous = posting;
                    }
                    grams.put(encoded, postings);
                }
                if (!store.put(new Snapshot(key, grams))) throw new IOException("Duplicate entry or cache budget exceeded");
            }
            if (input.available() != 0) throw new IOException("Trailing cache data");
        }
        return store;
    }

    private static int bounded(int value, int maximum, String label) throws IOException {
        if (value < 0 || value > maximum) throw new IOException("Invalid " + label);
        return value;
    }

    void save(Path target) throws IOException {
        List<Snapshot> snapshots;
        synchronized (this) { snapshots = new ArrayList<>(entries.values()); }
        Path parent = target.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "search-index-", ".tmp");
        try {
            CRC32 checksum = new CRC32();
            try (DataOutputStream output = new DataOutputStream(new CheckedOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(temporary)), checksum))) {
                output.writeInt(MAGIC);
                output.writeInt(FORMAT);
                output.writeUTF(ALGORITHM);
                output.writeInt(snapshots.size());
                for (Snapshot snapshot : snapshots) {
                    output.write(HexFormat.of().parseHex(snapshot.key().digest()));
                    output.writeInt(snapshot.key().keyCount());
                    output.writeInt(snapshot.grams().size());
                    for (var gram : snapshot.grams().long2ObjectEntrySet()) {
                        output.writeLong(gram.getLongKey());
                        int[] postings = gram.getValue();
                        output.writeInt(postings.length);
                        for (int index : postings) output.writeInt(index);
                    }
                }
                output.writeLong(checksum.getValue());
            }
            if (Files.size(temporary) > byteLimit) throw new IOException("Cache budget exceeded");
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
