package dev.jetoptimizer.mixin;

import dev.jetoptimizer.JETOptimizerProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "mezz.jei.gui.startup.JeiGuiStarter", remap = false)
@SuppressWarnings("unused")
abstract class JeiGuiStarterMixin {
    @Inject(method = "start", at = @At("HEAD"), remap = false)
    private static void jetoptimizer$beginGuiRuntime(CallbackInfoReturnable<?> callbackInfo) {
        JETOptimizerProfiler.beginStage("JEI GUI runtime construction");
    }

    @Inject(method = "start", at = @At("RETURN"), remap = false)
    private static void jetoptimizer$finishGuiRuntime(CallbackInfoReturnable<?> callbackInfo) {
        JETOptimizerProfiler.finishStage("JEI GUI runtime construction");
    }
}
