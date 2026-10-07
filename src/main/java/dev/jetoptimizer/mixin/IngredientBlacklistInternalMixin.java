package dev.jetoptimizer.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.jetoptimizer.RuntimeRemovalVisibilityBatch;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.library.ingredients.IngredientVisibility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.At;

import java.lang.ref.WeakReference;
import java.util.Collection;

@Pseudo
@Mixin(targets = "mezz.jei.library.ingredients.IngredientBlacklistInternal", remap = false)
@SuppressWarnings("unused")
abstract class IngredientBlacklistInternalMixin {
    @Shadow
    private WeakReference<IngredientVisibility> ingredientVisibilityRef;

    @WrapMethod(method = "onIngredientsRemoved", remap = false, require = 1)
    private <T> void jetoptimizer$batchRemovalVisibilityNotifications(
            IIngredientHelper<T> ingredientHelper,
            Collection<ITypedIngredient<T>> ingredients,
            Operation<Void> original
    ) {
        RuntimeRemovalVisibilityBatch.Batch previous = RuntimeRemovalVisibilityBatch.current();
        RuntimeRemovalVisibilityBatch.Batch batch = RuntimeRemovalVisibilityBatch.begin(
                ingredientVisibilityRef.get(),
                ingredients.size()
        );
        if (batch == null) {
            RuntimeRemovalVisibilityBatch.activate(null);
            try {
                original.call(ingredientHelper, ingredients);
            } finally {
                RuntimeRemovalVisibilityBatch.restore(previous);
            }
            return;
        }

        RuntimeRemovalVisibilityBatch.activate(batch);
        Throwable originalFailure = null;
        try {
            original.call(ingredientHelper, ingredients);
        } catch (RuntimeException | Error failure) {
            originalFailure = failure;
            throw failure;
        } finally {
            RuntimeRemovalVisibilityBatch.restore(previous);
            try {
                batch.finish();
            } catch (RuntimeException | Error notificationFailure) {
                if (originalFailure != null) {
                    originalFailure.addSuppressed(notificationFailure);
                } else {
                    throw notificationFailure;
                }
            }
        }
    }

    @WrapOperation(
        method = "onIngredientsRemoved",
        at = @At(value = "INVOKE", target =
            "Lmezz/jei/library/ingredients/IngredientBlacklistInternal;notifyListenersOfVisibilityChange(Lmezz/jei/api/ingredients/ITypedIngredient;Ljava/util/Collection;Z)V", remap = false),
        remap = false,
        require = 1
    )
    private static <T> void jetoptimizer$collectRemovalVisibilityChange(
        @Coerce Object blacklist,
        ITypedIngredient<T> ingredient,
        Collection<UidContext> contexts,
        boolean visible,
        Operation<Void> original
    ) {
        RuntimeRemovalVisibilityBatch.Batch batch = RuntimeRemovalVisibilityBatch.current();
        if (batch == null || !batch.collect(ingredient, contexts, visible)) {
            original.call(blacklist, ingredient, contexts, visible);
        }
    }
}
