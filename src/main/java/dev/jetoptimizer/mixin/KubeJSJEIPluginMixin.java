package dev.jetoptimizer.mixin;

import dev.jetoptimizer.KubeJSCallbackProbe;
import dev.jetoptimizer.KubeJSItemRemovalIndex;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.recipe.IRecipeCategoriesLookup;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.common.crafting.CompoundIngredient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;
import java.util.Iterator;
import java.util.stream.Collector;
import java.util.stream.Stream;

/**
 * Narrow KubeJS's onRuntimeAvailable work: phase-probes its sub-segments, and skips building the
 * recipe-category map entirely when neither REMOVE_* event has listeners and remote is null.
 * The category-map skip is fail-open: any reflection failure or a present listener builds the
 * full map exactly as KubeJS would. KubeJS is not on the compile classpath, so all KubeJS-typed
 * targets are string-based with JEI-typed redirect handlers where the callee is a JEI type.
 */
@Pseudo
@Mixin(targets = "dev.latvian.mods.kubejs.integration.jei.KubeJSJEIPlugin", remap = false)
@SuppressWarnings({"unused", "rawtypes", "unchecked"})
abstract class KubeJSJEIPluginMixin {
    @Inject(method = "onRuntimeAvailable", at = @At("HEAD"), remap = false)
    private void jetoptimizer$beginKubeJSItemRemovalIndex(IJeiRuntime runtime, CallbackInfo callbackInfo) {
        KubeJSCallbackProbe.begin();
        KubeJSItemRemovalIndex.beginCallback(runtime);
    }

    @Redirect(
        method = "onRuntimeAvailable",
        at = @At(value = "INVOKE", ordinal = 0, target =
            "Ljava/util/stream/Stream;collect(Ljava/util/stream/Collector;)Ljava/lang/Object;", remap = false),
        remap = false,
        require = 1
    )
    private static Object jetoptimizer$timeRecipeCategoryMap(Stream stream, Collector collector) {
        long startedAt = System.nanoTime();
        Object categories = stream.collect(collector);
        KubeJSCallbackProbe.recordCategoryMapCollect(System.nanoTime() - startedAt);
        return categories;
    }

    @Redirect(
        method = "onRuntimeAvailable",
        at = @At(value = "INVOKE", ordinal = 0, target =
            "Lmezz/jei/api/runtime/IJeiRuntime;getRecipeManager()Lmezz/jei/api/recipe/IRecipeManager;", remap = false),
        remap = false,
        require = 1
    )
    private static IRecipeManager jetoptimizer$timeFirstRecipeManagerGet(IJeiRuntime runtime) {
        long startedAt = System.nanoTime();
        IRecipeManager recipeManager = runtime.getRecipeManager();
        KubeJSCallbackProbe.recordManagerGetters(System.nanoTime() - startedAt);
        return recipeManager;
    }

    @Redirect(
        method = "onRuntimeAvailable",
        at = @At(value = "INVOKE", ordinal = 1, target =
            "Lmezz/jei/api/runtime/IJeiRuntime;getRecipeManager()Lmezz/jei/api/recipe/IRecipeManager;", remap = false),
        remap = false,
        require = 1
    )
    private static IRecipeManager jetoptimizer$timeSecondRecipeManagerGet(IJeiRuntime runtime) {
        long startedAt = System.nanoTime();
        IRecipeManager recipeManager = runtime.getRecipeManager();
        KubeJSCallbackProbe.recordManagerGetters(System.nanoTime() - startedAt);
        return recipeManager;
    }

    @Redirect(
        method = "onRuntimeAvailable",
        at = @At(value = "INVOKE", ordinal = 0, target =
            "Lmezz/jei/api/runtime/IJeiRuntime;getIngredientManager()Lmezz/jei/api/runtime/IIngredientManager;", remap = false),
        remap = false,
        require = 1
    )
    private static IIngredientManager jetoptimizer$timeIngredientManagerGet(IJeiRuntime runtime) {
        long startedAt = System.nanoTime();
        IIngredientManager ingredientManager = runtime.getIngredientManager();
        KubeJSCallbackProbe.recordManagerGetters(System.nanoTime() - startedAt);
        return ingredientManager;
    }

    @Redirect(
        method = "onRuntimeAvailable",
        at = @At(value = "INVOKE", ordinal = 0, target =
            "Lmezz/jei/api/recipe/IRecipeManager;createRecipeCategoryLookup()Lmezz/jei/api/recipe/IRecipeCategoriesLookup;", remap = false),
        remap = false,
        require = 1
    )
    private static IRecipeCategoriesLookup jetoptimizer$timeCategoryLookupCreate(IRecipeManager recipeManager) {
        long startedAt = System.nanoTime();
        IRecipeCategoriesLookup lookup = recipeManager.createRecipeCategoryLookup();
        KubeJSCallbackProbe.recordCategoryLookupCreate(System.nanoTime() - startedAt);
        return lookup;
    }

