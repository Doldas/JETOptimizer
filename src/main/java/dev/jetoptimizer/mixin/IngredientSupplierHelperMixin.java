package dev.jetoptimizer.mixin;

import dev.jetoptimizer.JETOptimizerProfiler;
import dev.jetoptimizer.RecipeLayoutBuilderPool;
import dev.jetoptimizer.PersistentRecipeCache;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.library.ingredients.RecipeIngredientSupplier;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.library.gui.recipes.supplier.builder.IngredientSupplierBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "mezz.jei.library.util.IngredientSupplierHelper", remap = false)
@SuppressWarnings("unused")
abstract class IngredientSupplierHelperMixin {
    @Redirect(
            method = "getIngredientSupplier",
            at = @At(
                    value = "NEW",
                    target = "Lmezz/jei/library/gui/recipes/supplier/builder/IngredientSupplierBuilder;"
            ),
            remap = false
    )
    private static IngredientSupplierBuilder jetoptimizer$acquireRecipeLayoutBuilder(IIngredientManager ingredientManager) {
        return RecipeLayoutBuilderPool.acquire(ingredientManager);
    }

    @Inject(method = "getIngredientSupplier", at = @At("HEAD"), cancellable = true, remap = false)
    private static <T> void jetoptimizer$beginCategoryRecipeLayout(T recipe, IRecipeCategory<T> category,
            IIngredientManager manager, CallbackInfoReturnable<RecipeIngredientSupplier> callbackInfo) {
        JETOptimizerProfiler.beginRecipeLayoutBuild();
        RecipeIngredientSupplier cached = PersistentRecipeCache.lookup(recipe, category);
        if (cached != null) {
            JETOptimizerProfiler.finishRecipeLayoutBuild();
            callbackInfo.setReturnValue(cached);
        }
    }

    @Inject(method = "getIngredientSupplier", at = @At("RETURN"), remap = false)
    private static <T> void jetoptimizer$finishCategoryRecipeLayout(T recipe, IRecipeCategory<T> category,
            IIngredientManager manager, CallbackInfoReturnable<RecipeIngredientSupplier> callbackInfo) {
        PersistentRecipeCache.capture(recipe, category, callbackInfo.getReturnValue());
        JETOptimizerProfiler.finishRecipeLayoutBuild();
    }
}
