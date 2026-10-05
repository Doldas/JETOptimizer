package dev.jetoptimizer.mixin;

import dev.jetoptimizer.JETOptimizerProfiler;
import mezz.jei.api.recipe.RecipeIngredientRole;
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

    @Inject(method = "addRecipe", at = @At("HEAD"), remap = false)
    private void jetoptimizer$beginRecipeMapInsertion(CallbackInfo callbackInfo) {
        jetoptimizer$recipeMapStartedAt = JETOptimizerProfiler.beginRecipeMapInsert(role.ordinal());
    }

    @Inject(method = "addRecipe", at = @At("RETURN"), remap = false)
    private void jetoptimizer$finishRecipeMapInsertion(CallbackInfo callbackInfo) {
        long startedAt = jetoptimizer$recipeMapStartedAt;
        jetoptimizer$recipeMapStartedAt = Long.MIN_VALUE;
        JETOptimizerProfiler.finishRecipeMapInsert(role.ordinal(), startedAt);
    }
}
