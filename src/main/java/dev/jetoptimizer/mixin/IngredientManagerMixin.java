package dev.jetoptimizer.mixin;

import dev.jetoptimizer.JETOptimizerProfiler;
import mezz.jei.api.ingredients.IIngredientType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;

@Pseudo
@Mixin(targets = "mezz.jei.library.ingredients.IngredientManager", remap = false)
@SuppressWarnings("unused")
abstract class IngredientManagerMixin {
    @Inject(method = "addIngredientsAtRuntime", at = @At("HEAD"), remap = false)
    private <T> void jetoptimizer$countRuntimeAdds(IIngredientType<T> type, Collection<T> ingredients, CallbackInfo callbackInfo) {
        if (ingredients != null) {
            JETOptimizerProfiler.recordRuntimeIngredientMutation(true, ingredients.size());
        }
    }

    @Inject(method = "removeIngredientsAtRuntime", at = @At("HEAD"), remap = false)
    private <T> void jetoptimizer$countRuntimeRemovals(IIngredientType<T> type, Collection<T> ingredients, CallbackInfo callbackInfo) {
        if (ingredients != null) {
            JETOptimizerProfiler.recordRuntimeIngredientMutation(false, ingredients.size());
        }
    }
}
