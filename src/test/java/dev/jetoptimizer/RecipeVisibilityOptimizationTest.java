package dev.jetoptimizer;

import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.library.ingredients.RecipeIngredientSupplier.FocusLink;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class RecipeVisibilityOptimizationTest {
    static final ITypedIngredient<String> VISIBLE = new TestIngredient("visible");
    static final ITypedIngredient<String> HIDDEN = new TestIngredient("hidden");
    static final Predicate<ITypedIngredient<?>> IS_VISIBLE = ingredient -> ingredient != HIDDEN;
    static final IFocusGroup EMPTY = new IFocusGroup() {
        public boolean isEmpty() { return true; }
        public List<IFocus<?>> getAllFocuses() { return List.of(); }
        public Stream<IFocus<?>> getFocuses(RecipeIngredientRole role) { return Stream.empty(); }
        public <T> Stream<IFocus<T>> getFocuses(IIngredientType<T> type) { return Stream.empty(); }
        public <T> Stream<IFocus<T>> getFocuses(IIngredientType<T> type, RecipeIngredientRole role) { return Stream.empty(); }
    };

    record TestIngredient(String getIngredient) implements ITypedIngredient<String> {
        public IIngredientType<String> getType() { return () -> String.class; }
        public ITypedIngredient<String> normalize(IIngredientHelper<String> helper) { return this; }
    }

    @SafeVarargs
    static FocusLink link(List<? extends ITypedIngredient<?>>... slots) {
        List<FocusLink.Slot> result = new ArrayList<>();
        for (var slot : slots) result.add(new FocusLink.Slot(RecipeIngredientRole.INPUT, new ArrayList<>(slot)));
        return new FocusLink(result);
    }

    static void compare(FocusLink link) {
        // Call the actual pinned JEI implementation as the oracle, not a copy of our algorithm.
        boolean original = link.getVisibleIngredientIndexes(EMPTY, null, IS_VISIBLE) != null;
        assertEquals(original, RecipeVisibilityOptimization.hasVisibleCombination(link, IS_VISIBLE), link.toString());
    }

    @Test void blanksEmptyAndMismatchedSlotsMatchJei() {
        compare(link());
        compare(link(List.of()));
        compare(link(List.of(), List.of(HIDDEN)));
        compare(link(List.of(VISIBLE), List.of()));
        compare(link(Arrays.asList(null, HIDDEN), List.of(HIDDEN, VISIBLE)));
        compare(link(Arrays.asList(null, VISIBLE), Arrays.asList(null, HIDDEN)));
        compare(link(List.of(HIDDEN, VISIBLE), List.of(VISIBLE, HIDDEN)));
        compare(link(List.of(HIDDEN), List.of(HIDDEN)));
    }

    @Test void randomizedDifferentialAgainstJei() {
        Random random = new Random(0x4a45544f);
        for (int trial = 0; trial < 100_000; trial++) {
            List<FocusLink.Slot> slots = new ArrayList<>();
            for (int slot = 0, count = random.nextInt(5); slot < count; slot++) {
                List<ITypedIngredient<?>> ingredients = new ArrayList<>();
                for (int index = 0, size = random.nextInt(9); index < size; index++) {
                    ingredients.add(switch (random.nextInt(3)) { case 0 -> null; case 1 -> VISIBLE; default -> HIDDEN; });
                }
                slots.add(new FocusLink.Slot(RecipeIngredientRole.values()[random.nextInt(4)], ingredients));
            }
            compare(new FocusLink(slots));
        }
    }

    @Test void stopsAfterFirstCompleteCombination() {
        FocusLink link = link(java.util.Collections.nCopies(4096, VISIBLE), java.util.Collections.nCopies(4096, VISIBLE));
        AtomicInteger calls = new AtomicInteger();
        assertTrue(RecipeVisibilityOptimization.hasVisibleCombination(link, ingredient -> { calls.incrementAndGet(); return true; }));
        assertEquals(2, calls.get());
        calls.set(0);
        assertNotNull(link.getVisibleIngredientIndexes(EMPTY, null, ingredient -> { calls.incrementAndGet(); return true; }));
        assertEquals(8192, calls.get());
    }
}
