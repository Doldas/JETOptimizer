package dev.jetoptimizer.mixin;

import dev.jetoptimizer.JETOptimizerProfiler;
import dev.jetoptimizer.SearchTextOptimization;
import mezz.jei.api.ingredients.ITypedIngredient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/**
 * Attributes JEI's per-ingredient tooltip search-string extraction to the ingredient type it came from.
 * The tooltip stage is the single largest identified startup region, and this shows whether it is
 * diffuse or dominated by a few ingredient types, which is what decides if it can be attacked at all.
 *
 * <p>The uid is resolved once and reused by the return hook: {@code getTooltipStrings} runs once per
 * ingredient, so walking the typed ingredient twice would double the cost of the only bookkeeping
 * this mixin does.
 */
@Pseudo
@Mixin(targets = "mezz.jei.gui.ingredients.ListElementInfo", remap = false)
@SuppressWarnings("unused")
abstract class ListElementInfoMixin<V> {
    @Shadow
    public abstract ITypedIngredient<V> getTypedIngredient();

    @Unique
    private String jetoptimizer$ingredientTypeUid;

    @Inject(method = "getTooltipStrings", at = @At("HEAD"), remap = false)
    private void jetoptimizer$beginTooltipStrings(CallbackInfoReturnable<?> callbackInfo) {
        jetoptimizer$ingredientTypeUid = ingredientTypeUid();
        JETOptimizerProfiler.beginTooltipStringSource(jetoptimizer$ingredientTypeUid);
    }

    @Inject(method = "getTooltipStrings", at = @At("RETURN"), remap = false)
    private void jetoptimizer$finishTooltipStrings(CallbackInfoReturnable<?> callbackInfo) {
        JETOptimizerProfiler.finishTooltipStringSource();
    }

    private String ingredientTypeUid() {
        try {
            ITypedIngredient<V> typedIngredient = getTypedIngredient();
            if (typedIngredient == null) {
                return "unknown";
            }
            return typedIngredient.getType().getUid();
        } catch (RuntimeException | LinkageError e) {
            return "unknown";
        }
    }

    /**
     * {@code getStrings} is the whole pure-text part of tooltip search-word construction: rendering
     * the tooltip already happened, so what is left is {@code getString}, formatting removal,
     * lowercasing, whitespace splitting and set insertion. Timing it separates JEI's own pure work
     * from the mod-supplied tooltip rendering, which is what bounds how much any search-text
     * optimization can possibly save.
     */
    @Inject(method = "getStrings", at = @At("HEAD"), remap = false)
    private static void jetoptimizer$beginSearchTextPipeline(CallbackInfoReturnable<?> callbackInfo) {
        JETOptimizerProfiler.beginSearchTextPipeline();
    }

    @Inject(method = "getStrings", at = @At("RETURN"), remap = false)
    private static void jetoptimizer$finishSearchTextPipeline(CallbackInfoReturnable<?> callbackInfo) {
        JETOptimizerProfiler.finishSearchTextPipeline();
    }

    /**
     * Replaces the {@code \s+} split regex applied to every tooltip line. Fail-open: while the
     * optimization is disabled, or if the replacement fails, the injection returns without
     * cancelling so JEI's original body runs unchanged.
     */
    @Inject(method = "addSplitStrings", at = @At("HEAD"), cancellable = true, remap = false)
    private static void jetoptimizer$fastSplitWhitespace(
        Set<String> result,
        String string,
        CallbackInfo callbackInfo
    ) {
        if (!SearchTextOptimization.enabled() || string == null) {
            return;
        }
        JETOptimizerProfiler.beginFastTextSplit();
        try {
            SearchTextOptimization.splitOnWhitespace(result, string);
            JETOptimizerProfiler.finishFastTextSplit(true);
            callbackInfo.cancel();
        } catch (RuntimeException | LinkageError e) {
            JETOptimizerProfiler.finishFastTextSplit(false);
            JETOptimizerProfiler.recordOptimizationFallback("whitespace splitting");
        }
    }
}
