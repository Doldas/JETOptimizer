package dev.jetoptimizer.mixin;

import dev.jetoptimizer.JETOptimizerProfiler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Pseudo
@Mixin(targets = "mezz.jei.modshade.net.mezzdev.bakedsubstring.BakedSubstringIndex$Builder", remap = false)
@SuppressWarnings({"unused", "MismatchedQueryAndUpdateOfCollection"})
abstract class BakedSubstringIndexBuilderMixin {
    @Shadow
    @Final
    private List<String> keys;

    @Inject(method = "build", at = @At("HEAD"), remap = false)
    private void jetoptimizer$beginGramIndexBuild(CallbackInfoReturnable<?> callbackInfo) {
        JETOptimizerProfiler.beginBakedSubstringIndexBuild(keys.size());
    }

    @Inject(method = "build", at = @At("RETURN"), remap = false)
    private void jetoptimizer$finishGramIndexBuild(CallbackInfoReturnable<?> callbackInfo) {
        JETOptimizerProfiler.finishBakedSubstringIndexBuild();
    }
}
