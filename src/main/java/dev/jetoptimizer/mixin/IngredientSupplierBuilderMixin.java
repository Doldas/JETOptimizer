package dev.jetoptimizer.mixin;

import dev.jetoptimizer.RecipeLayoutBuilderPool;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.library.gui.recipes.supplier.builder.IngredientSlotBuilder;
import mezz.jei.library.gui.recipes.supplier.builder.IngredientSupplierBuilder;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Map;

@Pseudo
@Mixin(targets = "mezz.jei.library.gui.recipes.supplier.builder.IngredientSupplierBuilder", remap = false)
@SuppressWarnings("unused")
abstract class IngredientSupplierBuilderMixin {
    @Shadow
    @Final
    private IIngredientManager ingredientManager;

    @Shadow
    @Final
    private Map<?, List<IngredientSlotBuilder>> ingredientSlotBuilders;

    @Shadow
    @Final
    private List<List<IngredientSlotBuilder>> focusLinkedSlots;

    @Inject(method = "buildIngredientSupplier", at = @At("RETURN"), remap = false)
    private void jetoptimizer$recycleRecipeLayoutBuilder(CallbackInfoReturnable<?> callbackInfo) {
        RecipeLayoutBuilderPool.recycle(
                (IngredientSupplierBuilder) (Object) this,
                ingredientManager,
                ingredientSlotBuilders,
                focusLinkedSlots
        );
    }
}
