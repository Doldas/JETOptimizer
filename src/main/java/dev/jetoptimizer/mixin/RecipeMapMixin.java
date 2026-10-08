package dev.jetoptimizer.mixin;

import dev.jetoptimizer.JETOptimizerProfiler;
import dev.jetoptimizer.RecipeStartupOptimization;
import dev.jetoptimizer.PersistentRecipeCache;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.ingredients.subtypes.UidContext;
import org.spongepowered.asm.mixin.injection.Redirect;
import mezz.jei.api.ingredients.IIngredientSupplier;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.library.ingredients.RecipeIngredientSupplier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "mezz.jei.library.recipes.collect.RecipeMap", remap = false)
@SuppressWarnings("unused")
abstract class RecipeMapMixin {
    @Shadow
    @Final
    private RecipeIngredientRole role;

    @Unique
    private long jetoptimizer$recipeMapStartedAt = Long.MIN_VALUE;

    @Inject(method = "addRecipe", at = @At("HEAD"), cancellable = true, remap = false)
    private <T> void jetoptimizer$beginRecipeMapInsertion(
            RecipeType<T> recipeType, T recipe, IIngredientSupplier supplier, CallbackInfo callbackInfo
    ) {
        jetoptimizer$recipeMapStartedAt = JETOptimizerProfiler.beginRecipeMapInsert(role.ordinal());
        if (RecipeStartupOptimization.fastSuppliers()
                && supplier.getClass() == RecipeIngredientSupplier.class
                && supplier.getIngredients(role).isEmpty()) {
            // No relationships exist for this role. Avoid a temporary IngredientUidIndex and
            // its maps/lambda, while still counting this invocation in the existing profile.
            JETOptimizerProfiler.recordEmptyRecipeRole();
            jetoptimizer$finishRecipeMapInsertion(callbackInfo);
            callbackInfo.cancel();
        }
    }

    @Redirect(method = "addRecipeIngredient", at = @At(value = "INVOKE",
            target = "Lmezz/jei/api/ingredients/IIngredientHelper;getUid(Lmezz/jei/api/ingredients/ITypedIngredient;Lmezz/jei/api/ingredients/subtypes/UidContext;)Ljava/lang/Object;"), remap = false)
    private <T> Object jetoptimizer$reuseSharedRecipeUid(IIngredientHelper<T> helper, ITypedIngredient<T> ingredient, UidContext context) {
        return PersistentRecipeCache.recipeUid(helper, ingredient, context);
    }

    @Inject(method = "addRecipe", at = @At("RETURN"), remap = false)
    private void jetoptimizer$finishRecipeMapInsertion(CallbackInfo callbackInfo) {
        long startedAt = jetoptimizer$recipeMapStartedAt;
        jetoptimizer$recipeMapStartedAt = Long.MIN_VALUE;
        JETOptimizerProfiler.finishRecipeMapInsert(role.ordinal(), startedAt);
    }
}
