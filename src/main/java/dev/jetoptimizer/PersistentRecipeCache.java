package dev.jetoptimizer;

import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.library.ingredients.RecipeIngredientSupplier;
import mezz.jei.library.recipes.RecipeManagerInternal;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/** Persistent prepared native recipe data, verified against the current synchronized recipe objects. */
public final class PersistentRecipeCache {
    private record ModFile(String id, String version, Path path) {}
    private record State(PreparedRecipeStore store, Map<String, String> manifest) {}
    private record Candidate(NativeRecipeCacheAdapter.Input input, String fingerprint, PreparedRecipeStore.Entry entry) {}
    private static final ExecutorService DISK = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "jetoptimizer-recipe-disk"); thread.setDaemon(true); return thread;
    });
    private static final ThreadLocal<Batch> CURRENT = new ThreadLocal<>();
    private static final AtomicLong REVISION = new AtomicLong();
    private static final AtomicLong SAVED_REVISION = new AtomicLong();
    private static final AtomicBoolean WRITING = new AtomicBoolean();
    private static volatile CompletableFuture<State> preloaded;
    private static RecipeCacheWorkers workers;
    private static Path file;
    private static Boolean enabled;
    private static boolean registering;
    private static NativeRecipeCacheAdapter.Encoder encoder;
    private static IIngredientManager ingredientManager;
    private static long hits, misses, bypasses, captured, prepareNanos, restoreNanos, sharedUidRequests;

    private PersistentRecipeCache() {}

    private static final class ManagerField {
        static final Field VALUE = find();
        private static Field find() {
            try {
                Field field = RecipeManagerInternal.class.getDeclaredField("ingredientManager");
                field.setAccessible(true); return field;
            } catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
        }
    }

    public static synchronized void initialize(Path path) {
        if (preloaded != null || !enabled()) return;
        try {
            file = path;
            workers = new RecipeCacheWorkers(JETOptimizerConfig.RECIPE_CACHE_WORKERS.get());
            // Resolve loader objects on the client setup thread. Workers receive only IDs/versions/paths.
            List<ModFile> mods = ModList.get().getMods().stream().map(info -> new ModFile(info.getModId(),
                    info.getVersion().toString(), info.getOwningFile().getFile().getFilePath())).toList();
            preloaded = CompletableFuture.supplyAsync(() -> {
                long started = System.nanoTime();
                ConcurrentMap<Path, String> jars = new ConcurrentHashMap<>();
                Map<String, String> manifest = workers.mapOrdered(mods, mod -> {
                    String hash;
                    try {
                        hash = jars.computeIfAbsent(mod.path(), jar -> {
                            try { return hashPath(jar); }
                            catch (IOException failure) { throw new CompletionException(failure); }
                        });
                    } catch (RuntimeException failure) {
                        hash = "unavailable";
                        JETOptimizer.LOGGER.warn("Recipe cache cannot fingerprint mod {}: {}", mod.id(), failure.getMessage());
                    }
                    return Map.entry(mod.id(), mod.version() + ":" + hash);
                }).stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
                PreparedRecipeStore store;
                try { store = PreparedRecipeStore.load(path); }
                catch (IOException | RuntimeException failure) {
                    JETOptimizer.LOGGER.warn("Prepared recipe cache ignored; recipes will rebuild: {}", failure.getMessage());
                    store = new PreparedRecipeStore();
                }
                int invalidated = store.invalidate(manifest);
                if (invalidated > 0) REVISION.incrementAndGet();
                JETOptimizer.LOGGER.info("Prepared recipe cache preloaded: {} recipes, {} invalidated, {} mods in {} ms",
                        store.size(), invalidated, manifest.size(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                return new State(store, manifest);
            }, DISK);
        } catch (RuntimeException | LinkageError failure) {
            if (workers != null) workers.close();
            JETOptimizer.LOGGER.warn("Prepared recipe cache preload could not start; original JEI handling remains active: {}", failure.getMessage());
        }
    }

    private static String hashPath(Path path) throws IOException {
        if (Files.isRegularFile(path)) return RecipeCacheHash.file(path);
        if (!Files.isDirectory(path)) throw new IOException("Mod file is unavailable");
        // Development runs use class/resource directories. Fingerprint their full contents too.
        try (var files = Files.walk(path)) {
            List<Path> members = files.filter(Files::isRegularFile).sorted().limit(8193).toList();
            if (members.size() > 8192) throw new IOException("Development mod directory exceeds file limit");
            List<String> fields = new ArrayList<>();
            for (Path member : members) { fields.add(path.relativize(member).toString()); fields.add(RecipeCacheHash.file(member)); }
            return RecipeCacheHash.strings(fields);
        }
    }

    private static boolean enabled() {
        if (enabled != null) return enabled;
        try {
            return enabled = JETOptimizerConfig.ENABLED.get() && JETOptimizerConfig.RECONNECT_CACHE.get()
                    && JETOptimizerConfig.EXPERIMENTAL_OPTIMIZATIONS.get() && JETOptimizerConfig.PERSISTENT_RECIPE_CACHE.get()
                    && ModList.get().getModContainerById("jei")
                    .map(container -> "19.57.0.449".equals(container.getModInfo().getVersion().toString())).orElse(false);
        } catch (RuntimeException | LinkageError ignored) { return false; }
    }

    public static void beginRuntime() {
        clearRuntime(); enabled = null; registering = true;
        hits = misses = bypasses = captured = prepareNanos = restoreNanos = sharedUidRequests = 0;
    }

    public static void finishRuntime() {
        if (enabled()) {
            State state = ready();
            if (state != null && REVISION.get() != SAVED_REVISION.get()) scheduleSave(state);
            JETOptimizer.LOGGER.info("Prepared recipe cache: {} hits, {} misses, {} bypasses, {} captured, {} shared ingredient UID requests; snapshot/hash {} ms, rebind {} ms",
                    hits, misses, bypasses, captured, sharedUidRequests, TimeUnit.NANOSECONDS.toMillis(prepareNanos), TimeUnit.NANOSECONDS.toMillis(restoreNanos));
        }
        clearRuntime();
    }

    public static void stopRuntime() {
        if (registering) finishRuntime(); else clearRuntime();
        enabled = null;
    }

    private static void clearRuntime() {
        registering = false; CURRENT.remove(); encoder = null; ingredientManager = null;
    }

    private static State ready() {
        CompletableFuture<State> future = preloaded;
        return future == null || !future.isDone() || future.isCompletedExceptionally() ? null : future.getNow(null);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Batch prepareBatch(RecipeManagerInternal manager, RecipeType<?> type, List<?> recipes) {
        Batch batch = new Batch(CURRENT.get());
        CURRENT.set(batch); batch.installed = true;
        State state = ready();
        if (!registering || !enabled() || state == null || recipes.isEmpty()) { bypasses += recipes.size(); return batch; }
        long started = System.nanoTime();
        try {
            IRecipeCategory<?> category = manager.getRecipeCategory((RecipeType) type);
            if (!NativeRecipeCacheAdapter.supportedCategory(category)) { bypasses += recipes.size(); return batch; }
            IIngredientManager currentManager = (IIngredientManager) ManagerField.VALUE.get(manager);
            if (ingredientManager != currentManager) {
                encoder = new NativeRecipeCacheAdapter.Encoder(Objects.requireNonNull(Minecraft.getInstance().level).registryAccess(), currentManager);
                ingredientManager = currentManager;
            }
            encoder.snapshotCategory(category);
            List<Object> supported = new ArrayList<>();
            List<NativeRecipeCacheAdapter.Input> detached = new ArrayList<>();
            long snapshotBytes = 0;
            for (Object recipe : recipes) {
                NativeRecipeCacheAdapter.Input input = NativeRecipeCacheAdapter.prepare(recipe, category, encoder, state.manifest());
                if (input == null) { bypasses++; continue; }
                snapshotBytes += input.fields().stream().mapToLong(fieldValue -> 2L * fieldValue.length()).sum();
                if (snapshotBytes > 64L * 1024 * 1024) throw new IllegalArgumentException("Recipe batch snapshot exceeds budget");
                supported.add(recipe); detached.add(input);
            }
            // Registry/codec access has finished. Pure hashes can now be prepared in ordered chunks.
            List<String> fingerprints = workers.mapOrdered(detached, NativeRecipeCacheAdapter.Input::fingerprint);
            Map<PreparedRecipeStore.Id, String> keys = new LinkedHashMap<>();
            java.util.stream.IntStream.range(0, detached.size()).forEach(index -> keys.put(detached.get(index).id(), fingerprints.get(index)));
            Map<PreparedRecipeStore.Id, PreparedRecipeStore.Entry> cached = state.store().lookupAll(keys);
            batch.category = category; batch.state = state; batch.encoder = encoder;
            java.util.stream.IntStream.range(0, detached.size()).forEach(index -> {
                var input = detached.get(index);
                PreparedRecipeStore.Entry entry = cached.get(input.id());
                if (entry != null && (!entry.fingerprint().equals(fingerprints.get(index)) || !entry.roles().equals(input.expectedRoles()))) entry = null;
                batch.candidates.put(supported.get(index), new Candidate(input, fingerprints.get(index), entry));
            });
            CURRENT.set(batch); batch.installed = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            bypasses += recipes.size();
            JETOptimizerProfiler.recordOptimizationFallback("prepared recipe batch");
        } finally { prepareNanos += System.nanoTime() - started; }
        return batch;
    }

    public static RecipeIngredientSupplier lookup(Object recipe, IRecipeCategory<?> category) {
        Batch batch = CURRENT.get();
        Candidate candidate = batch == null || batch.category != category ? null : batch.candidates.get(recipe);
        if (candidate == null) return null;
        if (candidate.entry() == null) { misses++; return null; }
        long started = System.nanoTime();
        try {
            RecipeIngredientSupplier supplier = NativeRecipeCacheAdapter.restore(candidate.entry(), batch.encoder);
            hits++; return supplier;
        } catch (RuntimeException | LinkageError failure) {
            batch.candidates.put(recipe, new Candidate(candidate.input(), candidate.fingerprint(), null));
            misses++;
            JETOptimizerProfiler.recordOptimizationFallback("prepared recipe rebind");
            return null;
        } finally { restoreNanos += System.nanoTime() - started; }
    }

    public static <T> Object recipeUid(IIngredientHelper<T> helper, ITypedIngredient<T> ingredient, UidContext context) {
        Batch batch = CURRENT.get();
        if (batch != null && batch.encoder != null && context == UidContext.Recipe && batch.encoder.shared(ingredient)) {
            sharedUidRequests++;
            return batch.encoder.recipeUid(helper, ingredient);
        }
        return helper.getUid(ingredient, context);
    }

    public static void capture(Object recipe, IRecipeCategory<?> category, RecipeIngredientSupplier supplier) {
        Batch batch = CURRENT.get();
        Candidate candidate = batch == null || batch.category != category ? null : batch.candidates.get(recipe);
        if (candidate == null || candidate.entry() != null) return;
        try {
            PreparedRecipeStore.Entry entry = NativeRecipeCacheAdapter.capture(candidate.input(), candidate.fingerprint(), supplier, batch.encoder);
            if (entry != null) batch.captures.add(entry);
        } catch (RuntimeException | LinkageError failure) {
            JETOptimizerProfiler.recordOptimizationFallback("prepared recipe capture");
        }
    }

    public static final class Batch implements AutoCloseable {
        private final Batch previous;
        private boolean installed;
        private State state;
        private IRecipeCategory<?> category;
        private NativeRecipeCacheAdapter.Encoder encoder;
        private final Map<Object, Candidate> candidates = new IdentityHashMap<>();
        private final List<PreparedRecipeStore.Entry> captures = new ArrayList<>();
        private Batch(Batch previous) { this.previous = previous; }
        @Override public void close() {
            if (!installed) return;
            try {
                int changed = state == null ? 0 : state.store().putAll(captures);
                captured += changed;
                if (changed != 0) REVISION.incrementAndGet();
            } catch (RuntimeException failure) { JETOptimizerProfiler.recordOptimizationFallback("prepared recipe bulk commit"); }
            finally {
                if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
                candidates.clear(); captures.clear(); category = null; encoder = null; state = null; installed = false;
            }
        }
    }

    private static void scheduleSave(State state) {
        if (!WRITING.compareAndSet(false, true)) return;
        DISK.execute(() -> {
            long revision = REVISION.get();
            try { state.store().save(file); SAVED_REVISION.set(revision); }
            catch (IOException | RuntimeException failure) { JETOptimizer.LOGGER.warn("Could not save prepared recipe cache: {}", failure.getMessage()); }
            finally { WRITING.set(false); if (REVISION.get() != revision) scheduleSave(state); }
        });
    }
}
