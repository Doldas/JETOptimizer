package dev.jetoptimizer;

import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.library.ingredients.RecipeIngredientSupplier.FocusLink;

import java.util.List;
import java.util.function.Predicate;

/**
 * Existential counterpart of FocusLink.getVisibleIngredientIndexes for EMPTY focuses only.
 * The manager discards the returned indexes and tests only for null. Do not use this for layout
 * drawing: layouts need every index, including JEI's empty-set sentinel for unrestricted slots.
 */
public final class RecipeVisibilityOptimization {
    private RecipeVisibilityOptimization() {}

    public static boolean hasVisibleCombination(FocusLink link, Predicate<ITypedIngredient<?>> isVisible) {
        List<FocusLink.Slot> slots = link.slots();
        if (slots.isEmpty()) return true;
        int candidates = slots.getFirst().ingredients().size();
        // JEI returns an empty set (not null) for an empty candidate range.
        if (candidates == 0) return true;

        for (int index = 0; index < candidates; index++) {
            boolean visible = true;
            for (FocusLink.Slot slot : slots) {
                List<ITypedIngredient<?>> ingredients = slot.ingredients();
                if (index >= ingredients.size()) {
                    visible = false;
                    break;
                }
                ITypedIngredient<?> ingredient = ingredients.get(index);
                // A null entry represents a blank display ingredient, which is allowed by JEI.
                if (ingredient != null && !isVisible.test(ingredient)) {
                    visible = false;
                    break;
                }
            }
            if (visible) return true;
        }
        return false;
    }
}
