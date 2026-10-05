package dev.jetoptimizer.mixin;

import dev.jetoptimizer.JETOptimizerProfiler;
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
    }

    private static void recordIngredientCount(IJeiRuntime runtime) {
        int count = 0;
        for (IIngredientType<?> type : runtime.getIngredientManager().getRegisteredIngredientTypes()) {
            count += ingredientCount(runtime, type);
        }
        JETOptimizerProfiler.recordIngredientCount(count);
    }

    private static <T> int ingredientCount(IJeiRuntime runtime, IIngredientType<T> type) {
        return runtime.getIngredientManager().getAllIngredients(type).size();
    }
}
