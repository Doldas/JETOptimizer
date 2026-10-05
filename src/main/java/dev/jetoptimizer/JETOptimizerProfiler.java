package dev.jetoptimizer;

import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RecipesUpdatedEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Collects only timings and counts around source-confirmed JEI lifecycle boundaries. */
public final class JETOptimizerProfiler {
    private static final ThreadLocal<Session> ACTIVE_SESSION = new ThreadLocal<>();

    private static volatile long recipePacketStartedAt;
    private static volatile PendingRecipeSync pendingRecipeSync;

    private JETOptimizerProfiler() {
    }

    public static void onLoggingIn() {
        recipePacketStartedAt = 0L;
        pendingRecipeSync = null;
    }

    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        if (event.getPlayer() != null) {
            recipePacketStartedAt = 0L;
            pendingRecipeSync = null;
            ACTIVE_SESSION.remove();
        }
    }

    public static void onRecipesUpdated(RecipesUpdatedEvent event) {
        if (!JETOptimizerConfig.ENABLED.get()
            || (!JETOptimizerConfig.PROFILING.get() && !JETOptimizerConfig.PLUGIN_PROFILING.get())) {
            pendingRecipeSync = null;
            recipePacketStartedAt = 0L;
            return;
        }

        long now = System.nanoTime();
        long packetStart = recipePacketStartedAt;
        recipePacketStartedAt = 0L;
        int recipeCount = event.getRecipeManager().getRecipes().size();
        pendingRecipeSync = new PendingRecipeSync(packetStart, now, recipeCount);
    }

    public static void recipePacketStarted() {
        if (JETOptimizerConfig.ENABLED.get() && (JETOptimizerConfig.PROFILING.get() || JETOptimizerConfig.PLUGIN_PROFILING.get())) {
            recipePacketStartedAt = System.nanoTime();
        }
    }

    public static void beginJeiStartup() {
        if (!JETOptimizerConfig.ENABLED.get() || (!JETOptimizerConfig.PROFILING.get() && !JETOptimizerConfig.PLUGIN_PROFILING.get())) {
            return;
        }

        PendingRecipeSync sync = pendingRecipeSync;
        pendingRecipeSync = null;
        long now = System.nanoTime();
        Session session = new Session(now, sync);
        session.observedHooks.add("JeiStarter.start");
        if (sync != null) {
            session.observedHooks.add("RecipesUpdatedEvent");
            if (sync.packetStartedAt > 0L) {
                session.observedHooks.add("ClientPacketListener.handleUpdateRecipes");
            }
        }
        ACTIVE_SESSION.set(session);
    }

    public static void finishJeiStartup(long finishedAt) {
        Session session = ACTIVE_SESSION.get();
        if (session == null) {
            return;
        }

        long totalNanos = finishedAt - session.startedAt;
        ACTIVE_SESSION.remove();
        if (JETOptimizerConfig.PROFILING.get()) {
            logProfile(session, totalNanos);
        }
        if (JETOptimizerConfig.PLUGIN_PROFILING.get()) {
            logPluginTimings(session);
        }
    }

    public static void beginStage(String stageName) {
        Session session = ACTIVE_SESSION.get();
        if (session != null && JETOptimizerConfig.PROFILING.get()) {
            session.stageStartedAt.put(stageName, System.nanoTime());
            session.observedHooks.add(stageName);
        }
    }

    public static void finishStage(String stageName) {
        Session session = ACTIVE_SESSION.get();
        if (session == null || !JETOptimizerConfig.PROFILING.get()) {
            return;
        }
        Long startedAt = session.stageStartedAt.remove(stageName);
        if (startedAt != null) {
            session.stageNanos.merge(stageName, System.nanoTime() - startedAt, Long::sum);
        }
    }

    public static void recordPluginCallback(String uid, long elapsedNanos) {
        Session session = ACTIVE_SESSION.get();
        if (session != null && JETOptimizerConfig.PLUGIN_PROFILING.get()) {
            session.pluginNanos.merge(uid, elapsedNanos, Long::sum);
            session.pluginUids.add(uid);
        }
    }

    public static boolean isPluginProfilingActive() {
        return ACTIVE_SESSION.get() != null
            && JETOptimizerConfig.ENABLED.get()
            && JETOptimizerConfig.PLUGIN_PROFILING.get();
    }

    public static void recordIngredientCount(int count) {
        Session session = ACTIVE_SESSION.get();
        if (session != null) {
            session.ingredientCount = count;
        }
    }

    public static void recordRecipeCategoryCount(int count) {
        Session session = ACTIVE_SESSION.get();
        if (session != null) {
            session.recipeCategoryCount = count;
        }
    }

    private static void logProfile(Session session, long totalNanos) {
        StringBuilder lines = new StringBuilder("[JETOptimizer] JEI initialization profile\n");

        if (session.recipeSync != null) {
            PendingRecipeSync sync = session.recipeSync;
            if (sync.packetStartedAt > 0L) {
                appendTiming(lines, "Recipe handler to JEI start", session.startedAt - sync.packetStartedAt);
                appendTiming(lines, "Recipe handler to RecipesUpdatedEvent", sync.eventAt - sync.packetStartedAt);
                appendTiming(lines, "RecipesUpdatedEvent to JEI start", session.startedAt - sync.eventAt);
            } else {
                appendTiming(lines, "RecipesUpdatedEvent to JEI start", session.startedAt - sync.eventAt);
            }
            lines.append("Client recipes: ").append(sync.recipeCount).append('\n');
        } else {
            lines.append("Recipe synchronization timing: unavailable (no matching RecipesUpdatedEvent)\n");
        }

        session.stageNanos.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> appendTiming(lines, entry.getKey(), entry.getValue()));
        long topLevelStagesNanos = session.stageNanos.entrySet().stream()
            .filter(entry -> !entry.getKey().equals("Ingredient list construction"))
            .filter(entry -> !entry.getKey().equals("Ingredient filter construction"))
            .filter(entry -> !entry.getKey().equals("Ingredient search index construction"))
            .mapToLong(Map.Entry::getValue)
            .sum();
        appendTiming(lines, "Other (unattributed)", Math.max(0L, totalNanos - topLevelStagesNanos));
        appendTiming(lines, "Total JEI start", totalNanos);
        lines.append("Ingredient count: ").append(session.ingredientCount >= 0 ? session.ingredientCount : "unavailable").append('\n');
        lines.append("Recipe category count: ").append(session.recipeCategoryCount >= 0 ? session.recipeCategoryCount : "unavailable").append('\n');
        if (JETOptimizerConfig.PLUGIN_PROFILING.get()) {
            lines.append("Plugin UIDs observed: ").append(session.pluginUids.size()).append('\n');
        }
        lines.append("Mixin hooks: ").append(session.observedHooks).append('\n');
        JETOptimizer.LOGGER.info(lines.toString().stripTrailing());
    }

    private static void logPluginTimings(Session session) {
        if (session.pluginNanos.isEmpty()) {
            JETOptimizer.LOGGER.info("[JETOptimizer] JEI plugin timings: no callbacks observed");
            return;
        }

        StringBuilder lines = new StringBuilder("[JETOptimizer] JEI plugin timings\n");
        session.pluginNanos.entrySet().stream()
            .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
            .forEach(entry -> appendTiming(lines, entry.getKey(), entry.getValue()));
        JETOptimizer.LOGGER.info(lines.toString().stripTrailing());
    }

    private static void appendTiming(StringBuilder output, String label, long nanos) {
        output.append(String.format(Locale.ROOT, "%-38s %8.3f s%n", label + ":", nanos / 1_000_000_000.0));
    }

    private static final class Session {
        private final long startedAt;
        private final PendingRecipeSync recipeSync;
        private final Map<String, Long> stageNanos = new HashMap<>();
        private final Map<String, Long> stageStartedAt = new HashMap<>();
        private final Map<String, Long> pluginNanos = new HashMap<>();
        private final Set<String> pluginUids = new HashSet<>();
        private final List<String> observedHooks = new ArrayList<>();
        private int ingredientCount = -1;
        private int recipeCategoryCount = -1;

        private Session(long startedAt, PendingRecipeSync recipeSync) {
            this.startedAt = startedAt;
            this.recipeSync = recipeSync;
        }
    }

    private record PendingRecipeSync(long packetStartedAt, long eventAt, int recipeCount) {
    }
}