    @Redirect(
        method = "onRuntimeAvailable",
        at = @At(value = "INVOKE", ordinal = 0, target =
            "Lmezz/jei/api/recipe/IRecipeCategoriesLookup;get()Ljava/util/stream/Stream;", remap = false),
        remap = false,
        require = 1
    )
    private Stream<IRecipeCategory<?>> jetoptimizer$timeCategoryLookupGet(IRecipeCategoriesLookup lookup) {
        long startedAt = System.nanoTime();
        if (KubeJSCallbackProbe.categoriesMapUnused(this)) {
            KubeJSCallbackProbe.recordCategoryMapSkipped();
            KubeJSCallbackProbe.recordCategoryLookupGet(System.nanoTime() - startedAt);
            return Stream.empty();
        }
        Stream<IRecipeCategory<?>> categories = lookup.get();
        KubeJSCallbackProbe.recordCategoryLookupGet(System.nanoTime() - startedAt);
        return categories;
    }

    @Inject(
        method = "onRuntimeAvailable",
        at = @At(value = "INVOKE", ordinal = 0, target =
            "Ldev/latvian/mods/kubejs/event/EventHandler;hasListeners()Z", remap = false),
        remap = false,
        require = 1
    )
    private void jetoptimizer$markCategoryMapBuilt(CallbackInfo callbackInfo) {
        KubeJSCallbackProbe.mark(KubeJSCallbackProbe.CATEGORY_MAP_BUILT);
    }

    @Inject(
        method = "onRuntimeAvailable",
        at = @At(value = "INVOKE", ordinal = 1, target =
            "Ldev/latvian/mods/kubejs/event/EventHandler;hasListeners()Z", remap = false),
        remap = false,
        require = 1
    )
    private void jetoptimizer$markRemoveCategoriesDone(CallbackInfo callbackInfo) {
        KubeJSCallbackProbe.mark(KubeJSCallbackProbe.REMOVE_CATEGORIES_DONE);
    }

    @Inject(
        method = "onRuntimeAvailable",
        at = @At(value = "INVOKE", ordinal = 0, target =
            "Ldev/latvian/mods/kubejs/recipe/viewer/server/RecipeViewerData;removedCategories()Ljava/util/List;", remap = false),
        remap = false,
        require = 1
    )
    private void jetoptimizer$markRemoveRecipesDone(CallbackInfo callbackInfo) {
        KubeJSCallbackProbe.mark(KubeJSCallbackProbe.REMOVE_RECIPES_DONE);
    }

    @Inject(
        method = "onRuntimeAvailable",
        at = @At(value = "INVOKE", ordinal = 0, target =
            "Lmezz/jei/api/runtime/IIngredientManager;getAllIngredients(Lmezz/jei/api/ingredients/IIngredientType;)Ljava/util/Collection;", remap = false),
        remap = false,
        require = 1
    )
    private void jetoptimizer$markIngredientFetchStart(CallbackInfo callbackInfo) {
        KubeJSCallbackProbe.mark(KubeJSCallbackProbe.INGREDIENT_FETCH_START);
    }

    @Inject(
        method = "onRuntimeAvailable",
        at = @At(value = "INVOKE", ordinal = 0, target =
            "Ldev/latvian/mods/kubejs/util/Lazy;get()Ljava/lang/Object;", remap = false),
        remap = false,
        require = 1
    )
    private void jetoptimizer$markAllTypesInitStart(CallbackInfo callbackInfo) {
        KubeJSCallbackProbe.mark(KubeJSCallbackProbe.ALL_TYPES_ENTER);
    }

    @Inject(
        method = "onRuntimeAvailable",
        at = @At(value = "INVOKE", ordinal = 0, shift = At.Shift.AFTER, target =
            "Ldev/latvian/mods/kubejs/util/Lazy;get()Ljava/lang/Object;", remap = false),
        remap = false,
        require = 1
    )
    private void jetoptimizer$markAllTypesInitEnd(CallbackInfo callbackInfo) {
        KubeJSCallbackProbe.mark(KubeJSCallbackProbe.ALL_TYPES_EXIT);
    }

