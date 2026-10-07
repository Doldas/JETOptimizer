package dev.jetoptimizer.mixin;

import com.google.common.collect.ImmutableListMultimap;
import dev.jetoptimizer.JETOptimizerProfiler;
import dev.jetoptimizer.RecipeStartupOptimization;
import dev.jetoptimizer.RecipeVisibilityOptimization;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.library.ingredients.RecipeIngredientSupplier.FocusLink;
import mezz.jei.api.gui.builder.IIngredientAcceptor;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IIngredientVisibility;
import mezz.jei.library.config.RecipeCategorySortingConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Consumer;

@Pseudo
@Mixin(targets = "mezz.jei.library.recipes.RecipeManagerInternal", remap = false)
@SuppressWarnings("unused")
abstract class RecipeManagerInternalMixin {
    @Redirect(
        method = "isRecipeVisible(Lmezz/jei/library/recipes/collect/RecipeTypeData;Ljava/lang/Object;Lmezz/jei/api/recipe/IFocusGroup;)Z",
        at = @At(value = "INVOKE", target = "Lmezz/jei/library/ingredients/RecipeIngredientSupplier$FocusLink;getVisibleIngredientIndexes(Lmezz/jei/api/recipe/IFocusGroup;Lmezz/jei/api/runtime/IIngredientManager;Ljava/util/function/Predicate;)Ljava/util/Set;"),
        remap = false
    )
    private Set<Integer> jetoptimizer$checkAnyVisibleCombination(
            FocusLink link, IFocusGroup focuses, IIngredientManager manager,
            Predicate<ITypedIngredient<?>> isVisible
    ) {
        if (RecipeStartupOptimization.fastVisibility() && focuses.isEmpty()) {
            JETOptimizerProfiler.recordFastRecipeVisibility();
            return RecipeVisibilityOptimization.hasVisibleCombination(link, isVisible) ? Set.of() : null;
        }
        return link.getVisibleIngredientIndexes(focuses, manager, isVisible);
    }

    @Inject(method = "<init>", at = @At("HEAD"), remap = false)
    private static void jetoptimizer$recordRecipeCategoryCount(
        List<IRecipeCategory<?>> recipeCategories,
        ImmutableListMultimap<RecipeType<?>, Consumer<IIngredientAcceptor<?>>> recipeCatalysts,
        IIngredientManager ingredientManager,
        RecipeCategorySortingConfig recipeCategorySortingConfig,
        IIngredientVisibility ingredientVisibility,
        CallbackInfo callbackInfo
    ) {
        JETOptimizerProfiler.recordRecipeCategoryCount(recipeCategories.size());
        JETOptimizerProfiler.beginStage("Recipe registry construction");
    }

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void jetoptimizer$finishRecipeRegistryConstruction(
        List<IRecipeCategory<?>> recipeCategories,
        ImmutableListMultimap<RecipeType<?>, Consumer<IIngredientAcceptor<?>>> recipeCatalysts,
        IIngredientManager ingredientManager,
        RecipeCategorySortingConfig recipeCategorySortingConfig,
        IIngredientVisibility ingredientVisibility,
        CallbackInfo callbackInfo
    ) {
        JETOptimizerProfiler.finishStage("Recipe registry construction");
    }

    @Inject(method = "addPlugins", at = @At("HEAD"), remap = false)
    private void jetoptimizer$beginAdvancedPluginWiring(CallbackInfo callbackInfo) {
        JETOptimizerProfiler.beginStage("Advanced recipe-manager plugin wiring");
    }

    @Inject(method = "addPlugins", at = @At("RETURN"), remap = false)
    private void jetoptimizer$finishAdvancedPluginWiring(CallbackInfo callbackInfo) {
        JETOptimizerProfiler.finishStage("Advanced recipe-manager plugin wiring");
    }

    @Inject(method = "compact", at = @At("HEAD"), remap = false)
    private void jetoptimizer$beginRecipeMapCompaction(CallbackInfo callbackInfo) {
        JETOptimizerProfiler.beginStage("Recipe map compaction");
    }

    @Inject(method = "compact", at = @At("RETURN"), remap = false)
    private void jetoptimizer$finishRecipeMapCompaction(CallbackInfo callbackInfo) {
        JETOptimizerProfiler.finishStage("Recipe map compaction");
    }
}
