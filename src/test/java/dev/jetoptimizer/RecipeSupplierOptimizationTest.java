package dev.jetoptimizer;

import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.library.gui.recipes.supplier.builder.IngredientSlotBuilder;
import mezz.jei.library.gui.recipes.supplier.builder.IngredientSupplierBuilder;
import mezz.jei.library.ingredients.RecipeIngredientSupplier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class RecipeSupplierOptimizationTest {
    static final class SlotFixture extends IngredientSlotBuilder {
        final List<ITypedIngredient<?>> contents;
        SlotFixture(RecipeIngredientRole role, List<ITypedIngredient<?>> contents) {
            super(null, role);
            this.contents = contents;
        }
        @Override public List<ITypedIngredient<?>> getAllIngredients() { return contents; }
    }

    @SuppressWarnings("unchecked")
    static RecipeIngredientSupplier original(
            Map<RecipeIngredientRole, List<IngredientSlotBuilder>> roles,
            List<List<IngredientSlotBuilder>> links
    ) throws ReflectiveOperationException {
        IngredientSupplierBuilder builder = new IngredientSupplierBuilder(null);
        var roleField = IngredientSupplierBuilder.class.getDeclaredField("ingredientSlotBuilders");
        roleField.setAccessible(true);
        ((Map<RecipeIngredientRole, List<IngredientSlotBuilder>>) roleField.get(builder)).putAll(roles);
        var linkField = IngredientSupplierBuilder.class.getDeclaredField("focusLinkedSlots");
        linkField.setAccessible(true);
        ((List<List<IngredientSlotBuilder>>) linkField.get(builder)).addAll(links);
        return builder.buildIngredientSupplier();
    }

    @Test void randomizedSnapshotsMatchJei() throws ReflectiveOperationException {
        Random random = new Random(12345);
        for (int trial = 0; trial < 10_000; trial++) {
            Map<RecipeIngredientRole, List<IngredientSlotBuilder>> roles = new EnumMap<>(RecipeIngredientRole.class);
            List<List<IngredientSlotBuilder>> links = new ArrayList<>();
            for (RecipeIngredientRole role : RecipeIngredientRole.values()) {
                List<IngredientSlotBuilder> slots = new ArrayList<>();
                for (int slot = 0, count = random.nextInt(5); slot < count; slot++) {
                    List<ITypedIngredient<?>> ingredients = new ArrayList<>();
                    for (int i = 0, size = random.nextInt(8); i < size; i++) {
                        ingredients.add(switch (random.nextInt(3)) {
                            case 0 -> null;
                            case 1 -> RecipeVisibilityOptimizationTest.VISIBLE;
                            default -> RecipeVisibilityOptimizationTest.HIDDEN;
                        });
                    }
                    slots.add(new SlotFixture(role, ingredients));
                }
                roles.put(role, slots);
                if (!slots.isEmpty()) links.add(slots);
            }
            RecipeIngredientSupplier expected = original(roles, links);
            RecipeIngredientSupplier actual = RecipeSupplierOptimization.build(roles, links);
            for (RecipeIngredientRole role : RecipeIngredientRole.values()) {
                assertEquals(expected.getIngredients(role), actual.getIngredients(role));
            }
            assertEquals(expected.getFocusLinks(), actual.getFocusLinks());
        }
    }

    @Test void snapshotsOwnTheirListsAndPreserveBlankPositions() {
        List<ITypedIngredient<?>> contents = new ArrayList<>(Arrays.asList(RecipeVisibilityOptimizationTest.VISIBLE, null));
        IngredientSlotBuilder slot = new SlotFixture(RecipeIngredientRole.INPUT, contents);
        Map<RecipeIngredientRole, List<IngredientSlotBuilder>> roles = new EnumMap<>(RecipeIngredientRole.class);
        roles.put(RecipeIngredientRole.INPUT, new ArrayList<>(List.of(slot)));
        List<List<IngredientSlotBuilder>> links = new ArrayList<>(List.of(new ArrayList<>(List.of(slot))));
        RecipeIngredientSupplier supplier = RecipeSupplierOptimization.build(roles, links);
        contents.clear();
        roles.clear();
        links.clear();
        assertEquals(List.of(RecipeVisibilityOptimizationTest.VISIBLE), supplier.getIngredients(RecipeIngredientRole.INPUT));
        var linkedIngredients = supplier.getFocusLinks().getFirst().slots().getFirst().ingredients();
        assertEquals(Arrays.asList(RecipeVisibilityOptimizationTest.VISIBLE, null), linkedIngredients);
        assertThrows(UnsupportedOperationException.class, () -> linkedIngredients.clear());
        assertThrows(UnsupportedOperationException.class, () -> supplier.getIngredients(RecipeIngredientRole.INPUT).clear());
    }
}
