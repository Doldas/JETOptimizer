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

/** Avoids nested per-slot stream pipelines while retaining JEI's immutable snapshot constructors. */
public final class RecipeSupplierOptimization {
    private RecipeSupplierOptimization() {}

    public static RecipeIngredientSupplier build(
            Map<RecipeIngredientRole, List<IngredientSlotBuilder>> slotsByRole,
            List<List<IngredientSlotBuilder>> linkedSlots
    ) {
        Map<RecipeIngredientRole, List<ITypedIngredient<?>>> ingredientsByRole =
                new EnumMap<>(RecipeIngredientRole.class);
        for (var entry : slotsByRole.entrySet()) {
            List<ITypedIngredient<?>> ingredients = new ArrayList<>();
            for (IngredientSlotBuilder slot : entry.getValue()) {
                for (ITypedIngredient<?> ingredient : slot.getAllIngredients()) {
                    if (ingredient != null) ingredients.add(ingredient);
                }
            }
            ingredientsByRole.put(entry.getKey(), ingredients);
        }
        List<FocusLink> links = new ArrayList<>(linkedSlots.size());
        for (List<IngredientSlotBuilder> linked : linkedSlots) {
            List<FocusLink.Slot> slots = new ArrayList<>(linked.size());
            for (IngredientSlotBuilder slot : linked) {
                // Slot copies the list INCLUDING nulls, preserving linked rotation positions.
                slots.add(new FocusLink.Slot(slot.getRole(), slot.getAllIngredients()));
            }
            links.add(new FocusLink(slots));
        }
        return new RecipeIngredientSupplier(ingredientsByRole, links);
    }
}
