package dev.jetoptimizer.mixin;

import dev.jetoptimizer.JETOptimizerProfiler;
import mezz.jei.api.IModPlugin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.function.Consumer;

@Pseudo
@Mixin(targets = "mezz.jei.library.load.PluginCaller", remap = false)
@SuppressWarnings("unused")
abstract class PluginCallerMixin {
    @Inject(method = "callOnPlugins", at = @At("HEAD"), remap = false)
    private static void jetoptimizer$beginPluginPhase(String title, List<IModPlugin> plugins, Consumer<IModPlugin> callback, CallbackInfo callbackInfo) {
        JETOptimizerProfiler.beginPluginPhase(title);
    }

    @Inject(method = "callOnPlugins", at = @At("RETURN"), remap = false)
    private static void jetoptimizer$finishPluginPhase(String title, List<IModPlugin> plugins, Consumer<IModPlugin> callback, CallbackInfo callbackInfo) {
        JETOptimizerProfiler.finishPluginPhase(title);
    }

    @Redirect(
        method = "callOnPlugins",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V",
            remap = false
        ),
        remap = false
    )
    private static void jetoptimizer$timePluginCallback(Consumer<Object> callback, Object pluginObject) {
        if (!JETOptimizerProfiler.isPluginProfilingActive() || !(pluginObject instanceof IModPlugin plugin)) {
            callback.accept(pluginObject);
            return;
        }

        String pluginUid;
        try {
            pluginUid = plugin.getPluginUid().toString();
        } catch (RuntimeException | LinkageError ignored) {
            pluginUid = plugin.getClass().getName();
        }
        long startedAt = System.nanoTime();
        try {
            callback.accept(pluginObject);
        } finally {
            JETOptimizerProfiler.recordPluginCallback(pluginUid, System.nanoTime() - startedAt);
        }
    }
}
