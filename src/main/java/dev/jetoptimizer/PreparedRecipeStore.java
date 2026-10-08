package dev.jetoptimizer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.IntStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Detached recipe payloads and ingredient blocks; no live registry or JEI references. */
final class PreparedRecipeStore {
    static final int MAX_ENTRIES = 250_000;
    static final int MAX_BLOCK_ITEMS = 65_536;
    static final int MAX_TEXT_BYTES = 2 * 1024 * 1024;
    static final long MAX_BYTES = 128L * 1024 * 1024;
    private static final int MAGIC = 0x4a455452;
    private static final int FORMAT = 1;
    private static final String ADAPTER = "jei-19.57.0.449-native-supplier-v1";

    record Id(String category, String recipe) {
        Id { Objects.requireNonNull(category); Objects.requireNonNull(recipe); }
    }

    record Entry(Id id, String fingerprint, String recipeJson, Map<String, String> dependencies,
                 Map<Integer, List<String>> roles) {
        Entry {
            if (!fingerprint.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid fingerprint");
            Objects.requireNonNull(id); Objects.requireNonNull(recipeJson);
            dependencies = Map.copyOf(dependencies);
            Map<Integer, List<String>> copied = new TreeMap<>();
            roles.forEach((role, values) -> {
                if (role < 0 || role >= 4 || values.size() > MAX_BLOCK_ITEMS) throw new IllegalArgumentException("Invalid role block");
                copied.put(role, List.copyOf(values));
            });
            roles = Collections.unmodifiableMap(copied);
        }
        boolean matches(Map<String, String> manifest) {
            return dependencies.entrySet().stream().allMatch(entry -> Objects.equals(manifest.get(entry.getKey()), entry.getValue()));
        }
    }

    private static final class Block {
        final List<String> values;
        final long bytes;
        int references;
        Block(List<String> values) {
            this.values = values;
            bytes = 4 + values.stream().mapToLong(PreparedRecipeStore::textBytes).sum();
        }
    }
    private final LinkedHashMap<Id, Entry> entries = new LinkedHashMap<>(16, .75f, true);
    private final Map<List<String>, Block> blocks = new HashMap<>();
    private final int entryLimit;
    private final long byteLimit;
    private long bytes = 128;
    private long blockValues;

    PreparedRecipeStore() { this(MAX_ENTRIES, MAX_BYTES); }
    PreparedRecipeStore(int entryLimit, long byteLimit) {
        if (entryLimit < 1 || entryLimit > MAX_ENTRIES || byteLimit < 128 || byteLimit > MAX_BYTES) throw new IllegalArgumentException("Invalid budget");
        this.entryLimit = entryLimit;
        this.byteLimit = byteLimit;
    }

    synchronized Entry get(Id id, String fingerprint) {
        Entry entry = entries.get(id);
        return entry != null && entry.fingerprint().equals(fingerprint) ? entry : null;
    }
    synchronized Map<Id, Entry> lookupAll(Map<Id, String> fingerprints) {
        Map<Id, Entry> matches = new HashMap<>();
        fingerprints.forEach((id, fingerprint) -> {
            Entry entry = get(id, fingerprint);
            if (entry != null) matches.put(id, entry);
        });
        return matches;
    }
    synchronized int size() { return entries.size(); }
    synchronized long retainedBytes() { return bytes; }

    synchronized int putAll(Collection<Entry> additions) {
        int[] changed = {0};
        additions.forEach(entry -> { if (put(entry)) changed[0]++; });
        return changed[0];
    }

    private boolean put(Entry entry) {
        Entry previous = entries.get(entry.id());
        if (entry.equals(previous)) return false;
        if (entryBytes(entry) + 128 > byteLimit || !validText(entry)) return false;
        // Reject a single oversized snapshot before modifying an existing valid entry.
        long standalone = entryBytes(entry) + 128 + entry.roles().values().stream().distinct()
                .mapToLong(values -> {
                    Block existing = blocks.get(values);
                    return existing == null ? new Block(values).bytes : existing.bytes;
                }).sum();
        if (standalone > byteLimit) return false;
        if (previous != null) remove(previous);
        Map<Integer, List<String>> canonical = new TreeMap<>();
        entry.roles().forEach((role, values) -> {
            Block block = blocks.computeIfAbsent(values, Block::new);
            if (block.references++ == 0) { bytes += block.bytes; blockValues += block.values.size(); }
            canonical.put(role, block.values);
        });
        Entry retained = new Entry(entry.id(), entry.fingerprint(), entry.recipeJson(), entry.dependencies(), canonical);
        entries.put(retained.id(), retained);
        bytes += entryBytes(retained);
        Iterator<Entry> oldest = entries.values().iterator();
        while (entries.size() > entryLimit || bytes > byteLimit || blockValues > 2_000_000) {
            Entry evicted = oldest.next(); oldest.remove(); release(evicted);
        }
        return true;
    }

    synchronized int invalidate(Map<String, String> manifest) {
        int before = entries.size();
        Iterator<Entry> iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (!entry.matches(manifest)) { iterator.remove(); release(entry); }
        }
        return before - entries.size();
    }

