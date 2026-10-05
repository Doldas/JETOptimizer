package dev.jetoptimizer.mixin;

import dev.jetoptimizer.JETOptimizerProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "mezz.jei.gui.ingredients.IngredientFilter", remap = false)
@SuppressWarnings("unused")
abstract class IngredientFilterMixin {
    @Inject(method = "<init>", at = @At("HEAD"), remap = false)
    private static void jetoptimizer$beginIngredientFilter(CallbackInfo callbackInfo) {
        JETOptimizerProfiler.beginStage("Ingredient filter construction");
    }

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void jetoptimizer$finishIngredientFilter(CallbackInfo callbackInfo) {
        JETOptimizerProfiler.finishStage("Ingredient filter construction");
    }

    @Inject(method = "createElementSearch", at = @At("HEAD"), remap = false)
    private static void jetoptimizer$beginSearchIndex(CallbackInfoReturnable<?> callbackInfo) {
        JETOptimizerProfiler.beginStage("Ingredient search index construction");
    }

    @Inject(method = "createElementSearch", at = @At("RETURN"), remap = false)
    private static void jetoptimizer$finishSearchIndex(CallbackInfoReturnable<?> callbackInfo) {
        JETOptimizerProfiler.finishStage("Ingredient search index construction");
    }
}
