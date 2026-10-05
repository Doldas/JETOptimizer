package dev.jetoptimizer.mixin;

import dev.jetoptimizer.JETOptimizerProfiler;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
@SuppressWarnings("unused")
abstract class ClientPacketListenerMixin {
    @Inject(method = "handleUpdateRecipes", at = @At("HEAD"))
    private void jetoptimizer$markRecipePacketStart(CallbackInfo callbackInfo) {
        JETOptimizerProfiler.recipePacketStarted();
    }
}
