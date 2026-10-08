package dev.jetoptimizer;

import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.library.gui.recipes.supplier.builder.IngredientSlotBuilder;
import mezz.jei.library.ingredients.RecipeIngredientSupplier;
import mezz.jei.library.ingredients.RecipeIngredientSupplier.FocusLink;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bulk slot copies and ordered link streams retain JEI's immutable snapshot ownership. */
public final class RecipeSupplierOptimization {
    private RecipeSupplierOptimization() {}

    public static RecipeIngredientSupplier build(
            Map<RecipeIngredientRole, List<IngredientSlotBuilder>> slotsByRole,
            List<List<IngredientSlotBuilder>> linkedSlots
    ) {
        Map<RecipeIngredientRole, List<ITypedIngredient<?>>> ingredientsByRole =
                new EnumMap<>(RecipeIngredientRole.class);
        slotsByRole.forEach((role, builders) -> {
            List<ITypedIngredient<?>> ingredients = new ArrayList<>();
            builders.stream().map(IngredientSlotBuilder::getAllIngredients).forEach(ingredients::addAll);
            ingredients.removeIf(Objects::isNull);
            ingredientsByRole.put(role, ingredients);
        });
        List<FocusLink> links = linkedSlots.stream()
                .map(linked -> linked.stream()
                        // Slot copies INCLUDING nulls, preserving linked rotation positions.
                        .map(slot -> new FocusLink.Slot(slot.getRole(), slot.getAllIngredients()))
                        .toList())
                .map(FocusLink::new).toList();
        return new RecipeIngredientSupplier(ingredientsByRole, links);
    }
}