    private void remove(Entry entry) { entries.remove(entry.id()); release(entry); }
    private void release(Entry entry) {
        bytes -= entryBytes(entry);
        entry.roles().values().forEach(values -> {
            Block block = blocks.get(values);
            if (--block.references == 0) { bytes -= block.bytes; blockValues -= block.values.size(); blocks.remove(values); }
        });
    }
    private static long entryBytes(Entry entry) {
        return 80 + textBytes(entry.id().category()) + textBytes(entry.id().recipe()) + textBytes(entry.recipeJson())
                + 8L * entry.roles().size() + entry.dependencies().entrySet().stream()
                .mapToLong(dependency -> textBytes(dependency.getKey()) + textBytes(dependency.getValue())).sum();
    }
    private static long textBytes(String text) { return 4L + text.getBytes(StandardCharsets.UTF_8).length; }
    private static boolean validText(Entry entry) {
        return java.util.stream.Stream.concat(
                java.util.stream.Stream.of(entry.id().category(), entry.id().recipe(), entry.recipeJson()),
                java.util.stream.Stream.concat(entry.dependencies().entrySet().stream().flatMap(e -> java.util.stream.Stream.of(e.getKey(), e.getValue())),
                        entry.roles().values().stream().flatMap(List::stream)))
                .allMatch(value -> textBytes(value) - 4 <= MAX_TEXT_BYTES);
    }

