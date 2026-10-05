package dev.jetoptimizer.mixin;

import dev.jetoptimizer.JETOptimizerProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "mezz.jei.gui.ingredients.IngredientSorter", remap = false)
@SuppressWarnings("unused")
abstract class IngredientSorterMixin {
    @Inject(method = "sortIngredients", at = @At("HEAD"), remap = false)
    private static void jetoptimizer$beginSort(CallbackInfoReturnable<?> callbackInfo) {
        JETOptimizerProfiler.beginStage("Ingredient sorting");
    }

    @Inject(method = "sortIngredients", at = @At("RETURN"), remap = false)
    private static void jetoptimizer$finishSort(CallbackInfoReturnable<?> callbackInfo) {
        JETOptimizerProfiler.finishStage("Ingredient sorting");
    }
}
