package dev.jetoptimizer;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class JETOptimizerConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.BooleanValue PROFILING;
    public static final ModConfigSpec.BooleanValue PLUGIN_PROFILING;
    public static final ModConfigSpec.BooleanValue RECONNECT_CACHE;
    public static final ModConfigSpec.BooleanValue DEBUG_CACHE;
    public static final ModConfigSpec.BooleanValue DEBUG_CACHE_INVALIDATION;
    public static final ModConfigSpec.BooleanValue EXPERIMENTAL_OPTIMIZATIONS;
    public static final ModConfigSpec.BooleanValue FAST_SEARCH_TEXT;
    public static final ModConfigSpec.BooleanValue KUBEJS_ITEM_REMOVAL_INDEX;
    public static final ModConfigSpec.BooleanValue SKIP_UNUSED_KUBEJS_CATEGORY_MAP;
    public static final ModConfigSpec.BooleanValue BULK_RUNTIME_REMOVAL_VISIBILITY;
    public static final ModConfigSpec.BooleanValue REUSE_RECIPE_LAYOUT_BUILDERS;
    public static final ModConfigSpec.BooleanValue FAST_JOIN_SKIP_TOOLTIP_SEARCH;
    public static final ModConfigSpec SPEC;

    static {
        BUILDER.push("general");
        ENABLED = BUILDER.define("enabled", true);
        PROFILING = BUILDER.define("profiling", false);
        PLUGIN_PROFILING = BUILDER.define("pluginProfiling", false);
        RECONNECT_CACHE = BUILDER.define("reconnectCache", false);
        DEBUG_CACHE = BUILDER.define("debugCache", false);
        DEBUG_CACHE_INVALIDATION = BUILDER.define("debugCacheInvalidation", false);
        EXPERIMENTAL_OPTIMIZATIONS = BUILDER.define("experimentalOptimizations", false);
        BUILDER.push("optimizations");
        FAST_SEARCH_TEXT = BUILDER
                .comment(
                        "Replaces the two per-tooltip-line regular expressions JEI uses to build search words",
                        "(chat-format stripping and whitespace splitting) with equivalent non-regex scans.",
                        "Pure text work only: no cached state is kept and no connection-bound object is touched."
                )
                .define("fastSearchText", true);
        KUBEJS_ITEM_REMOVAL_INDEX = BUILDER
                .comment(
                        "Uses a short-lived dense-ID candidate bitset for simple KubeJS remote item-removal ingredients.",
                        "Unknown/custom ingredient predicates use KubeJS's original full scan."
                )
                .define("kubeJsItemRemovalIndex", true);
        SKIP_UNUSED_KUBEJS_CATEGORY_MAP = BUILDER
                .comment("Skips KubeJS's category map only when both removal events have no listeners and remote data is absent.")
                .define("skipUnusedKubeJsCategoryMap", true);
        BULK_RUNTIME_REMOVAL_VISIBILITY = BUILDER
                .comment("Batches JEI visibility notifications for runtime removals when only JEI's internal listeners are registered.")
                .define("bulkRuntimeRemovalVisibility", true);
        REUSE_RECIPE_LAYOUT_BUILDERS = BUILDER
                .comment(
                        "Reuses JEI's short-lived recipe layout builders during one recipe-registration phase.",
                        "Experimental: recipe categories must not retain the builder after setRecipe returns."
                )
                .define("reuseRecipeLayoutBuilders", true);
        FAST_JOIN_SKIP_TOOLTIP_SEARCH = BUILDER
                .comment(
                        "Fast-join experiment: skips generating tooltip words for JEI's ingredient search index.",
                        "This does not disable displayed hover tooltips, but items will no longer match searches by tooltip text."
                )
                .define("fastJoinSkipTooltipSearch", false);
        BUILDER.pop();
        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    private JETOptimizerConfig() {
    }
}
