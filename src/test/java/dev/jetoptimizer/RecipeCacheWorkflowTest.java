package dev.jetoptimizer;

import com.google.common.collect.ImmutableListMultimap;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IIngredientVisibility;
import mezz.jei.library.config.RecipeCategorySortingConfig;
import mezz.jei.library.recipes.RecipeManagerInternal;
import mezz.jei.library.plugins.vanilla.crafting.CraftingRecipeCategory;
import mezz.jei.library.plugins.vanilla.ingredients.ItemStackHelper;
import mezz.jei.library.gui.helpers.CraftingGridHelper;
import mezz.jei.library.gui.recipes.supplier.builder.IngredientSupplierBuilder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

/** Real service batching, native crafting grid extraction, registry decoding and disk round-trip. */
@Tag("workflow")
class RecipeCacheWorkflowTest {
    @TempDir Path directory;
    TestState state; RecipeCacheWorkers workers;
    PreparedRecipeStore store; IIngredientManager ingredients;
    CraftingRecipeCategory category; RecipeManagerInternal manager;
    @BeforeEach void start() throws Exception {
        state=new TestState(); workers=new RecipeCacheWorkers(3);
        store=new PreparedRecipeStore(); category=NativeRecipeCacheAdapterTest.crafting();
        ingredients=NativeRecipeCacheAdapterTest.manager(new ItemStackHelper(null,null));
        // JEI sorting and visibility collaborators have no disk/UI work in this service test.
        var sortingType=Class.forName("net.mezzdev.config.api.sorting.ISortingConfig");
        var sorting=Proxy.newProxyInstance(sortingType.getClassLoader(),new Class<?>[]{sortingType},(p,m,a) -> switch(m.getName()) {
            case "getComparator" -> Comparator.<String>naturalOrder();
            case "addChangeListener" -> (Runnable)() -> {};
            case "isVisible" -> true;
            default -> throw new UnsupportedOperationException(m.getName());
        });
        var config=(RecipeCategorySortingConfig)RecipeCategorySortingConfig.class.getConstructor(sortingType).newInstance(sorting);
        var visibility=(IIngredientVisibility)Proxy.newProxyInstance(IIngredientVisibility.class.getClassLoader(),new Class<?>[]{IIngredientVisibility.class},(p,m,a) -> {
            if(m.getName().equals("registerListener"))return null;
            if(m.getName().equals("isIngredientVisible"))return true;
            throw new UnsupportedOperationException(m.getName());
        });
        manager=new RecipeManagerInternal(List.of(category),ImmutableListMultimap.of(),ingredients,config,visibility);
        state.set(PersistentRecipeCache.class,"workers",workers);
        state.set(PersistentRecipeCache.class,"preloaded",null);
        state.set(PersistentRecipeCache.class,"encoder",null);
        state.set(PersistentRecipeCache.class,"ingredientManager",null);
        state.set(PersistentRecipeCache.class,"enabled",false);
        state.set(PersistentRecipeCache.class,"registering",false);
        runtime(store,NativeRecipeCacheAdapterTest.manifest());
    }
    void runtime(PreparedRecipeStore loaded,Map<String,String> manifest) throws Exception {
        PersistentRecipeCache.beginRuntime();
        state.set(PersistentRecipeCache.class,"enabled",true);
        state.set(PersistentRecipeCache.class,"ingredientManager",ingredients);
        state.set(PersistentRecipeCache.class,"encoder",new NativeRecipeCacheAdapter.Encoder(
            RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY),ingredients));
        var type=Class.forName("dev.jetoptimizer.PersistentRecipeCache$State");
        var constructor=type.getDeclaredConstructor(PreparedRecipeStore.class,Map.class); constructor.setAccessible(true);
        state.set(PersistentRecipeCache.class,"preloaded",CompletableFuture.completedFuture(constructor.newInstance(loaded,manifest)));
    }
    @AfterEach void stop() throws Exception {
        try { state.set(PersistentRecipeCache.class,"enabled",false); PersistentRecipeCache.stopRuntime(); }
        finally { workers.close(); state.close(); }
    }
    RecipeHolder<CraftingRecipe> recipe(Item item,int count) {
        var recipe=NativeRecipeCacheAdapterTest.recipe(new ItemStack(item,count),Ingredient.of(Items.COAL));
        return new RecipeHolder<>(recipe.id(),recipe.value());
    }
    void nativeCapture(RecipeHolder<CraftingRecipe> recipe) {
        // The native extension obtains its registry through Minecraft.level. Supply the test registry
        // directly, then execute the same real JEI grid helper and ingredient builder operations.
        var builder=new IngredientSupplierBuilder(ingredients);
        var registry=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        CraftingGridHelper.INSTANCE.createAndSetOutputs(builder,List.of(recipe.value().getResultItem(registry)));
        CraftingGridHelper.INSTANCE.createAndSetIngredients(builder,recipe.value().getIngredients(),0,0);
        PersistentRecipeCache.capture(recipe,category,builder.buildIngredientSupplier());
    }
    @Test void firstJoinRelaunchAndServerPayloadChangeUseOnlyAuthoritativeRecipes() throws Exception {
        var first=recipe(Items.DIAMOND,1);
        try(var batch=PersistentRecipeCache.prepareBatch(manager,category.getRecipeType(),List.of(first))) {
            assertNull(PersistentRecipeCache.lookup(first,category)); nativeCapture(first);
            assertEquals(0,store.size(),"entries commit after the batch, not per recipe");
        }
        assertEquals(1,store.size()); var path=directory.resolve("recipes.gz"); store.save(path);
        var loaded=PreparedRecipeStore.load(path); runtime(loaded,NativeRecipeCacheAdapterTest.manifest());
        var fresh=recipe(Items.DIAMOND,1);
        try(var batch=PersistentRecipeCache.prepareBatch(manager,category.getRecipeType(),List.of(fresh))) {
            assertNotNull(PersistentRecipeCache.lookup(fresh,category));
            assertNull(PersistentRecipeCache.lookup(first,category),"old world objects are not candidates");
        }
        var changed=recipe(Items.EMERALD,3);
        try(var batch=PersistentRecipeCache.prepareBatch(manager,category.getRecipeType(),List.of(changed))) {
            assertNull(PersistentRecipeCache.lookup(changed,category)); nativeCapture(changed);
        }
        try(var batch=PersistentRecipeCache.prepareBatch(manager,category.getRecipeType(),List.of(changed))) {
            var hit=PersistentRecipeCache.lookup(changed,category); assertNotNull(hit);
            var output=hit.getIngredients(mezz.jei.api.recipe.RecipeIngredientRole.OUTPUT).getFirst().getIngredient();
            assertTrue(output instanceof ItemStack stack && stack.is(Items.EMERALD) && stack.getCount()==3);
        }
        try(var deleted=PersistentRecipeCache.prepareBatch(manager,category.getRecipeType(),List.of())) {
            assertNull(PersistentRecipeCache.lookup(changed,category),"deleted recipes must never be injected from disk");
        }
        assertNull(PersistentRecipeCache.lookup(changed,category),"scope must close before runtime callbacks");
    }
    @Test void duplicateIdsWithDifferentPayloadsCannotShareTheWrongSupplier() throws Exception {
        var first=recipe(Items.DIAMOND,1); var second=recipe(Items.EMERALD,2);
        try(var batch=PersistentRecipeCache.prepareBatch(manager,category.getRecipeType(),List.of(first,second))) {
            nativeCapture(first); nativeCapture(second);
        }
        try(var batch=PersistentRecipeCache.prepareBatch(manager,category.getRecipeType(),List.of(first,second))) {
            assertNull(PersistentRecipeCache.lookup(first,category)); assertNotNull(PersistentRecipeCache.lookup(second,category));
        }
    }
    @Test void unsupportedNestedBatchMasksOuterRecipeCacheAndRestoresItOnFailure() throws Exception {
        var recipe=recipe(Items.DIAMOND,1);
        try(var batch=PersistentRecipeCache.prepareBatch(manager,category.getRecipeType(),List.of(recipe))) { nativeCapture(recipe); }
        try(var outer=PersistentRecipeCache.prepareBatch(manager,category.getRecipeType(),List.of(recipe))) {
            assertNotNull(PersistentRecipeCache.lookup(recipe,category));
            assertThrows(IllegalStateException.class,() -> {
                try(var inner=PersistentRecipeCache.prepareBatch(manager,category.getRecipeType(),List.of(new Object()))) {
                    assertNull(PersistentRecipeCache.lookup(recipe,category)); throw new IllegalStateException("plugin failed");
                }
            });
            assertNotNull(PersistentRecipeCache.lookup(recipe,category));
        }
        assertNull(PersistentRecipeCache.lookup(recipe,category));
    }
    @Test void updatedModHashAndSlowPreloadBothChooseNativeFallback() throws Exception {
        var recipe=recipe(Items.DIAMOND,1);
        try(var batch=PersistentRecipeCache.prepareBatch(manager,category.getRecipeType(),List.of(recipe))) { nativeCapture(recipe); }
        var manifest=new HashMap<>(NativeRecipeCacheAdapterTest.manifest()); manifest.put("example","jar-v2");
        assertEquals(1,store.invalidate(manifest)); runtime(store,manifest);
        try(var batch=PersistentRecipeCache.prepareBatch(manager,category.getRecipeType(),List.of(recipe))) {
            assertNull(PersistentRecipeCache.lookup(recipe,category)); nativeCapture(recipe);
        }
        state.set(PersistentRecipeCache.class,"preloaded",new CompletableFuture<>());
        try(var batch=PersistentRecipeCache.prepareBatch(manager,category.getRecipeType(),List.of(recipe))) {
            assertNull(PersistentRecipeCache.lookup(recipe,category));
        }
    }
}
