package dev.jetoptimizer.mixin;

import dev.jetoptimizer.JETOptimizerProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "mezz.jei.gui.ingredients.IngredientListElementFactory", remap = false)
@SuppressWarnings("unused")
abstract class IngredientListElementFactoryMixin {
    @Inject(method = "createBaseList", at = @At("HEAD"), remap = false)
    private static void jetoptimizer$beginIngredientList(CallbackInfoReturnable<?> callbackInfo) {
        JETOptimizerProfiler.beginStage("Ingredient list construction");
    }

    @Inject(method = "createBaseList", at = @At("RETURN"), remap = false)
    private static void jetoptimizer$finishIngredientList(CallbackInfoReturnable<?> callbackInfo) {
        JETOptimizerProfiler.finishStage("Ingredient list construction");
    }
}