    void save(Path file) throws IOException {
        List<Entry> snapshot;
        synchronized (this) { snapshot = List.copyOf(entries.values()); }
        List<List<String>> roleBlocks = snapshot.stream().flatMap(entry -> entry.roles().values().stream()).distinct().toList();
        Map<List<String>, Integer> blockIds = new HashMap<>();
        IntStream.range(0, roleBlocks.size()).forEach(index -> blockIds.put(roleBlocks.get(index), index));
        Path parent = file.toAbsolutePath().getParent(); Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "prepared-recipes-", ".tmp");
        try {
            try (DataOutputStream output = new DataOutputStream(new BoundedOutputStream(
                    new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary))), MAX_BYTES))) {
                output.writeInt(MAGIC); output.writeInt(FORMAT); writeText(output, ADAPTER);
                output.writeInt(roleBlocks.size());
                for (List<String> block : roleBlocks) {
                    output.writeInt(block.size());
                    for (String value : block) writeText(output, value);
                }
                output.writeInt(snapshot.size());
                for (Entry entry : snapshot) {
                    writeText(output, entry.id().category()); writeText(output, entry.id().recipe());
                    writeText(output, entry.fingerprint()); writeText(output, entry.recipeJson());
                    output.writeInt(entry.dependencies().size());
                    for (var dependency : new TreeMap<>(entry.dependencies()).entrySet()) {
                        writeText(output, dependency.getKey()); writeText(output, dependency.getValue());
                    }
                    output.writeInt(entry.roles().size());
                    for (var role : entry.roles().entrySet()) { output.writeInt(role.getKey()); output.writeInt(blockIds.get(role.getValue())); }
                }
            }
            if (Files.size(temporary) > MAX_BYTES) throw new IOException("Compressed cache exceeds budget");
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    static PreparedRecipeStore load(Path file) throws IOException {
        if (!Files.exists(file)) return new PreparedRecipeStore();
        if (Files.size(file) > MAX_BYTES) throw new IOException("Cache file exceeds budget");
        try (DataInputStream input = new DataInputStream(new BoundedInputStream(
                new GZIPInputStream(new BufferedInputStream(Files.newInputStream(file))), MAX_BYTES))) {
            if (input.readInt() != MAGIC || input.readInt() != FORMAT || !ADAPTER.equals(readText(input))) throw new IOException("Unsupported recipe cache");
            int blockCount = count(input, MAX_ENTRIES * 4);
            List<List<String>> roleBlocks = new ArrayList<>();
            long totalValues = 0;
            for (int block = 0; block < blockCount; block++) {
                int size = count(input, MAX_BLOCK_ITEMS);
                totalValues += size;
                if (totalValues > 2_000_000) throw new IOException("Too many ingredient references");
                List<String> values = new ArrayList<>();
                for (int value = 0; value < size; value++) values.add(readText(input));
                roleBlocks.add(List.copyOf(values));
            }
            int entryCount = count(input, MAX_ENTRIES);
            PreparedRecipeStore store = new PreparedRecipeStore();
            Set<Id> seen = new HashSet<>();
            for (int index = 0; index < entryCount; index++) {
                Id id = new Id(readText(input), readText(input));
                String fingerprint = readText(input), recipeJson = readText(input);
                Map<String, String> dependencies = new HashMap<>();
                int dependencyCount = count(input, 1024);
                for (int dependency = 0; dependency < dependencyCount; dependency++) {
                    if (dependencies.put(readText(input), readText(input)) != null) throw new IOException("Duplicate dependency");
                }
                Map<Integer, List<String>> roles = new TreeMap<>();
                int roleCount = count(input, 4);
                for (int role = 0; role < roleCount; role++) {
                    int ordinal = input.readInt(), block = input.readInt();
                    if (ordinal < 0 || ordinal >= 4 || block < 0 || block >= roleBlocks.size()
                            || roles.put(ordinal, roleBlocks.get(block)) != null) throw new IOException("Invalid role reference");
                }
                if (!seen.add(id) || !store.put(new Entry(id, fingerprint, recipeJson, dependencies, roles))) throw new IOException("Invalid or duplicate entry");
            }
            if (input.read() != -1) throw new IOException("Trailing recipe cache data"); // Forces gzip checksum validation.
            return store;
        } catch (IllegalArgumentException invalid) { throw new IOException("Invalid recipe cache entry", invalid); }
    }

    private static int count(DataInputStream input, int maximum) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > maximum) throw new IOException("Invalid recipe cache count");
        return count;
    }
    private static String readText(DataInputStream input) throws IOException {
        int length = count(input, MAX_TEXT_BYTES);
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException();
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!Arrays.equals(bytes, text.getBytes(StandardCharsets.UTF_8))) throw new IOException("Invalid UTF-8 cache text");
        return text;
    }
    private static void writeText(DataOutputStream output, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TEXT_BYTES || !new String(bytes, StandardCharsets.UTF_8).equals(text)) throw new IOException("Recipe cache text exceeds limit or contains invalid Unicode");
        output.writeInt(bytes.length); output.write(bytes);
    }
    private static final class BoundedInputStream extends FilterInputStream {
        private long remaining;
        BoundedInputStream(InputStream input, long limit) { super(input); remaining = limit; }
        @Override public int read() throws IOException {
            int value = in.read(); if (value >= 0 && --remaining < 0) throw new IOException("Decompressed cache exceeds budget"); return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = in.read(bytes, offset, (int) Math.min(length, Math.max(1, remaining + 1)));
            if (count > 0 && (remaining -= count) < 0) throw new IOException("Decompressed cache exceeds budget"); return count;
        }
    }
    private static final class BoundedOutputStream extends FilterOutputStream {
        private long remaining;
        BoundedOutputStream(OutputStream output, long limit) { super(output); remaining = limit; }
        @Override public void write(int value) throws IOException {
            if (--remaining < 0) throw new IOException("Recipe cache exceeds budget"); out.write(value);
        }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            if ((remaining -= length) < 0) throw new IOException("Recipe cache exceeds budget"); out.write(bytes, offset, length);
        }
    }
}
