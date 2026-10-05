package dev.jetoptimizer.mixin;

import dev.jetoptimizer.JETOptimizerProfiler;
import dev.jetoptimizer.SearchTextOptimization;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Replaces the chat-format stripping regex that JEI runs on every tooltip line of every ingredient.
 *
 * <p>{@code removeChatFormatting} is only reached from search-word construction
 * ({@code ListElementInfo.getStrings}, {@code ListElementInfo} creative tab names and
 * {@code DisplayNameUtil}), so this changes search text only and never anything that is rendered.
 *
 * <p>The injection is fail-open three ways: it does nothing while the optimization is disabled, it
 * does not intercept {@code null} (JEI's own method returns {@code null} for it), and any failure
 * inside the replacement leaves the return value unset so JEI's original implementation runs.
 */
@Pseudo
@Mixin(targets = "mezz.jei.common.util.StringUtil", remap = false)
@SuppressWarnings("unused")
abstract class StringUtilMixin {
    @Inject(method = "removeChatFormatting", at = @At("HEAD"), cancellable = true, remap = false)
    private static void jetoptimizer$fastStripChatFormatting(
        String string,
        CallbackInfoReturnable<String> callbackInfo
    ) {
        if (!SearchTextOptimization.enabled() || string == null) {
            return;
        }
        JETOptimizerProfiler.beginFastTextStrip();
        try {
            String stripped = SearchTextOptimization.stripFormatting(string);
            JETOptimizerProfiler.finishFastTextStrip(true);
            callbackInfo.setReturnValue(stripped);
        } catch (RuntimeException | LinkageError e) {
            JETOptimizerProfiler.finishFastTextStrip(false);
            JETOptimizerProfiler.recordOptimizationFallback("chat-format stripping");
        }
    }
}