    @Inject(
        method = "onRuntimeAvailable",
        at = @At(value = "INVOKE", ordinal = 0, target =
            "Ldev/latvian/mods/kubejs/recipe/viewer/server/RecipeViewerData;itemData()Ldev/latvian/mods/kubejs/recipe/viewer/server/ItemData;", remap = false),
        remap = false,
        require = 1
    )
    private void jetoptimizer$markRemoteItemRemovalStart(CallbackInfo callbackInfo) {
        KubeJSCallbackProbe.mark(KubeJSCallbackProbe.REMOTE_ITEM_REMOVAL_START);
    }

    @Inject(
        method = "onRuntimeAvailable",
        at = @At(value = "INVOKE", ordinal = 1, target =
            "Ldev/latvian/mods/kubejs/util/Lazy;get()Ljava/lang/Object;", remap = false),
        remap = false,
        require = 1
    )
    private void jetoptimizer$markAllTypesSecondInitStart(CallbackInfo callbackInfo) {
        KubeJSCallbackProbe.mark(KubeJSCallbackProbe.ALL_TYPES_2_ENTER);
    }

    @Inject(
        method = "onRuntimeAvailable",
        at = @At(value = "INVOKE", ordinal = 1, shift = At.Shift.AFTER, target =
            "Ldev/latvian/mods/kubejs/util/Lazy;get()Ljava/lang/Object;", remap = false),
        remap = false,
        require = 1
    )
    private void jetoptimizer$markAllTypesSecondInitEnd(CallbackInfo callbackInfo) {
        KubeJSCallbackProbe.mark(KubeJSCallbackProbe.ALL_TYPES_2_EXIT);
    }

    @Inject(
        method = "onRuntimeAvailable",
        at = @At(value = "INVOKE", ordinal = 0, target =
            "Ldev/latvian/mods/kubejs/recipe/viewer/server/ItemData;addedEntries()Ljava/util/List;", remap = false),
        remap = false,
        require = 1
    )
    private void jetoptimizer$markRemoteAddedEntriesStart(CallbackInfo callbackInfo) {
        KubeJSCallbackProbe.mark(KubeJSCallbackProbe.REMOTE_ADDED_ENTRIES_START);
    }

    @Redirect(
        method = "onRuntimeAvailable",
        at = @At(value = "INVOKE", ordinal = 0, target =
            "Lnet/neoforged/neoforge/common/crafting/CompoundIngredient;of([Lnet/minecraft/world/item/crafting/Ingredient;)Lnet/minecraft/world/item/crafting/Ingredient;", remap = false),
        remap = false,
        require = 1
    )
    private static Ingredient jetoptimizer$prepareItemFilter(Ingredient[] filterIngredients) {
        Ingredient filter = CompoundIngredient.of(filterIngredients);
        return KubeJSItemRemovalIndex.prepareItemFilter(filter, filterIngredients.length);
    }

    @Redirect(
        method = "onRuntimeAvailable",
        at = @At(value = "INVOKE", ordinal = 0, target = "Ljava/util/Collection;iterator()Ljava/util/Iterator;", remap = false),
        remap = false,
        require = 1
    )
    private static Iterator<?> jetoptimizer$iterateDenseItemCandidates(Collection<?> allItems) {
        return KubeJSItemRemovalIndex.itemIterator(allItems);
    }

    @Redirect(
        method = "onRuntimeAvailable",
        at = @At(value = "INVOKE", ordinal = 0, target =
            "Lnet/minecraft/world/item/crafting/Ingredient;test(Lnet/minecraft/world/item/ItemStack;)Z", remap = false),
        remap = false,
        require = 1
    )
    private static boolean jetoptimizer$testSelectedItemCandidate(Ingredient filter, ItemStack stack) {
        return KubeJSItemRemovalIndex.testItem(filter, stack);
    }

    @Redirect(
        method = "onRuntimeAvailable",
        at = @At(value = "INVOKE", ordinal = 0, target =
            "Lmezz/jei/api/runtime/IIngredientManager;removeIngredientsAtRuntime(Lmezz/jei/api/ingredients/IIngredientType;Ljava/util/Collection;)V", remap = false),
        remap = false,
        require = 1
    )
    private static void jetoptimizer$recordItemRemovalRequest(
        IIngredientManager ingredientManager,
        IIngredientType<?> ingredientType,
        Collection<?> ingredients
    ) {
        KubeJSItemRemovalIndex.recordRemovalRequest(ingredients.size());
        ingredientManager.removeIngredientsAtRuntime((IIngredientType) ingredientType, ingredients);
    }

    @Inject(method = "onRuntimeAvailable", at = @At("RETURN"), remap = false)
    private void jetoptimizer$finishKubeJSItemRemovalIndex(IJeiRuntime runtime, CallbackInfo callbackInfo) {
        KubeJSCallbackProbe.finish();
        KubeJSItemRemovalIndex.finishCallback();
    }
}
