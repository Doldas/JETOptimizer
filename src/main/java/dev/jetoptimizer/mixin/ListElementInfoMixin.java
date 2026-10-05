package dev.jetoptimizer.mixin;

import dev.jetoptimizer.JETOptimizerProfiler;
import mezz.jei.api.ingredients.ITypedIngredient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Attributes JEI's per-ingredient tooltip search-string extraction to the ingredient type it came from.
 * The tooltip stage is the single largest identified startup region, and this shows whether it is
 * diffuse or dominated by a few ingredient types, which is what decides if it can be attacked at all.
 */
@Pseudo
@Mixin(targets = "mezz.jei.gui.ingredients.ListElementInfo", remap = false)
@SuppressWarnings("unused")
abstract class ListElementInfoMixin<V> {
    @Shadow
    public abstract ITypedIngredient<V> getTypedIngredient();

    @Inject(method = "getTooltipStrings", at = @At("HEAD"), remap = false)
    private void jetoptimizer$beginTooltipStrings(CallbackInfoReturnable<?> callbackInfo) {
        JETOptimizerProfiler.beginTooltipStringSource(ingredientTypeUid());
    }

    @Inject(method = "getTooltipStrings", at = @At("RETURN"), remap = false)
    private void jetoptimizer$finishTooltipStrings(CallbackInfoReturnable<?> callbackInfo) {
        JETOptimizerProfiler.finishTooltipStringSource(ingredientTypeUid());
    }

    private String ingredientTypeUid() {
        try {
            ITypedIngredient<V> typedIngredient = getTypedIngredient();
            if (typedIngredient == null || typedIngredient.getType() == null) {
                return "unknown";
            }
            return typedIngredient.getType().getUid();
        } catch (RuntimeException | LinkageError e) {
            return "unknown";
        }
    }
}
