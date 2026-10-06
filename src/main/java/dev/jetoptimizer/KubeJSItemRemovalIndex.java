package dev.jetoptimizer;

import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.common.crafting.CompoundIngredient;
import net.neoforged.neoforge.common.crafting.ICustomIngredient;

import java.util.BitSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Short-lived ID-first candidate selection for KubeJS's remote JEI item removal pass.
 *
 * <p>KubeJS builds a union Ingredient, then tests it against every registered item. For the exact
 * vanilla ItemValue/TagValue tree and NeoForge CompoundIngredient nodes, those tests are completely
 * described by item registry IDs. We turn that union into a BitSet and stream only matching dense
 * ingredient IDs to KubeJS. Unknown custom predicates keep KubeJS's original iterator and test path.
 * The source collection is a live JEI view, so the snapshot is taken only when the remote filter is
 * ready, after KubeJS's earlier client-side removal events have run.
 */
public final class KubeJSItemRemovalIndex {
    private static final ThreadLocal<Context> ACTIVE = new ThreadLocal<>();

    private KubeJSItemRemovalIndex() {
    }

    public static void beginCallback(IJeiRuntime runtime) {
        boolean enabled;
        try {
            enabled = JETOptimizerConfig.ENABLED.get() && JETOptimizerConfig.KUBEJS_ITEM_REMOVAL_INDEX.get();
        } catch (RuntimeException | LinkageError ignored) {
            enabled = false;
        }
        Context context = new Context(enabled);
        ACTIVE.set(context);
        if (enabled) {
            try {
                // IngredientInfo returns an unmodifiable live view of RegisteredIngredientIndex's
                // LinkedHashMap values. KubeJS can capture its own view before client removal events;
                // this view reflects those mutations when snapshotted later at filter construction.
                context.itemSource = runtime.getIngredientManager().getAllIngredients(VanillaTypes.ITEM_STACK);
            } catch (RuntimeException | LinkageError ignored) {
                context.itemSource = null;
                context.fallbackFilters++;
            }
        }
    }

    public static Ingredient prepareItemFilter(Ingredient filter, int patternCount) {
        Context context = ACTIVE.get();
        if (context == null || !context.enabled || context.itemSource == null) {
            return filter;
        }

        context.filterCount++;
        context.patternCount += patternCount;
        context.sourceIngredientEntries += context.itemSource.size();
        long startedAt = System.nanoTime();
        try {
            BitSet itemRegistryIds = new BitSet(BuiltInRegistries.ITEM.size());
            if (!collectSimpleItemIds(filter, itemRegistryIds, context)) {
                context.fallbackFilters++;
                context.indexNanos += System.nanoTime() - startedAt;
                context.itemSource = null;
                return filter;
            }

            context.filterItemRegistryIds += itemRegistryIds.cardinality();
            int expectedSize = context.itemSource.size();
            ItemStack[] stacksByDenseId = context.itemSource.toArray(new ItemStack[expectedSize]);

            BitSet candidateIngredientIds = new BitSet(stacksByDenseId.length);
            for (int ingredientId = 0; ingredientId < stacksByDenseId.length; ingredientId++) {
                ItemStack stack = stacksByDenseId[ingredientId];
                if (stack == null) {
                    context.fallbackFilters++;
                    context.indexNanos += System.nanoTime() - startedAt;
                    context.itemSource = null;
                    return filter;
                }
                int itemRegistryId = BuiltInRegistries.ITEM.getId(stack.getItem());
                if (itemRegistryId < 0 || itemRegistryId >= BuiltInRegistries.ITEM.size()) {
                    context.fallbackFilters++;
                    context.indexNanos += System.nanoTime() - startedAt;
                    context.itemSource = null;
                    return filter;
                }
                if (itemRegistryIds.get(itemRegistryId)) {
                    candidateIngredientIds.set(ingredientId);
                }
            }

            int candidateCount = candidateIngredientIds.cardinality();
            context.candidateIngredientEntries += candidateCount;
            context.candidateLoopExpected += candidateCount;
            context.indexBuilds++;
            context.indexNanos += System.nanoTime() - startedAt;
            context.fastFilter = filter;
            context.selection = new CandidateSelection(stacksByDenseId, candidateIngredientIds, candidateCount);
        } catch (RuntimeException | LinkageError ignored) {
            context.fallbackFilters++;
            context.indexNanos += System.nanoTime() - startedAt;
            context.itemSource = null;
            context.fastFilter = null;
            context.selection = null;
        }
        return filter;
    }

