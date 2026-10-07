package dev.jetoptimizer.mixin;

import dev.jetoptimizer.JETOptimizerProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Map;

@Pseudo
@Mixin(targets = "mezz.jei.library.ingredients.RegisteredIngredientIndex", remap = false)
@SuppressWarnings("unused")
abstract class RegisteredIngredientIndexMixin {
    @Redirect(
            method = "remove",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;remove(Ljava/lang/Object;)Ljava/lang/Object;", remap = false),
            remap = false,
            require = 1
    )
    private Object jetoptimizer$countRegisteredRemoval(Map<Object, Object> ingredientsByUid, Object uid) {
        Object removed = ingredientsByUid.remove(uid);
        if (removed != null) {
            JETOptimizerProfiler.recordSuccessfulRuntimeIngredientRemoval();
        }
        return removed;
    }
}
