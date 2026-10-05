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
        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    private JETOptimizerConfig() {
    }
}
