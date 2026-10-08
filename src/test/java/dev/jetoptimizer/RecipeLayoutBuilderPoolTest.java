package dev.jetoptimizer;

import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.library.gui.recipes.supplier.builder.*;
import org.junit.jupiter.api.*;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RecipeLayoutBuilderPoolTest {
    TestState state;
    @BeforeEach void start() throws Exception {
        state = new TestState();
        state.config(JETOptimizerConfig.ENABLED, true);
        state.config(JETOptimizerConfig.EXPERIMENTAL_OPTIMIZATIONS, true);
        state.config(JETOptimizerConfig.REUSE_RECIPE_LAYOUT_BUILDERS, true);
        RecipeLayoutBuilderPool.beginRegistration();
    }
    @AfterEach void stop() { RecipeLayoutBuilderPool.clear(); state.close(); }
    private static IIngredientManager manager() {
        return (IIngredientManager) Proxy.newProxyInstance(IIngredientManager.class.getClassLoader(),
                new Class<?>[]{IIngredientManager.class}, (p,m,a) -> { throw new UnsupportedOperationException(m.getName()); });
    }
    @SuppressWarnings("unchecked")
    private static void recycle(IngredientSupplierBuilder builder, IIngredientManager manager) throws Exception {
        RecipeLayoutBuilderPool.recycle(builder, manager,
            (Map<?, ? extends List<?>>) TestState.field(IngredientSupplierBuilder.class, "ingredientSlotBuilders").get(builder),
            (List<?>) TestState.field(IngredientSupplierBuilder.class, "focusLinkedSlots").get(builder));
    }
    @Test void nextRecipeStartsEmptyAndPreviousSupplierKeepsItsIngredients() throws Exception {
        var manager = manager(); var builder = RecipeLayoutBuilderPool.acquire(manager);
        @SuppressWarnings("unchecked") var roles = (Map<RecipeIngredientRole,List<IngredientSlotBuilder>>)TestState.field(IngredientSupplierBuilder.class,"ingredientSlotBuilders").get(builder);
        roles.put(RecipeIngredientRole.INPUT, new ArrayList<>(List.of(new RecipeSupplierOptimizationTest.SlotFixture(
            RecipeIngredientRole.INPUT, List.of(RecipeVisibilityOptimizationTest.VISIBLE)))));
        var first = RecipeSupplierOptimization.build(roles, List.of());
        recycle(builder, manager);
        assertSame(builder, RecipeLayoutBuilderPool.acquire(manager));
        assertTrue(builder.buildIngredientSupplier().getIngredients(RecipeIngredientRole.INPUT).isEmpty());
        assertEquals(List.of(RecipeVisibilityOptimizationTest.VISIBLE), first.getIngredients(RecipeIngredientRole.INPUT));
    }
    @Test void disconnectAndManagerChangesDoNotReuseOldClientObjects() throws Exception {
        var oldManager = manager(); var old = RecipeLayoutBuilderPool.acquire(oldManager); recycle(old, oldManager);
        var nextManager = manager(); var next = RecipeLayoutBuilderPool.acquire(nextManager); assertNotSame(old, next);
        recycle(next, nextManager); RecipeLayoutBuilderPool.clear(); RecipeLayoutBuilderPool.beginRegistration();
        assertNotSame(next, RecipeLayoutBuilderPool.acquire(nextManager));
    }
    @Test void runtimeCallbacksOutsideRegistrationDoNotPoolBuilders() throws Exception {
        RecipeLayoutBuilderPool.clear(); var manager = manager(); var first = RecipeLayoutBuilderPool.acquire(manager);
        recycle(first, manager); assertNotSame(first, RecipeLayoutBuilderPool.acquire(manager));
    }
    @Test void disablingTheOptimizationPreservesOriginalBuilderContents() throws Exception {
        state.config(JETOptimizerConfig.REUSE_RECIPE_LAYOUT_BUILDERS, false);
        var builder = RecipeLayoutBuilderPool.acquire(manager());
        List<Object> slots = new ArrayList<>(List.of(new Object())); List<Object> links = new ArrayList<>(slots);
        RecipeLayoutBuilderPool.recycle(builder, null, Map.of("role",slots), links);
        assertEquals(1,slots.size()); assertEquals(1,links.size());
    }
    @Test void concurrentRegistrationOnAnotherThreadCannotBorrowThisThreadsBuilder() throws Exception {
        var manager = manager(); var first = RecipeLayoutBuilderPool.acquire(manager); recycle(first,manager);
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var other = executor.submit(() -> {
                RecipeLayoutBuilderPool.beginRegistration();
                try { return RecipeLayoutBuilderPool.acquire(manager); } finally { RecipeLayoutBuilderPool.clear(); }
            }).get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertNotSame(first,other); assertSame(first,RecipeLayoutBuilderPool.acquire(manager));
        }
    }
}
