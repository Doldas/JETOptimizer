package dev.jetoptimizer;

import net.neoforged.fml.ModList;

/** Runtime-scoped switches for source-specific JEI recipe optimizations. */
public final class RecipeStartupOptimization {
    private static volatile Boolean visibility;
    private static volatile Boolean suppliers;

    private RecipeStartupOptimization() {}

    public static void reset() {
        visibility = null;
        suppliers = null;
    }

    public static boolean fastVisibility() {
        Boolean cached = visibility;
        if (cached != null) return cached;
        try {
            return visibility = JETOptimizerConfig.ENABLED.get()
                    && JETOptimizerConfig.EXPERIMENTAL_OPTIMIZATIONS.get()
                    && JETOptimizerConfig.FAST_UNFOCUSED_RECIPE_VISIBILITY.get()
                    && supportedJei();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    public static boolean fastSuppliers() {
        Boolean cached = suppliers;
        if (cached != null) return cached;
        try {
            return suppliers = JETOptimizerConfig.ENABLED.get()
                    && JETOptimizerConfig.EXPERIMENTAL_OPTIMIZATIONS.get()
                    && JETOptimizerConfig.FAST_RECIPE_SUPPLIERS.get()
                    && supportedJei();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }
    private static boolean supportedJei() {
        // These replacements depend on exact internal semantics, not just the public API.
        return ModList.get().getModContainerById("jei")
                .map(container -> "19.57.0.449".equals(container.getModInfo().getVersion().toString()))
                .orElse(false);
    }
}
