package dev.jetoptimizer.mixin;

import com.google.common.collect.ImmutableListMultimap;
import dev.jetoptimizer.JETOptimizerProfiler;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.function.Consumer;

@Pseudo
@Mixin(targets = "mezz.jei.library.recipes.RecipeManagerInternal", remap = false)
@SuppressWarnings("unused")
abstract class RecipeManagerInternalMixin {
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
    }
}
