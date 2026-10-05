package dev.jetoptimizer.mixin;

import dev.jetoptimizer.JETOptimizerProfiler;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.library.recipes.RecipeManagerInternal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

@Pseudo
@Mixin(targets = "mezz.jei.library.load.registration.RecipeRegistration", remap = false)
@SuppressWarnings({"unused", "rawtypes", "unchecked"})
abstract class RecipeRegistrationMixin {
    @Redirect(
        method = "addRecipes",
        at = @At(
            value = "INVOKE",
            target = "Lmezz/jei/library/recipes/RecipeManagerInternal;addRecipes(Lmezz/jei/api/recipe/RecipeType;Ljava/util/List;)V",
            remap = false
        ),
        remap = false
    )
    private static void jetoptimizer$timeRecipeBatch(RecipeManagerInternal recipeManager, RecipeType recipeType, List recipes) {
        long startedAt = JETOptimizerProfiler.beginRecipeAddBatch(recipes.size());
        try {
            recipeManager.addRecipes(recipeType, recipes);
        } finally {
            JETOptimizerProfiler.finishRecipeAddBatch(startedAt);
        }
    }
}
