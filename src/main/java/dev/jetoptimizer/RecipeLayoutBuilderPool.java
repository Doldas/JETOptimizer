package dev.jetoptimizer;

import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.library.gui.recipes.supplier.builder.IngredientSupplierBuilder;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Reuses the short-lived JEI builder used to extract recipe ingredient slots.
 *
 * <p>The builder's output is copied into an immutable {@code RecipeIngredientSupplier} before it is
 * recycled. Reuse is confined to the client thread and a single recipe-registration phase; entries
 * from a different ingredient manager are discarded. Recipe categories must treat the builder as a
 * callback-scoped object and must not retain it after {@code setRecipe} returns.
 */
public final class RecipeLayoutBuilderPool {
    private static final int MAX_POOLED_BUILDERS = 4;
    private static final ThreadLocal<ArrayDeque<PooledBuilder>> BUILDERS = ThreadLocal.withInitial(ArrayDeque::new);
    private static volatile Boolean cachedEnabled;

    private RecipeLayoutBuilderPool() {
    }

    public static IngredientSupplierBuilder acquire(IIngredientManager ingredientManager) {
        if (isReuseEnabled()) {
            ArrayDeque<PooledBuilder> builders = BUILDERS.get();
            IngredientSupplierBuilder reusable = null;
            for (Iterator<PooledBuilder> iterator = builders.iterator(); iterator.hasNext(); ) {
                PooledBuilder pooled = iterator.next();
                iterator.remove();
                if (pooled.ingredientManager() == ingredientManager && reusable == null) {
                    reusable = pooled.builder();
                }
            }

            boolean reused = reusable != null;
            JETOptimizerProfiler.recordRecipeLayoutBuilderAcquisition(reused);
            return reused ? reusable : new IngredientSupplierBuilder(ingredientManager);
        }

        JETOptimizerProfiler.recordRecipeLayoutBuilderAcquisition(false);
        return new IngredientSupplierBuilder(ingredientManager);
    }

    /**
     * Called after buildIngredientSupplier has copied all builder state into its result.
     */
    public static void recycle(
            IngredientSupplierBuilder builder,
            IIngredientManager ingredientManager,
            Map<?, ? extends List<?>> slotsByRole,
            List<?> focusLinkedSlots
    ) {
        try {
            for (List<?> slots : slotsByRole.values()) {
                slots.clear();
            }
            focusLinkedSlots.clear();
        } catch (RuntimeException | LinkageError e) {
            return;
        }

        if (isReuseEnabled()) {
            ArrayDeque<PooledBuilder> builders = BUILDERS.get();
            if (builders.size() < MAX_POOLED_BUILDERS) {
                builders.addFirst(new PooledBuilder(builder, ingredientManager));
            }
        }
    }

    /**
     * Release manager references as soon as the synchronous recipe-registration phase ends.
     */
    public static void clear() {
        BUILDERS.remove();
        cachedEnabled = null;
    }

    private static boolean isReuseEnabled() {
        Boolean enabled = cachedEnabled;
        if (enabled != null) {
            return enabled;
        }
        try {
            boolean value = JETOptimizerConfig.ENABLED.get()
                    && JETOptimizerConfig.EXPERIMENTAL_OPTIMIZATIONS.get()
                    && JETOptimizerConfig.REUSE_RECIPE_LAYOUT_BUILDERS.get();
            cachedEnabled = value;
            return value;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    private record PooledBuilder(IngredientSupplierBuilder builder, IIngredientManager ingredientManager) {
    }
}
