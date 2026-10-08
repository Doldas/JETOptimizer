package dev.jetoptimizer;

import mezz.jei.modshade.net.mezzdev.bakedsubstring.BakedSubstringIndex;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Runs the real service and disk executor with detached loader/config inputs. No game client is simulated. */
@Tag("workflow")
class SearchCacheWorkflowTest {
    @TempDir Path directory;
    TestState state;
    @BeforeEach void start() throws Exception {
        drain(); state = new TestState();
        state.config(JETOptimizerConfig.DEBUG_CACHE, false);
        state.config(JETOptimizerConfig.DEBUG_CACHE_INVALIDATION, false);
        state.set(PersistentSearchIndexCache.class,"cachedEnabled",true);
        state.set(PersistentSearchIndexCache.class,"preloaded",null);
        state.set(PersistentSearchIndexCache.class,"file",null);
    }
    @AfterEach void stop() throws Exception { try { drain(); } finally { state.close(); } }
    static void drain() throws Exception { CacheDiskExecutor.INSTANCE.submit(() -> {}).get(5,TimeUnit.SECONDS); }
    void launch(Path file) throws Exception {
        PersistentSearchIndexCache.initialize(file);
        ((CompletableFuture<?>)TestState.get(PersistentSearchIndexCache.class,"preloaded")).get(5,TimeUnit.SECONDS);
    }
    static <T> BakedSubstringIndex<T> build(List<String> keys,List<T> values) {
        var builder = BakedSubstringIndex.<T>builder();
        for (int i=0;i<keys.size();i++) builder.put(keys.get(i),values.get(i));
        return builder.build();
    }
    @Test void firstJoinSavesThenRelaunchAndReconnectRebindToCurrentIngredients() throws Exception {
        var path=directory.resolve("search.bin"); launch(path);
        var keys=List.of("copper ingot","iron ingot"); var old=List.of(new Object(),new Object());
        var miss=PersistentSearchIndexCache.lookup(keys,old); assertNotNull(miss); assertNull(miss.result());
        PersistentSearchIndexCache.capture(miss,build(keys,old)); drain(); assertTrue(Files.exists(path));
        state.set(PersistentSearchIndexCache.class,"preloaded",null); launch(path);
        var current=List.of(new Object(),new Object());
        var hit=PersistentSearchIndexCache.lookup(keys,current); assertNotNull(hit.result());
        assertEquals(List.of(current.getFirst()),new ArrayList<>(hit.result().getSearchResults("copper")));
        assertFalse(hit.result().getSearchResults("").contains(old.getFirst()));
        PersistentSearchIndexCache.beginRuntime(); state.set(PersistentSearchIndexCache.class,"cachedEnabled",true);
        assertNotNull(PersistentSearchIndexCache.lookup(keys,current).result());
    }
    @Test void anotherServersChangedRemovedOrReorderedSearchWordsRequireRebuild() throws Exception {
        launch(directory.resolve("search.bin")); var keys=List.of("copper","iron"); var values=List.of(new Object(),new Object());
        PersistentSearchIndexCache.capture(PersistentSearchIndexCache.lookup(keys,values),build(keys,values)); drain();
        for (var changed:List.of(List.of("copper","silver"),List.of("copper"),List.of("iron","copper"))) {
            var lookup=PersistentSearchIndexCache.lookup(changed,changed); assertNotNull(lookup); assertNull(lookup.result());
        }
    }
    @Test void corruptCacheIsIgnoredAndReplacedAfterSuccessfulNativeBuild() throws Exception {
        var path=directory.resolve("search.bin"); Files.write(path,new byte[]{1,2,3}); launch(path);
        var keys=List.of("coal"); var nativeIndex=build(keys,keys);
        var lookup=PersistentSearchIndexCache.lookup(keys,keys); assertNotNull(lookup); assertNull(lookup.result());
        PersistentSearchIndexCache.capture(lookup,nativeIndex); drain();
        assertEquals(1,DiskSearchIndexStore.load(path).size());
        assertNotNull(PersistentSearchIndexCache.lookup(keys,keys).result());
    }
    @Test void joinNeverWaitsForSlowOrFailedPreload() throws Exception {
        var pending=new CompletableFuture<DiskSearchIndexStore>(); state.set(PersistentSearchIndexCache.class,"preloaded",pending);
        // Running on a separate thread makes a blocking join fail with a bounded timeout.
        try (var executor=Executors.newSingleThreadExecutor()) {
            assertNull(executor.submit(() -> PersistentSearchIndexCache.lookup(List.of("coal"),List.of("coal"))).get(2,TimeUnit.SECONDS));
            pending.completeExceptionally(new java.io.IOException("unreadable"));
            assertNull(executor.submit(() -> PersistentSearchIndexCache.lookup(List.of("coal"),List.of("coal"))).get(2,TimeUnit.SECONDS));
        }
    }
    @Test void unwritableDiskKeepsMemoryReuseAvailable() throws Exception {
        var parent=directory.resolve("file-not-directory"); Files.writeString(parent,"occupied"); launch(parent.resolve("search.bin"));
        var keys=List.of("coal"); PersistentSearchIndexCache.capture(PersistentSearchIndexCache.lookup(keys,keys),build(keys,keys)); drain();
        assertNotNull(PersistentSearchIndexCache.lookup(keys,keys).result());
    }
    @Test void disablingCacheDoesNotCaptureOrReplaceNativeResults() throws Exception {
        var path=directory.resolve("search.bin"); launch(path); var keys=List.of("coal");
        var lookup=PersistentSearchIndexCache.lookup(keys,keys);
        state.set(PersistentSearchIndexCache.class,"cachedEnabled",false);
        assertNull(PersistentSearchIndexCache.lookup(keys,keys));
        PersistentSearchIndexCache.capture(lookup,build(keys,keys)); drain(); assertFalse(Files.exists(path));
    }
}
