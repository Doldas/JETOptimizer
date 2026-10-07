package dev.jetoptimizer;

/**
 * Controls the explicit fast-join experiment that omits tooltip words from JEI's search index.
 */
public final class TooltipSearchOptimization {
    private static volatile Boolean cachedSkipTooltipSearch;

    private TooltipSearchOptimization() {
    }

    /**
     * Reset at the start of each JEI runtime so config edits take effect on the next restart.
     */
    public static void beginRuntime() {
        cachedSkipTooltipSearch = null;
    }

    public static void endRuntime() {
        cachedSkipTooltipSearch = null;
    }

    public static boolean skipTooltipSearch() {
        Boolean cached = cachedSkipTooltipSearch;
        if (cached != null) {
            return cached;
        }
        try {
            boolean value = JETOptimizerConfig.ENABLED.get()
                    && JETOptimizerConfig.EXPERIMENTAL_OPTIMIZATIONS.get()
                    && JETOptimizerConfig.FAST_JOIN_SKIP_TOOLTIP_SEARCH.get();
            cachedSkipTooltipSearch = value;
            return value;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }
}
