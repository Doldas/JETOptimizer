package dev.jetoptimizer.mixin;

import dev.jetoptimizer.JETOptimizerProfiler;
import dev.jetoptimizer.PersistentSearchIndexCache;
import mezz.jei.modshade.net.mezzdev.bakedsubstring.BakedSubstringIndex;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
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

    @Shadow
    @Final
    private List<Object> values;

    @Unique
    private PersistentSearchIndexCache.Lookup jetoptimizer$cacheLookup;

    @Inject(method = "build", at = @At("HEAD"), cancellable = true, remap = false)
    private void jetoptimizer$beginGramIndexBuild(CallbackInfoReturnable<BakedSubstringIndex<?>> callbackInfo) {
        JETOptimizerProfiler.beginBakedSubstringIndexBuild(keys.size());
        jetoptimizer$cacheLookup = PersistentSearchIndexCache.lookup(keys, values);
        if (jetoptimizer$cacheLookup != null && jetoptimizer$cacheLookup.result() != null) {
            BakedSubstringIndex<?> restored = jetoptimizer$cacheLookup.result();
            jetoptimizer$cacheLookup = null;
            JETOptimizerProfiler.finishBakedSubstringIndexBuild();
            callbackInfo.setReturnValue(restored);
        }
    }

    @Inject(method = "build", at = @At("RETURN"), remap = false)
    private void jetoptimizer$finishGramIndexBuild(CallbackInfoReturnable<BakedSubstringIndex<?>> callbackInfo) {
        PersistentSearchIndexCache.capture(jetoptimizer$cacheLookup, callbackInfo.getReturnValue());
        jetoptimizer$cacheLookup = null;
        JETOptimizerProfiler.finishBakedSubstringIndexBuild();
    }
}
