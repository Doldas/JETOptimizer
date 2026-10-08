package dev.jetoptimizer;

import mezz.jei.api.runtime.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.*;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("workflow")
class KubeJSRemovalWorkflowTest {
    TestState state;
    @BeforeEach void start() throws Exception {
        state=new TestState(); state.config(JETOptimizerConfig.ENABLED,true);
        state.config(JETOptimizerConfig.EXPERIMENTAL_OPTIMIZATIONS,true);
        state.config(JETOptimizerConfig.KUBEJS_ITEM_REMOVAL_INDEX,true);
    }
    @AfterEach void stop() { KubeJSItemRemovalIndex.finishCallback(); state.close(); }
    void callback(List<ItemStack> source) {
        var manager=(IIngredientManager)Proxy.newProxyInstance(IIngredientManager.class.getClassLoader(),new Class<?>[]{IIngredientManager.class},
            (p,m,a) -> { if(m.getName().equals("getAllIngredients"))return source; throw new UnsupportedOperationException(m.getName()); });
        var runtime=(IJeiRuntime)Proxy.newProxyInstance(IJeiRuntime.class.getClassLoader(),new Class<?>[]{IJeiRuntime.class},
            (p,m,a) -> { if(m.getName().equals("getIngredientManager"))return manager; throw new UnsupportedOperationException(m.getName()); });
        KubeJSItemRemovalIndex.beginCallback(runtime);
    }
    static List<ItemStack> removed(List<ItemStack> source,Ingredient filter) {
        List<ItemStack> removed=new ArrayList<>(); var iterator=KubeJSItemRemovalIndex.itemIterator(source);
        while(iterator.hasNext()) {
            var stack=(ItemStack)iterator.next(); if(KubeJSItemRemovalIndex.testItem(filter,stack))removed.add(stack);
        }
        assertThrows(NoSuchElementException.class,iterator::next); return removed;
    }
    @Test void optimizedRemovalMatchesVanillaPredicateIncludingComponentVariantsAndOrder() {
        var named=new ItemStack(Items.COAL,3); named.set(DataComponents.CUSTOM_NAME,Component.literal("Special coal"));
        var source=new ArrayList<>(List.of(new ItemStack(Items.DIAMOND),named,new ItemStack(Items.COAL),new ItemStack(Items.CHARCOAL)));
        var filter=Ingredient.of(Items.COAL,Items.CHARCOAL); var expected=source.stream().filter(filter).toList();
        callback(source); assertSame(filter,KubeJSItemRemovalIndex.prepareItemFilter(filter,2));
        assertEquals(expected,removed(source,filter));
    }
    @Test void earlierClientRemovalsAreObservedWhenRemoteFilterIsPrepared() {
        var deleted=new ItemStack(Items.COAL); var kept=new ItemStack(Items.CHARCOAL);
        var source=new ArrayList<>(List.of(deleted,kept)); callback(source); source.remove(deleted);
        var filter=Ingredient.of(Items.COAL,Items.CHARCOAL); KubeJSItemRemovalIndex.prepareItemFilter(filter,2);
        assertEquals(List.of(kept),removed(source,filter));
    }
    @Test void emptyFilterAndChangedCollectionSizeUseOriginalPredicate() {
        var source=new ArrayList<>(List.of(ItemStack.EMPTY,new ItemStack(Items.COAL))); callback(source);
        KubeJSItemRemovalIndex.prepareItemFilter(Ingredient.EMPTY,0);
        assertEquals(source.stream().filter(Ingredient.EMPTY).toList(),removed(source,Ingredient.EMPTY));
        KubeJSItemRemovalIndex.finishCallback(); callback(source);
        var filter=Ingredient.of(Items.COAL); KubeJSItemRemovalIndex.prepareItemFilter(filter,1);
        source.add(new ItemStack(Items.DIAMOND));
        assertEquals(source.stream().filter(filter).toList(),removed(source,filter));
    }
    @Test void secondIteratorFallsBackWithoutTreatingEveryItemAsAMatch() {
        var source=new ArrayList<>(List.of(new ItemStack(Items.COAL),new ItemStack(Items.DIAMOND))); callback(source);
        var filter=Ingredient.of(Items.COAL); KubeJSItemRemovalIndex.prepareItemFilter(filter,1);
        assertEquals(source.stream().filter(filter).toList(),removed(source,filter));
        assertEquals(source.stream().filter(filter).toList(),removed(source,filter));
    }
    @Test void unknownCustomFilterKeepsItsExclusionPredicate() {
        var source=new ArrayList<>(List.of(new ItemStack(Items.COAL),new ItemStack(Items.CHARCOAL),new ItemStack(Items.DIAMOND)));
        var filter=net.neoforged.neoforge.common.crafting.DifferenceIngredient.of(
            Ingredient.of(Items.COAL,Items.CHARCOAL),Ingredient.of(Items.CHARCOAL));
        callback(source); KubeJSItemRemovalIndex.prepareItemFilter(filter,2);
        assertEquals(source.stream().filter(filter).toList(),removed(source,filter));
        assertEquals(List.of(source.getFirst()),source.stream().filter(filter).toList());
    }
    @Test void callbackEndClearsPredicateShortcutForNextRuntime() {
        var source=new ArrayList<>(List.of(new ItemStack(Items.COAL))); callback(source);
        var filter=Ingredient.of(Items.COAL); KubeJSItemRemovalIndex.prepareItemFilter(filter,1); removed(source,filter);
        KubeJSItemRemovalIndex.finishCallback();
        assertFalse(KubeJSItemRemovalIndex.testItem(filter,new ItemStack(Items.DIAMOND)));
    }
}
