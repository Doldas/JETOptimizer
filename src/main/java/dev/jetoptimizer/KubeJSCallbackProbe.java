package dev.jetoptimizer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;

public final class KubeJSCallbackProbe {
    public static final int CATEGORY_MAP_BUILT = 1;
    public static final int REMOVE_CATEGORIES_DONE = 2;
    public static final int REMOVE_RECIPES_DONE = 3;
    public static final int INGREDIENT_FETCH_START = 4;
    public static final int ALL_TYPES_ENTER = 5;
    public static final int ALL_TYPES_EXIT = 6;
    public static final int REMOTE_ITEM_REMOVAL_START = 7;
    public static final int ALL_TYPES_2_ENTER = 8;
    public static final int ALL_TYPES_2_EXIT = 9;
    public static final int REMOTE_ADDED_ENTRIES_START = 10;
    public static final int CALLBACK_END = 11;

    private static final String[] MARK_NAMES = {
        "callback begin",
        "category map built",
        "remove-categories events done",
        "remove-recipes events done",
        "ingredient fetch starting",
        "item type tables init starting",
        "item type tables init finished",
        "remote item removal starting",
        "item type tables init #2 starting",
        "item type tables init #2 finished",
        "remote add-entries starting",
        "callback end",
    };

    private static final long[] MARKS = new long[MARK_NAMES.length];
    private static long managerGetterNanos;
    private static long categoryLookupCreateNanos;
    private static long categoryLookupGetNanos;
    private static long categoryMapCollectNanos;
    private static boolean categoryMapSkipped;

    private static final Method EVENT_HANDLER_HAS_LISTENERS;
    private static final Field REMOVE_CATEGORIES_EVENT;
    private static final Field REMOVE_RECIPES_EVENT;

    static {
        Method eventHandlerHasListeners = null;
        Field removeCategories = null;
        Field removeRecipes = null;
        try {
            Class<?> handler = Class.forName("dev.latvian.mods.kubejs.event.EventHandler");
            Class<?> events = Class.forName("dev.latvian.mods.kubejs.plugin.builtin.event.RecipeViewerEvents");
            eventHandlerHasListeners = handler.getMethod("hasListeners");
            removeCategories = events.getField("REMOVE_CATEGORIES");
            removeRecipes = events.getField("REMOVE_RECIPES");
        } catch (Throwable ignored) {
        }
        EVENT_HANDLER_HAS_LISTENERS = eventHandlerHasListeners;
        REMOVE_CATEGORIES_EVENT = removeCategories;
        REMOVE_RECIPES_EVENT = removeRecipes;
    }

    private KubeJSCallbackProbe() {
    }

    public static void begin() {
        Arrays.fill(MARKS, 0L);
        managerGetterNanos = 0L;
        categoryLookupCreateNanos = 0L;
        categoryLookupGetNanos = 0L;
        categoryMapCollectNanos = 0L;
        categoryMapSkipped = false;
        MARKS[0] = System.nanoTime();
    }

    public static boolean categoriesMapUnused(Object plugin) {
        if (EVENT_HANDLER_HAS_LISTENERS == null || REMOVE_CATEGORIES_EVENT == null || REMOVE_RECIPES_EVENT == null) {
            return false;
        }
        try {
            if ((Boolean) EVENT_HANDLER_HAS_LISTENERS.invoke(REMOVE_CATEGORIES_EVENT.get(null))
                || (Boolean) EVENT_HANDLER_HAS_LISTENERS.invoke(REMOVE_RECIPES_EVENT.get(null))) {
                return false;
            }
            Field remote = plugin.getClass().getDeclaredField("remote");
            remote.setAccessible(true);
            return remote.get(plugin) == null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void recordCategoryMapSkipped() {
        categoryMapSkipped = true;
    }

    public static void mark(int index) {
        MARKS[index] = System.nanoTime();
    }

    public static void recordManagerGetters(long nanos) {
        managerGetterNanos += nanos;
    }

    public static void recordCategoryLookupCreate(long nanos) {
        categoryLookupCreateNanos += nanos;
    }

    public static void recordCategoryLookupGet(long nanos) {
        categoryLookupGetNanos += nanos;
    }

    public static void recordCategoryMapCollect(long nanos) {
        categoryMapCollectNanos += nanos;
    }

    public static void finish() {
        long startedAt = MARKS[0];
        if (startedAt == 0L) {
            return;
        }
        long endedAt = System.nanoTime();
        MARKS[CALLBACK_END] = endedAt;
        StringBuilder report = new StringBuilder("KubeJS onRuntimeAvailable phase timing:\n");
        report.append("  total: ").append(formatSeconds(endedAt - startedAt)).append('\n');
        StringBuilder notReached = new StringBuilder();
        int previous = 0;
        for (int index = 1; index < MARKS.length; index++) {
            if (MARKS[index] == 0L) {
                if (notReached.length() > 0) {
                    notReached.append(", ");
                }
                notReached.append(MARK_NAMES[index]);
                continue;
            }
            report.append("  ").append(MARK_NAMES[previous]).append(" -> ").append(MARK_NAMES[index])
                .append(": ").append(formatMillis(MARKS[index] - MARKS[previous])).append('\n');
            previous = index;
        }
        if (notReached.length() > 0) {
            report.append("  not reached: ").append(notReached).append('\n');
        }
        if (categoryMapSkipped) {
            report.append("  category map build skipped: no REMOVE_CATEGORIES/REMOVE_RECIPES listeners and remote == null\n");
        }
        report.append("  nested manager getters (recipe+ingredient): ")
            .append(formatMillis(managerGetterNanos)).append('\n');
        report.append("  nested createRecipeCategoryLookup: ")
            .append(formatMillis(categoryLookupCreateNanos)).append('\n');
        report.append("  nested category lookup .get(): ")
            .append(formatMillis(categoryLookupGetNanos)).append('\n');
        report.append("  nested category map collect (toMap): ")
            .append(formatMillis(categoryMapCollectNanos)).append('\n');
        if (MARK_NAMES.length > 1 && MARKS[1] > 0L) {
            long nested = managerGetterNanos + categoryLookupCreateNanos
                + categoryLookupGetNanos + categoryMapCollectNanos;
            report.append("  first-segment remainder (map copy, key lambda): ")
                .append(formatMillis(MARKS[1] - MARKS[0] - nested)).append('\n');
        }
        Arrays.fill(MARKS, 0L);
        JETOptimizerProfiler.recordKubeJSCallbackPhases(report.toString());
    }

    private static String formatSeconds(long nanos) {
        return String.format(Locale.ROOT, "%.3f s", nanos / 1_000_000_000.0);
    }

    private static String formatMillis(long nanos) {
        return String.format(Locale.ROOT, "%.3f ms", nanos / 1_000_000.0);
    }
}
