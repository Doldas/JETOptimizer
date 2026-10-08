package dev.jetoptimizer;

import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.library.ingredients.IngredientVisibility;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

public final class RuntimeRemovalVisibilityBatch {
    private static final String INGREDIENT_FILTER = "mezz.jei.gui.ingredients.IngredientFilter";
    private static final String RECIPE_MANAGER = "mezz.jei.library.recipes.RecipeManagerInternal";
    private static final Field LISTENERS_FIELD = findListenersField();
    private static final ThreadLocal<Batch> ACTIVE = new ThreadLocal<>();

    private RuntimeRemovalVisibilityBatch() {
    }

    public static Batch begin(IngredientVisibility visibility, int requestedIngredients) {
        if (!enabled()) {
            return null;
        }
        return new Batch(visibility, requestedIngredients, hasOnlyJeiListeners(visibility));
    }

    public static boolean enabled() {
        try {
            return JETOptimizerConfig.ENABLED.get()
                    && JETOptimizerConfig.EXPERIMENTAL_OPTIMIZATIONS.get()
                    && JETOptimizerConfig.BULK_RUNTIME_REMOVAL_VISIBILITY.get();
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    public static Batch current() {
        return ACTIVE.get();
    }

    public static void activate(Batch batch) {
        ACTIVE.set(batch);
    }

    public static void restore(Batch previous) {
        if (previous == null) {
            ACTIVE.remove();
        } else {
            ACTIVE.set(previous);
        }
    }

    private static Field findListenersField() {
        try {
            Field field = IngredientVisibility.class.getDeclaredField("listeners");
            field.setAccessible(true);
            return field;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean hasOnlyJeiListeners(IngredientVisibility visibility) {
        if (visibility == null || LISTENERS_FIELD == null) {
            return false;
        }
        try {
            Object value = LISTENERS_FIELD.get(visibility);
            if (!(value instanceof List<?> listeners) || listeners.size() != 2) {
                return false;
            }
            return Set.of(INGREDIENT_FILTER, RECIPE_MANAGER).equals(listeners.stream()
                    .map(listener -> listener.getClass().getName()).collect(Collectors.toSet()));
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static final class Batch {
        private final IngredientVisibility visibility;
        private final int requestedIngredients;
        private boolean listenersCompatible;
        private final long startedAt = System.nanoTime();
        private final Map<Integer, Group> groups = new LinkedHashMap<>(4);
        private int individualNotifications;
        private int singleDispatches;
        private int batchedItems;
        private int batchedDispatches;
        private boolean finished;

        private Batch(IngredientVisibility visibility, int requestedIngredients, boolean listenersCompatible) {
            this.visibility = visibility;
            this.requestedIngredients = requestedIngredients;
            this.listenersCompatible = listenersCompatible;
        }

        public boolean collect(ITypedIngredient<?> ingredient, Collection<UidContext> contexts, boolean visible) {
            individualNotifications++;
            if (!listenersCompatible) {
                singleDispatches++;
                return false;
            }
            if (visible || contexts.isEmpty()) {
                flushGroups();
                listenersCompatible = false;
                singleDispatches++;
                return false;
            }

            int contextMask = contexts.stream().mapToInt(context -> 1 << context.ordinal()).reduce(0, (first, second) -> first | second);
            Group group = groups.computeIfAbsent(contextMask, mask -> new Group(mask, EnumSet.copyOf(contexts)));
            group.ingredients.add(ingredient);
            batchedItems++;
            return true;
        }

        public void finish() {
            if (finished) {
                return;
            }
            finished = true;
            try {
                flushGroups();
            } finally {
                JETOptimizerProfiler.recordRuntimeRemovalVisibilityBatch(
                        requestedIngredients,
                        individualNotifications,
                        singleDispatches,
                        batchedItems,
                        batchedDispatches,
                        listenersCompatible,
                        System.nanoTime() - startedAt
                );
            }
        }

        private void flushGroups() {
            if (visibility == null) {
                return;
            }
            groups.values().forEach(group -> {
                notifyListeners(group);
                batchedDispatches++;
            });
            groups.clear();
        }

        private void notifyListeners(Group group) {
            notifyListenersUnchecked(group.ingredients, group.contexts);
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private void notifyListenersUnchecked(Collection ingredients, Collection<UidContext> contexts) {
            visibility.notifyListeners(ingredients, contexts, false);
        }
    }

    private static final class Group {
        private final int contextMask;
        private final EnumSet<UidContext> contexts;
        private final List<ITypedIngredient<?>> ingredients = new ArrayList<>();

        private Group(int contextMask, EnumSet<UidContext> contexts) {
            this.contextMask = contextMask;
            this.contexts = contexts;
        }
    }
}
