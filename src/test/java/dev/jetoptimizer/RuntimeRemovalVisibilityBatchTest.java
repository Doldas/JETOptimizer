package dev.jetoptimizer;

import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.runtime.*;
import mezz.jei.library.config.EditModeConfig;
import mezz.jei.library.ingredients.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeRemovalVisibilityBatchTest {
    record Event(List<?> ingredients, Set<UidContext> contexts, boolean visible) {}
    static IngredientVisibility visibility(List<Event> events) {
        var manager=(IIngredientManager)Proxy.newProxyInstance(IIngredientManager.class.getClassLoader(),new Class<?>[]{IIngredientManager.class},(p,m,a) -> {
            if (m.getName().equals("registerIngredientListener")) return null;
            throw new UnsupportedOperationException(m.getName());
        });
        var edit=new EditModeConfig(new EditModeConfig.ISerializer() {
            public void initialize(EditModeConfig config) {} public void save(EditModeConfig config) {} public void load(EditModeConfig config) {}
        },manager);
        var visibility=new IngredientVisibility(new IngredientBlacklistInternal(manager),null,edit,manager);
        visibility.registerListener(new IIngredientVisibility.IListener() {
            public <V> void onIngredientVisibilityChanged(ITypedIngredient<V> ingredient,boolean visible) {
                events.add(new Event(List.of(ingredient),Set.of(),visible));
            }
            public <V> void onIngredientsVisibilityChanged(Collection<ITypedIngredient<V>> ingredients,Collection<UidContext> contexts,boolean visible) {
                events.add(new Event(List.copyOf(ingredients),Set.copyOf(contexts),visible));
            }
        });
        return visibility;
    }
    // Exercise the batching algorithm with a recording listener; the real compatibility gate is tested separately.
    static RuntimeRemovalVisibilityBatch.Batch compatible(IngredientVisibility visibility) throws Exception {
        var constructor=RuntimeRemovalVisibilityBatch.Batch.class.getDeclaredConstructor(IngredientVisibility.class,int.class,boolean.class);
        constructor.setAccessible(true); return constructor.newInstance(visibility,10,true);
    }
    @Test void contextGroupsPreserveDuplicatesAndEncounterOrderAndFinishOnlyOnce() throws Exception {
        List<Event> events=new ArrayList<>(); var batch=compatible(visibility(events));
        var visible=RecipeVisibilityOptimizationTest.VISIBLE; var hidden=RecipeVisibilityOptimizationTest.HIDDEN;
        assertTrue(batch.collect(visible,List.of(UidContext.Recipe),false));
        assertTrue(batch.collect(hidden,List.of(UidContext.Ingredient),false));
        assertTrue(batch.collect(visible,List.of(UidContext.Recipe,UidContext.Recipe),false));
        assertTrue(events.isEmpty()); batch.finish(); batch.finish();
        assertEquals(List.of(new Event(List.of(visible,visible),Set.of(UidContext.Recipe),false),
            new Event(List.of(hidden),Set.of(UidContext.Ingredient),false)),events);
    }
    @Test void visibleChangeFlushesPendingRemovalsBeforeOriginalNotificationAndDisablesBatching() throws Exception {
        List<Event> events=new ArrayList<>(); var visibility=visibility(events); var batch=compatible(visibility);
        var ingredient=RecipeVisibilityOptimizationTest.VISIBLE;
        assertTrue(batch.collect(ingredient,List.of(UidContext.Recipe),false));
        assertFalse(batch.collect(ingredient,List.of(UidContext.Recipe),true));
        visibility.notifyListeners(List.of(ingredient),List.of(UidContext.Recipe),true);
        assertFalse(batch.collect(ingredient,List.of(UidContext.Recipe),false)); batch.finish();
        assertEquals(List.of(false,true),events.stream().map(Event::visible).toList());
    }
    @Test void thirdPartyListenersKeepIndividualDispatchSemantics() throws Exception {
        try(var state=new TestState()) {
            state.config(JETOptimizerConfig.ENABLED,true); state.config(JETOptimizerConfig.EXPERIMENTAL_OPTIMIZATIONS,true);
            state.config(JETOptimizerConfig.BULK_RUNTIME_REMOVAL_VISIBILITY,true);
            List<Event> events=new ArrayList<>(); var visibility=visibility(events); var batch=RuntimeRemovalVisibilityBatch.begin(visibility,1);
            assertNotNull(batch); assertFalse(batch.collect(RecipeVisibilityOptimizationTest.VISIBLE,List.of(UidContext.Recipe),false));
            batch.finish(); assertTrue(events.isEmpty(),"caller must retain original listener dispatch");
        }
    }
}
