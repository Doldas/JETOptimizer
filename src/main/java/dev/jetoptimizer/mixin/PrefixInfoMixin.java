package dev.jetoptimizer.mixin;

import dev.jetoptimizer.JETOptimizerProfiler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;

@Pseudo
@Mixin(targets = "mezz.jei.common.search.PrefixInfo", remap = false)
@SuppressWarnings("unused")
abstract class PrefixInfoMixin {
    @Shadow
    @Final
    private String id;

    @Inject(method = "createStorageBuilder", at = @At("HEAD"), remap = false)
    private void jetoptimizer$setCurrentPrefix(CallbackInfoReturnable<?> callbackInfo) {
        JETOptimizerProfiler.setCurrentSearchPrefix(id);
    }

    @Inject(method = "getStrings", at = @At("HEAD"), remap = false)
    private void jetoptimizer$beginStringSource(CallbackInfoReturnable<?> callbackInfo) {
        JETOptimizerProfiler.beginSearchStringSource(id);
    }

    @Inject(method = "getStrings", at = @At("RETURN"), remap = false)
    private void jetoptimizer$finishStringSource(CallbackInfoReturnable<Collection<String>> callbackInfo) {
        Collection<String> strings = callbackInfo.getReturnValue();
        JETOptimizerProfiler.finishSearchStringSource(id, strings == null ? 0 : strings.size());
    }
}
