package dev.jetoptimizer;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RecipesUpdatedEvent;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Collects only timings and counts around source-confirmed JEI lifecycle boundaries. */
public final class JETOptimizerProfiler {
    private static final String[] RECIPE_INGREDIENT_ROLE_NAMES = {"INPUT", "OUTPUT", "CATALYST", "RENDER_ONLY"};
    private static final ThreadLocal<Session> ACTIVE_SESSION = new ThreadLocal<>();

    private static final AtomicInteger CONNECTION_GENERATION = new AtomicInteger();

    private static final String UNKNOWN_TARGET = "unknown (no server address available)";
    private static final String INTEGRATED_TARGET = "integrated server (local world)";
    private static volatile GenerationSnapshot previousGenerationSnapshot;

    private static volatile long recipePacketStartedAt;
    private static volatile PendingRecipeSync pendingRecipeSync;

    private JETOptimizerProfiler() {
    }

    public static void onLoggingIn() {
        CONNECTION_GENERATION.incrementAndGet();
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
        session.generation = Math.max(1, CONNECTION_GENERATION.get());
        session.startedAtEpochMillis = System.currentTimeMillis();
        session.serverAddress = currentServerAddress();
        session.previousSnapshot = previousGenerationSnapshot;
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
            previousGenerationSnapshot = GenerationSnapshot.from(session);
        }
        if (JETOptimizerConfig.PLUGIN_PROFILING.get()) {
            logPluginTimings(session);
        }
    }

    private static String currentServerAddress() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null) {
                return UNKNOWN_TARGET;
            }
            // An integrated server has no address, so it must be named explicitly. Otherwise a local
            // world and a remote server would both report "unknown" and look interchangeable.
            if (minecraft.hasSingleplayerServer()) {
                return INTEGRATED_TARGET;
            }
            var serverData = minecraft.getCurrentServer();
            if (serverData == null || serverData.ip == null || serverData.ip.isBlank()) {
                return UNKNOWN_TARGET;
            }
            return serverData.ip;
        } catch (RuntimeException | LinkageError e) {
            return UNKNOWN_TARGET;
        }
    }

    public static void beginStage(String stageName) {
        Session session = ACTIVE_SESSION.get();
        if (session != null && JETOptimizerConfig.PROFILING.get()) {
            session.stageStartedAt.put(stageName, System.nanoTime());
            session.observedHooks.add(stageName);
            if (stageName.equals("Ingredient search index construction")) {
                session.searchPrefixMetrics.clear();
                session.searchPrefixStarts.clear();
                session.bakedIndexStarts.clear();
                session.tooltipNanosByType.clear();
                session.tooltipStringStartedAt = null;
                session.currentTooltipTypeUid = null;
                session.currentSearchPrefix = null;
            } else if (stageName.equals("Ingredient sorting")) {
                session.observedHooks.add("IngredientSorter.sortIngredients");
            }
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
            String phase = session.currentPluginPhase();
            if (phase != null) {
                session.pluginNanosByPhase.merge(new PluginCallbackKey(phase, uid), elapsedNanos, Long::sum);
            }
        }
    }

    public static void beginPluginPhase(String title) {
        Session session = ACTIVE_SESSION.get();
        if (session != null) {
            session.pluginPhaseStack.push(new PluginPhaseFrame(title, System.nanoTime()));
            session.observedHooks.add("PluginCaller.callOnPlugins phase timing");
        }
    }

    public static void finishPluginPhase(String title) {
        Session session = ACTIVE_SESSION.get();
        if (session == null || session.pluginPhaseStack.isEmpty()) {
            return;
        }
        PluginPhaseFrame frame = session.pluginPhaseStack.pop();
        if (!frame.title.equals(title)) {
            return;
        }
        long elapsed = System.nanoTime() - frame.startedAt;
        if (JETOptimizerConfig.PROFILING.get()) {
            session.pluginPhaseNanos.merge(title, elapsed, Long::sum);
        }
    }

    public static long beginRecipeAddBatch(int batchSize) {
        Session session = ACTIVE_SESSION.get();
        if (session == null
            || !JETOptimizerConfig.PROFILING.get()
            || !isRegisteringRecipes(session)) {
            return Long.MIN_VALUE;
        }
        session.recipeAddBatches++;
        session.recipeAddRecipeCount += batchSize;
        return System.nanoTime();
    }

    public static void finishRecipeAddBatch(long startedAt) {
        if (startedAt == Long.MIN_VALUE) {
            return;
        }
        Session session = ACTIVE_SESSION.get();
        if (session != null) {
            session.recipeAddNanos += System.nanoTime() - startedAt;
        }
    }

    public static long beginRecipeMapInsert(int roleOrdinal) {
        Session session = ACTIVE_SESSION.get();
        if (session == null
            || !JETOptimizerConfig.PROFILING.get()
            || roleOrdinal < 0
            || roleOrdinal >= RECIPE_INGREDIENT_ROLE_NAMES.length
            || !isRegisteringRecipes(session)) {
            return Long.MIN_VALUE;
        }
        return System.nanoTime();
    }

    public static void finishRecipeMapInsert(int roleOrdinal, long startedAt) {
        if (startedAt == Long.MIN_VALUE || roleOrdinal < 0 || roleOrdinal >= RECIPE_INGREDIENT_ROLE_NAMES.length) {
            return;
        }
        Session session = ACTIVE_SESSION.get();
        if (session != null) {
            session.recipeMapIndexNanos[roleOrdinal] += System.nanoTime() - startedAt;
            session.recipeMapInsertCalls[roleOrdinal]++;
        }
    }

    public static void beginRecipeLayoutBuild() {
        Session session = ACTIVE_SESSION.get();
        if (session != null && JETOptimizerConfig.PROFILING.get() && isRegisteringRecipes(session)) {
            session.recipeLayoutStarts.push(System.nanoTime());
        }
    }

    public static void finishRecipeLayoutBuild() {
        Session session = ACTIVE_SESSION.get();
        if (session != null && !session.recipeLayoutStarts.isEmpty()) {
            session.recipeLayoutNanos += System.nanoTime() - session.recipeLayoutStarts.pop();
            session.recipeLayoutCalls++;
        }
    }

    private static boolean isRegisteringRecipes(Session session) {
        PluginPhaseFrame frame = session.pluginPhaseStack.peek();
        return frame != null && frame.title.equals("Registering recipes");
    }

    public static boolean isPluginProfilingActive() {
        return ACTIVE_SESSION.get() != null
            && JETOptimizerConfig.ENABLED.get()
            && JETOptimizerConfig.PLUGIN_PROFILING.get();
    }

    public static void recordRecipeCategoryCount(int count) {
        Session session = ACTIVE_SESSION.get();
        if (session != null) {
            session.recipeCategoryCount = count;
        }
    }

    public static void recordIngredientCountsAtGuiBuild(int managerRaw, int managerTyped, int filterEntries) {
        Session session = ACTIVE_SESSION.get();
        if (session != null) {
            session.managerRawAtGuiBuild = managerRaw;
            session.managerTypedAtGuiBuild = managerTyped;
            session.filterEntriesAtGuiBuild = filterEntries;
        }
    }

    public static void recordFinalIngredientCounts(int managerRaw, int managerTyped) {
        Session session = ACTIVE_SESSION.get();
        if (session != null) {
            session.ingredientCount = managerRaw;
            session.finalManagerRaw = managerRaw;
            session.finalManagerTyped = managerTyped;
        }
    }

    public static void recordRuntimeIngredientMutation(boolean added, int requestedCount) {
        Session session = ACTIVE_SESSION.get();
        if (session == null) {
            return;
        }
        if (added) {
            session.runtimeIngredientAddCalls++;
            session.runtimeIngredientAddRequests += requestedCount;
        } else {
            session.runtimeIngredientRemoveCalls++;
            session.runtimeIngredientRemoveRequests += requestedCount;
        }
    }

    public static void setCurrentSearchPrefix(String prefixId) {
        Session session = activeSearchSession();
        if (session != null) {
            session.currentSearchPrefix = prefixId;
        }
    }

    public static void beginSearchStringSource(String prefixId) {
        Session session = activeSearchSession();
        if (session != null) {
            session.searchPrefixStarts.put(prefixId, System.nanoTime());
            session.searchPrefixMetrics.computeIfAbsent(prefixId, ignored -> new SearchPrefixMetrics()).getterCalls++;
        }
    }

    public static void finishSearchStringSource(String prefixId, int returnedStringCount) {
        Session session = activeSearchSession();
        if (session == null) {
            return;
        }
        Long startedAt = session.searchPrefixStarts.remove(prefixId);
        if (startedAt == null) {
            return;
        }
        SearchPrefixMetrics metrics = session.searchPrefixMetrics.computeIfAbsent(prefixId, ignored -> new SearchPrefixMetrics());
        metrics.stringSourceNanos += System.nanoTime() - startedAt;
        metrics.returnedStringCandidates += returnedStringCount;
    }

    public static void beginBakedSubstringIndexBuild(int keyCount) {
        Session session = activeSearchSession();
        if (session == null) {
            return;
        }
        String prefixId = session.currentSearchPrefix == null ? "unknown-prefix" : session.currentSearchPrefix;
        session.bakedIndexStarts.push(new BakedIndexFrame(prefixId, System.nanoTime(), keyCount));
    }

    public static void finishBakedSubstringIndexBuild() {
        Session session = activeSearchSession();
        if (session == null || session.bakedIndexStarts.isEmpty()) {
            return;
        }
        BakedIndexFrame frame = session.bakedIndexStarts.pop();
        SearchPrefixMetrics metrics = session.searchPrefixMetrics.computeIfAbsent(frame.prefixId, ignored -> new SearchPrefixMetrics());
        metrics.bakedBuildNanos += System.nanoTime() - frame.startedAt;
        metrics.bakedBuildCalls++;
        metrics.bakedKeyEntries += frame.keyCount;
    }

    private static Session activeSearchSession() {
        Session session = ACTIVE_SESSION.get();
        return session != null
            && JETOptimizerConfig.PROFILING.get()
            && session.stageStartedAt.containsKey("Ingredient search index construction")
            ? session
            : null;
    }

    public static void beginTooltipStringSource(String ingredientTypeUid) {
        Session session = activeSearchSession();
        if (session != null) {
            session.tooltipStringStartedAt = System.nanoTime();
            session.currentTooltipTypeUid = ingredientTypeUid;
        }
    }

    public static void finishTooltipStringSource(String ingredientTypeUid) {
        Session session = activeSearchSession();
        Long startedAt = session == null ? null : session.tooltipStringStartedAt;
        if (session == null || startedAt == null) {
            return;
        }
        session.tooltipStringStartedAt = null;
        session.currentTooltipTypeUid = null;
        long[] metrics = session.tooltipNanosByType.computeIfAbsent(ingredientTypeUid, ignored -> new long[2]);
        metrics[0] += System.nanoTime() - startedAt;
        metrics[1]++;
    }

    private static void logProfile(Session session, long totalNanos) {
        StringBuilder lines = new StringBuilder("[JETOptimizer] JEI initialization profile\n");
        appendConnectionIdentity(lines, session);

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
            .filter(entry -> !entry.getKey().equals("Ingredient sorting"))
            .mapToLong(Map.Entry::getValue)
            .sum();
        appendTiming(lines, "Other (unattributed)", Math.max(0L, totalNanos - topLevelStagesNanos));
        appendTiming(lines, "Total JEI start", totalNanos);
        lines.append("Ingredient count (final raw manager): ").append(formatCount(session.ingredientCount)).append('\n');
        appendIngredientCountAnalysis(lines, session);
        lines.append("Recipe category count: ").append(session.recipeCategoryCount >= 0 ? session.recipeCategoryCount : "unavailable").append('\n');
        if (JETOptimizerConfig.PLUGIN_PROFILING.get()) {
            lines.append("Plugin UIDs observed: ").append(session.pluginUids.size()).append('\n');
        }
        lines.append("Mixin hooks: ").append(session.observedHooks).append('\n');
        appendStructuralComparison(lines, session);
        appendRecipeRegistrationBreakdown(lines, session);
        appendSearchIndexBreakdown(lines, session);
        JETOptimizer.LOGGER.info(lines.toString().stripTrailing());
    }

    private static void appendConnectionIdentity(StringBuilder lines, Session session) {
        lines.append("Connection generation: ").append(session.generation)
            .append(" (").append(describeJoinKind(session)).append(')')
            .append('\n');
        GenerationSnapshot previous = session.previousSnapshot;
        if (previous == null) {
            lines.append("Server address: ").append(session.serverAddress).append('\n');
            return;
        }
        lines.append("Server address: ").append(session.serverAddress)
            .append(previous.serverAddress.equals(session.serverAddress) ? " (unchanged)" : " (CHANGED)")
            .append('\n');
        long gapSeconds = Math.max(0L, session.startedAtEpochMillis - previous.startedAtEpochMillis) / 1000L;
        lines.append("Previous connection in this process: generation ").append(previous.generation)
            .append(", ").append(gapSeconds).append(" s earlier\n");
    }

    /**
     * Generation counts logins, so generation &gt; 1 only proves the process logged in again. It does
     * not prove the target was the same remote server: a local world also logs in and has no address.
     * The kind is therefore derived from both the generation and the target that was observed.
     */
    private static String describeJoinKind(Session session) {
        if (session.generation <= 1) {
            return "first join in this process";
        }
        if (INTEGRATED_TARGET.equals(session.serverAddress)) {
            return "in-game join to a local single player world";
        }
        if (UNKNOWN_TARGET.equals(session.serverAddress)) {
            return "in-game join, target could not be identified";
        }
        return "in-game reconnect to a remote server";
    }

    private static void appendStructuralComparison(StringBuilder lines, Session session) {
        GenerationSnapshot previous = session.previousSnapshot;
        if (previous == null) {
            lines.append("Structural comparison vs previous connection: unavailable (first connection profiled in this process)\n");
            return;
        }

        Map<String, Long> current = session.structuralValues();
        int compared = 0;
        int changed = 0;
        StringBuilder detail = new StringBuilder();
        for (Map.Entry<String, Long> entry : previous.values.entrySet()) {
            Long now = current.get(entry.getKey());
            if (now == null || entry.getValue() == null) {
                continue;
            }
            compared++;
            if (now.equals(entry.getValue())) {
                detail.append("    unchanged ").append(entry.getKey()).append(": ").append(now).append('\n');
            } else {
                changed++;
                detail.append("    CHANGED   ").append(entry.getKey()).append(": ")
                    .append(entry.getValue()).append(" -> ").append(now).append('\n');
            }
        }

        lines.append("Structural comparison vs generation ").append(previous.generation)
            .append(": ").append(changed).append(" of ").append(compared).append(" comparable fields differ\n");
        if (changed > 0) {
            lines.append(detail);
        }
    }

    private static void appendIngredientCountAnalysis(StringBuilder lines, Session session) {
        lines.append("Ingredient manager at GUI list build (raw/typed): ")
            .append(formatCount(session.managerRawAtGuiBuild)).append('/')
            .append(formatCount(session.managerTypedAtGuiBuild)).append('\n');
        lines.append("IngredientFilter base-list entries: ").append(formatCount(session.filterEntriesAtGuiBuild)).append('\n');
        lines.append("Ingredient manager after onRuntimeAvailable (raw/typed): ")
            .append(formatCount(session.finalManagerRaw)).append('/')
            .append(formatCount(session.finalManagerTyped)).append('\n');
        if (session.managerRawAtGuiBuild >= 0 && session.finalManagerRaw >= 0) {
            lines.append("Ingredient manager raw delta during JEI start: ")
                .append(session.finalManagerRaw - session.managerRawAtGuiBuild).append('\n');
        }
        lines.append("Runtime ingredient add requests/calls: ").append(session.runtimeIngredientAddRequests)
            .append('/').append(session.runtimeIngredientAddCalls).append('\n');
        lines.append("Runtime ingredient remove requests/calls: ").append(session.runtimeIngredientRemoveRequests)
            .append('/').append(session.runtimeIngredientRemoveCalls).append('\n');
    }

    private static String formatCount(int count) {
        return count >= 0 ? Integer.toString(count) : "unavailable";
    }

    private static void appendRecipeRegistrationBreakdown(StringBuilder lines, Session session) {
        long recipeStage = session.stageNanos.getOrDefault("Recipe and category registration", -1L);
        if (recipeStage < 0L) {
            return;
        }
        lines.append("Recipe/category registration detail (non-overlapping except noted):\n");

        String[] callbackPhases = {
            "Registering categories",
            "Registering vanilla category extensions",
            "Registering recipe catalysts",
            "Registering advanced plugins",
            "Registering recipes"
        };
        long measured = 0L;
        for (String phase : callbackPhases) {
            long elapsed = session.pluginPhaseNanos.getOrDefault(phase, 0L);
            measured += elapsed;
            if (!phase.equals("Registering recipes")) {
                appendTiming(lines, "  " + phase, elapsed);
            } else {
                long recipeCallbacksWithoutAdd = Math.max(0L, elapsed - session.recipeAddNanos);
                appendTiming(lines, "  registerRecipes plugin work outside addRecipes", recipeCallbacksWithoutAdd);
                appendTiming(lines, "  RecipeManagerInternal.addRecipes (nested)", session.recipeAddNanos);
                measured = measured - elapsed + recipeCallbacksWithoutAdd + session.recipeAddNanos;
                lines.append("  Recipe addRecipes batches/recipes: ")
                    .append(session.recipeAddBatches).append('/').append(session.recipeAddRecipeCount).append('\n');
                appendTiming(lines, "    IngredientSupplierHelper category setRecipe", session.recipeLayoutNanos);
                lines.append("      calls: ").append(session.recipeLayoutCalls).append('\n');
                long recipeMapNanos = 0L;
                for (int role = 0; role < session.recipeMapIndexNanos.length; role++) {
                    long elapsedByRole = session.recipeMapIndexNanos[role];
                    recipeMapNanos += elapsedByRole;
                    appendTiming(lines, "    RecipeMap.addRecipe " + RECIPE_INGREDIENT_ROLE_NAMES[role], elapsedByRole);
                    lines.append("      calls: ").append(session.recipeMapInsertCalls[role]).append('\n');
                }
                appendTiming(
                    lines,
                    "    Other addRecipes work",
                    Math.max(0L, session.recipeAddNanos - session.recipeLayoutNanos - recipeMapNanos)
                );
            }
        }

        long registryBuild = session.stageNanos.getOrDefault("Recipe registry construction", 0L);
        long advancedPluginWiring = session.stageNanos.getOrDefault("Advanced recipe-manager plugin wiring", 0L);
        long compaction = session.stageNanos.getOrDefault("Recipe map compaction", 0L);
        appendTiming(lines, "  Recipe registry construction", registryBuild);
        appendTiming(lines, "  Advanced recipe-manager plugin wiring", advancedPluginWiring);
        appendTiming(lines, "  Recipe map compaction", compaction);
        measured += registryBuild + advancedPluginWiring + compaction;
        appendTiming(lines, "  Other recipe-manager internals", Math.max(0L, recipeStage - measured));

        List<Map.Entry<PluginCallbackKey, Long>> topRecipeCallbacks = session.pluginNanosByPhase.entrySet().stream()
            .filter(entry -> entry.getKey().phase.equals("Registering recipes"))
            .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
            .limit(10)
            .toList();
        if (!topRecipeCallbacks.isEmpty()) {
            lines.append("  Slowest registerRecipes plugin callbacks (nested, top 10):\n");
            for (Map.Entry<PluginCallbackKey, Long> entry : topRecipeCallbacks) {
                appendTiming(lines, "    " + entry.getKey().pluginUid, entry.getValue());
            }
        }
    }

    private static void appendSearchIndexBreakdown(StringBuilder lines, Session session) {
        long searchStage = session.stageNanos.getOrDefault("Ingredient search index construction", -1L);
        if (searchStage < 0L) {
            return;
        }
        lines.append("Ingredient search-index detail (nested in filter construction):\n");
        long sourceNanos = 0L;
        long bakeNanos = 0L;
        for (Map.Entry<String, SearchPrefixMetrics> entry : session.searchPrefixMetrics.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .toList()) {
            SearchPrefixMetrics metrics = entry.getValue();
            sourceNanos += metrics.stringSourceNanos;
            bakeNanos += metrics.bakedBuildNanos;
            appendTiming(lines, "  source strings: " + entry.getKey(), metrics.stringSourceNanos);
            lines.append("    getter calls/candidate strings: ").append(metrics.getterCalls).append('/')
                .append(metrics.returnedStringCandidates).append('\n');
            lines.append("    baked builds/index key entries: ").append(metrics.bakedBuildCalls).append('/')
                .append(metrics.bakedKeyEntries).append('\n');
        }
        appendTiming(lines, "  Baked substring gram-index builds", bakeNanos);
        int bakeCalls = session.searchPrefixMetrics.values().stream()
            .mapToInt(metrics -> metrics.bakedBuildCalls)
            .sum();
        long keyEntries = session.searchPrefixMetrics.values().stream()
            .mapToLong(metrics -> metrics.bakedKeyEntries)
            .sum();
        lines.append("  Baked index build calls/key entries: ").append(bakeCalls).append('/').append(keyEntries).append('\n');
        appendTiming(lines, "  Other search-index work", Math.max(0L, searchStage - sourceNanos - bakeNanos));
        appendTooltipStringsByType(lines, session);
    }

    private static void appendTooltipStringsByType(StringBuilder lines, Session session) {
        if (session.tooltipNanosByType.isEmpty()) {
            return;
        }
        lines.append("  Tooltip search strings by ingredient type (top 6, time/calls):\n");
        session.tooltipNanosByType.entrySet().stream()
            .sorted(Comparator.<Map.Entry<String, long[]>>comparingLong(entry -> entry.getValue()[0]).reversed())
            .limit(6)
            .forEach(entry -> {
                String formatted = String.format(Locale.ROOT, "%.3f s", entry.getValue()[0] / 1_000_000_000.0);
                lines.append("    ").append(entry.getKey()).append(": ").append(formatted)
                    .append('/').append(entry.getValue()[1]).append('\n');
            });
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
        private int generation = 1;
        private long startedAtEpochMillis;
        private String serverAddress = "unknown";
        private GenerationSnapshot previousSnapshot;
        private final Map<String, Long> stageNanos = new HashMap<>();
        private final Map<String, Long> stageStartedAt = new HashMap<>();
        private final Map<String, Long> pluginNanos = new HashMap<>();
        private final Set<String> pluginUids = new HashSet<>();
        private final Set<String> observedHooks = new LinkedHashSet<>();
        private final Map<String, Long> pluginPhaseNanos = new HashMap<>();
        private final Map<PluginCallbackKey, Long> pluginNanosByPhase = new HashMap<>();
        private final Deque<PluginPhaseFrame> pluginPhaseStack = new ArrayDeque<>();
        private final Map<String, SearchPrefixMetrics> searchPrefixMetrics = new HashMap<>();
        private final Map<String, Long> searchPrefixStarts = new HashMap<>();
        private final Deque<BakedIndexFrame> bakedIndexStarts = new ArrayDeque<>();
        private final Deque<Long> recipeLayoutStarts = new ArrayDeque<>();
        private final Map<String, long[]> tooltipNanosByType = new HashMap<>();
        private Long tooltipStringStartedAt;
        private String currentTooltipTypeUid;
        private String currentSearchPrefix;
        private long recipeAddNanos;
        private int recipeAddBatches;
        private int recipeAddRecipeCount;
        private long recipeLayoutNanos;
        private int recipeLayoutCalls;
        private final long[] recipeMapIndexNanos = new long[RECIPE_INGREDIENT_ROLE_NAMES.length];
        private final int[] recipeMapInsertCalls = new int[RECIPE_INGREDIENT_ROLE_NAMES.length];
        private int ingredientCount = -1;
        private int recipeCategoryCount = -1;
        private int managerRawAtGuiBuild = -1;
        private int managerTypedAtGuiBuild = -1;
        private int filterEntriesAtGuiBuild = -1;
        private int finalManagerRaw = -1;
        private int finalManagerTyped = -1;
        private int runtimeIngredientAddCalls;
        private int runtimeIngredientAddRequests;
        private int runtimeIngredientRemoveCalls;
        private int runtimeIngredientRemoveRequests;

        private Session(long startedAt, PendingRecipeSync recipeSync) {
            this.startedAt = startedAt;
            this.recipeSync = recipeSync;
        }

        private String currentPluginPhase() {
            PluginPhaseFrame frame = pluginPhaseStack.peek();
            return frame == null ? null : frame.title;
        }

        /**
         * Connection-independent structural values. These are the only quantities a reuse
         * decision may rely on: no timings, no runtime object references, no ingredient payloads.
         */
        private Map<String, Long> structuralValues() {
            Map<String, Long> values = new LinkedHashMap<>();
            values.put("client recipes (RecipeManager size)", recipeSync == null ? -1L : recipeSync.recipeCount);
            values.put("recipe categories", (long) recipeCategoryCount);
            values.put("addRecipes batches", (long) recipeAddBatches);
            values.put("addRecipes recipes", (long) recipeAddRecipeCount);
            values.put("setRecipe calls", (long) recipeLayoutCalls);
            for (int role = 0; role < RECIPE_INGREDIENT_ROLE_NAMES.length; role++) {
                values.put("RecipeMap.addRecipe " + RECIPE_INGREDIENT_ROLE_NAMES[role] + " calls", (long) recipeMapInsertCalls[role]);
            }
            values.put("ingredient manager raw at GUI list build", (long) managerRawAtGuiBuild);
            values.put("IngredientFilter base-list entries", (long) filterEntriesAtGuiBuild);
            values.put("ingredient manager raw final", (long) finalManagerRaw);
            values.put("runtime ingredient add requests", (long) runtimeIngredientAddRequests);
            values.put("runtime ingredient remove requests", (long) runtimeIngredientRemoveRequests);
            for (Map.Entry<String, SearchPrefixMetrics> entry : searchPrefixMetrics.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList()) {
                values.put("search getter calls " + entry.getKey(), (long) entry.getValue().getterCalls);
                values.put("search candidate strings " + entry.getKey(), entry.getValue().returnedStringCandidates);
            }
            values.put("baked index build calls", bakedBuildCallsTotal());
            values.put("baked index key entries", bakedKeyEntriesTotal());
            for (Map.Entry<String, long[]> entry : tooltipNanosByType.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList()) {
                values.put("tooltip string calls " + entry.getKey(), entry.getValue()[1]);
            }
            values.put("observed plugin UIDs", (long) pluginUids.size());
            values.put("observed plugin UID set hash", pluginUids.isEmpty() ? -1L : pluginUids.hashCode());
            return values;
        }

        private long bakedBuildCallsTotal() {
            long total = 0L;
            for (SearchPrefixMetrics metrics : searchPrefixMetrics.values()) {
                total += metrics.bakedBuildCalls;
            }
            return total;
        }

        private long bakedKeyEntriesTotal() {
            long total = 0L;
            for (SearchPrefixMetrics metrics : searchPrefixMetrics.values()) {
                total += metrics.bakedKeyEntries;
            }
            return total;
        }
    }

    private record GenerationSnapshot(int generation, long startedAtEpochMillis, String serverAddress, Map<String, Long> values) {
        private static GenerationSnapshot from(Session session) {
            return new GenerationSnapshot(
                session.generation,
                session.startedAtEpochMillis,
                session.serverAddress,
                Collections.unmodifiableMap(new LinkedHashMap<>(session.structuralValues()))
            );
        }
    }

    private record PluginCallbackKey(String phase, String pluginUid) {
    }

    private record PluginPhaseFrame(String title, long startedAt) {
    }

    private record BakedIndexFrame(String prefixId, long startedAt, int keyCount) {
    }

    private static final class SearchPrefixMetrics {
        private long stringSourceNanos;
        private long returnedStringCandidates;
        private int getterCalls;
        private long bakedBuildNanos;
        private int bakedBuildCalls;
        private long bakedKeyEntries;
    }

    private record PendingRecipeSync(long packetStartedAt, long eventAt, int recipeCount) {
    }
}
