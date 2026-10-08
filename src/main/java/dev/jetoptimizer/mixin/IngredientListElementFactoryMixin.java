package dev.jetoptimizer.mixin;

import dev.jetoptimizer.JETOptimizerProfiler;
import mezz.jei.api.helpers.IModIdHelper;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.common.config.IIngredientFilterConfig;
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
    private static void jetoptimizer$finishIngredientList(
        IIngredientManager ingredientManager,
        IIngredientFilterConfig config,
        IModIdHelper modIdHelper,
        CallbackInfoReturnable<?> callbackInfo
    ) {
        JETOptimizerProfiler.finishStage("Ingredient list construction");
        int rawCount = countRawIngredients(ingredientManager);
        int typedCount = countTypedIngredients(ingredientManager);
        int listCount = ((java.util.List<?>) callbackInfo.getReturnValue()).size();
        JETOptimizerProfiler.recordIngredientCountsAtGuiBuild(rawCount, typedCount, listCount);
    }

    private static int countRawIngredients(IIngredientManager ingredientManager) {
        return ingredientManager.getRegisteredIngredientTypes().stream()
                .mapToInt(type -> rawIngredientCount(ingredientManager, type)).sum();
    }

    private static int countTypedIngredients(IIngredientManager ingredientManager) {
        return ingredientManager.getRegisteredIngredientTypes().stream()
                .mapToInt(type -> typedIngredientCount(ingredientManager, type)).sum();
    }

    private static <T> int rawIngredientCount(IIngredientManager manager, IIngredientType<T> type) {
        return manager.getAllIngredients(type).size();
    }

    private static <T> int typedIngredientCount(IIngredientManager manager, IIngredientType<T> type) {
        return manager.getAllTypedIngredients(type).size();
    }
}