    public static Iterator<?> itemIterator(Collection<?> original) {
        Context context = ACTIVE.get();
        CandidateSelection selection = context == null ? null : context.selection;
        if (selection == null || context.itemSource == null || context.sourceIngredientEntries != original.size()) {
            if (context != null) {
                context.fullScanFallbacks++;
                context.itemSource = null;
            }
            return original.iterator();
        }

        context.itemSource = null;
        context.selection = null;
        context.fastIteratorStarted = true;
        return selection.iterator(context);
    }

    /**
     * Exact for the fast iterator: its entries were selected from the item-ID union represented by
     * this recognized simple filter tree. If no such iterator is active, delegate unchanged.
     */
    public static boolean testItem(Ingredient filter, ItemStack stack) {
        Context context = ACTIVE.get();
        if (context != null && context.fastIteratorStarted && context.fastFilter == filter) {
            context.predicateTestsBypassed++;
            return true;
        }
        if (context != null) {
            context.originalPredicateTests++;
        }
        return filter.test(stack);
    }

    public static void recordRemovalRequest(int requestedCount) {
        Context context = ACTIVE.get();
        if (context != null) {
            context.managerRemovalRequestEntries += requestedCount;
        }
    }

    public static void finishCallback() {
        Context context = ACTIVE.get();
        ACTIVE.remove();
        if (context == null) {
            return;
        }
        JETOptimizerProfiler.recordKubeJSItemRemovalIndex(
            context.enabled,
            context.filterCount,
            context.patternCount,
            context.sourceIngredientEntries,
            context.filterItemRegistryIds,
            context.candidateIngredientEntries,
            context.candidateLoopExpected,
            context.candidateLoopEntries,
            context.predicateTestsBypassed,
            context.originalPredicateTests,
            context.fullScanFallbacks,
            context.fallbackFilters,
            context.managerRemovalRequestEntries,
            context.indexBuilds,
            context.leafItemValues,
            context.indexNanos
        );
    }

    private static boolean collectSimpleItemIds(Ingredient ingredient, BitSet itemRegistryIds, Context context) {
        if (!ingredient.isSimple()) {
            return false;
        }
        if (ingredient.isCustom()) {
            ICustomIngredient customIngredient = ingredient.getCustomIngredient();
            if (!(customIngredient instanceof CompoundIngredient compoundIngredient)) {
                return false;
            }
            for (Ingredient child : compoundIngredient.children()) {
                if (!collectSimpleItemIds(child, itemRegistryIds, context)) {
                    return false;
                }
            }
            return true;
        }

        if (ingredient.isEmpty()) {
            // The empty Ingredient matches ItemStack.EMPTY; keep that edge case on JEI's original path.
            return false;
        }
        ItemStack[] values = ingredient.getItems();
        if (values.length == 0) {
            return false;
        }
        context.leafItemValues += values.length;
        for (ItemStack value : values) {
            if (value == null) {
                return false;
            }
            int itemRegistryId = BuiltInRegistries.ITEM.getId(value.getItem());
            if (itemRegistryId < 0 || itemRegistryId >= BuiltInRegistries.ITEM.size()) {
                return false;
            }
            itemRegistryIds.set(itemRegistryId);
        }
        return true;
    }

    private static final class Context {
        private final boolean enabled;
        private Collection<?> itemSource;
        private Ingredient fastFilter;
        private CandidateSelection selection;
        private boolean fastIteratorStarted;
        private int filterCount;
        private int patternCount;
        private int leafItemValues;
        private int filterItemRegistryIds;
        private int sourceIngredientEntries;
        private int candidateIngredientEntries;
        private int candidateLoopExpected;
        private int candidateLoopEntries;
        private int predicateTestsBypassed;
        private int originalPredicateTests;
        private int fullScanFallbacks;
        private int fallbackFilters;
        private int managerRemovalRequestEntries;
        private int indexBuilds;
        private long indexNanos;

        private Context(boolean enabled) {
            this.enabled = enabled;
        }
    }

    private record CandidateSelection(ItemStack[] stacksByDenseId, BitSet ingredientIds, int size) {
        private Iterator<ItemStack> iterator(Context context) {
            return new Iterator<>() {
                private int nextDenseId = ingredientIds.nextSetBit(0);

                @Override
                public boolean hasNext() {
                    return nextDenseId >= 0;
                }

                @Override
                public ItemStack next() {
                    if (nextDenseId < 0) {
                        throw new NoSuchElementException();
                    }
                    int currentDenseId = nextDenseId;
                    nextDenseId = ingredientIds.nextSetBit(currentDenseId + 1);
                    context.candidateLoopEntries++;
                    return stacksByDenseId[currentDenseId];
                }
            };
        }
    }
}
