package dev.jetoptimizer.mixin;

import dev.jetoptimizer.JETOptimizerProfiler;
import dev.jetoptimizer.RecipeLayoutBuilderPool;
import dev.jetoptimizer.RecipeStartupOptimization;
import dev.jetoptimizer.TooltipSearchOptimization;
import dev.jetoptimizer.PersistentSearchIndexCache;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.common.Internal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "mezz.jei.library.startup.JeiStarter", remap = false)
@SuppressWarnings("unused")
abstract class JeiStarterMixin {
    @Inject(method = "start", at = @At("HEAD"), remap = false)
    private void jetoptimizer$beginExperimentalRuntimeOptions(CallbackInfo callbackInfo) {
        TooltipSearchOptimization.beginRuntime();
        PersistentSearchIndexCache.beginRuntime();
        RecipeStartupOptimization.reset();
        RecipeLayoutBuilderPool.clear();
    }

    @Inject(method = "stop", at = @At("HEAD"), remap = false)
    private void jetoptimizer$clearExperimentalRuntimeOptions(CallbackInfo callbackInfo) {
        TooltipSearchOptimization.endRuntime();
        RecipeStartupOptimization.reset();
        RecipeLayoutBuilderPool.clear();
    }

    @Inject(
            method = "start",
            at = @At(
                    value = "INVOKE",
                    target = "Lmezz/jei/common/util/LoggedTimer;start(Ljava/lang/String;)V",
                    ordinal = 0,
                    shift = At.Shift.AFTER,
                    remap = false
            ),
            remap = false
    )
    private void jetoptimizer$beginProfile(CallbackInfo callbackInfo) {
        JETOptimizerProfiler.beginJeiStartup();
    }

    @Inject(
            method = "start",
            at = @At(
                    value = "INVOKE",
                    target = "Lmezz/jei/common/util/LoggedTimer;stop()V",
                    ordinal = 1,
                    shift = At.Shift.AFTER,
                    remap = false
            ),
            remap = false
    )
    private void jetoptimizer$finishProfile(CallbackInfo callbackInfo) {
        long finishedAt = System.nanoTime();
        Internal.getOptionalJeiRuntime().ifPresent(JeiStarterMixin::recordIngredientCount);
        JETOptimizerProfiler.finishJeiStartup(finishedAt);
        PersistentSearchIndexCache.reportRuntime();
    }

    private static void recordIngredientCount(IJeiRuntime runtime) {
        int rawCount = 0;
        int typedCount = 0;
        for (IIngredientType<?> type : runtime.getIngredientManager().getRegisteredIngredientTypes()) {
            rawCount += rawIngredientCount(runtime, type);
            typedCount += typedIngredientCount(runtime, type);
        }
        JETOptimizerProfiler.recordFinalIngredientCounts(rawCount, typedCount);
    }

    private static <T> int rawIngredientCount(IJeiRuntime runtime, IIngredientType<T> type) {
        return runtime.getIngredientManager().getAllIngredients(type).size();
    }

    private static <T> int typedIngredientCount(IJeiRuntime runtime, IIngredientType<T> type) {
        return runtime.getIngredientManager().getAllTypedIngredients(type).size();
    }
}
