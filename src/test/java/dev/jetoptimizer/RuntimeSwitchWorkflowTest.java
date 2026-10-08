package dev.jetoptimizer;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("workflow")
class RuntimeSwitchWorkflowTest {
    @Test void tooltipSearchConfigChangesTakeEffectOnNextRuntimeAndMasterSwitchWins() throws Exception {
        try (var state=new TestState()) {
            state.config(JETOptimizerConfig.ENABLED,true);
            state.config(JETOptimizerConfig.EXPERIMENTAL_OPTIMIZATIONS,true);
            state.config(JETOptimizerConfig.FAST_JOIN_SKIP_TOOLTIP_SEARCH,true);
            TooltipSearchOptimization.beginRuntime(); assertTrue(TooltipSearchOptimization.skipTooltipSearch());
            state.config(JETOptimizerConfig.FAST_JOIN_SKIP_TOOLTIP_SEARCH,false);
            assertTrue(TooltipSearchOptimization.skipTooltipSearch());
            TooltipSearchOptimization.endRuntime(); TooltipSearchOptimization.beginRuntime();
            assertFalse(TooltipSearchOptimization.skipTooltipSearch());
            state.config(JETOptimizerConfig.FAST_JOIN_SKIP_TOOLTIP_SEARCH,true);
            state.config(JETOptimizerConfig.ENABLED,false); TooltipSearchOptimization.beginRuntime();
            assertFalse(TooltipSearchOptimization.skipTooltipSearch());
        } finally { TooltipSearchOptimization.endRuntime(); }
    }
    @Test void disabledRecipeBatchScopesAreNestedAndReleasedEvenOnCategoryFailure() throws Exception {
        try (var state=new TestState()) {
            state.set(PersistentRecipeCache.class,"enabled",false);
            var current=(ThreadLocal<?>)TestState.get(PersistentRecipeCache.class,"CURRENT");
            assertNull(current.get());
            try (var outer=PersistentRecipeCache.prepareBatch(null,null,java.util.List.of(new Object()))) {
                assertSame(outer,current.get());
                assertThrows(IllegalStateException.class,() -> {
                    try (var inner=PersistentRecipeCache.prepareBatch(null,null,java.util.List.of(new Object()))) {
                        assertSame(inner,current.get()); throw new IllegalStateException("category failed");
                    }
                });
                assertSame(outer,current.get());
                assertNull(PersistentRecipeCache.lookup(new Object(),null));
            }
            assertNull(current.get());
        } finally { PersistentRecipeCache.stopRuntime(); }
    }
}
