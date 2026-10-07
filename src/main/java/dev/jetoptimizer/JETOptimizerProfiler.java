package dev.jetoptimizer;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RecipesUpdatedEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Collects only timings and counts around source-confirmed JEI lifecycle boundaries.
 *
 * <p>Every {@code PluginCaller.callOnPlugins} phase is already intercepted, so phases such as
 * {@code Sending Runtime} and {@code Registering ingredients} need no additional mixin: they are
 * recorded in {@link Session#pluginPhaseNanos} and reported from here.
 *
 * <p>Recording paths are the hot code here: recipe map insertion alone runs once per recipe role and
 * search-string extraction once per ingredient per prefix. Those paths keep primitive counters and
 * direct field access, never stream pipelines or {@code Optional}, because both allocate. Stream and
 * {@code Optional} usage is confined to report generation, which runs once per session.
 */
public final class JETOptimizerProfiler {
    private static final String[] RECIPE_INGREDIENT_ROLE_NAMES = {"INPUT", "OUTPUT", "CATALYST", "RENDER_ONLY"};
    private static final ThreadLocal<Session> ACTIVE_SESSION = new ThreadLocal<>();

    private static final AtomicInteger CONNECTION_GENERATION = new AtomicInteger();

    private static final String UNKNOWN_TARGET = "unknown (no server address available)";
    private static final String INTEGRATED_TARGET = "integrated server (local world)";

    private static final String STAGE_SEARCH_INDEX = "Ingredient search index construction";
    private static final String STAGE_GUI_RUNTIME = "JEI GUI runtime construction";
    private static final String STAGE_INGREDIENT_REGISTRATION = "Ingredient registration";

    private static final String PHASE_REGISTERING_RECIPES = "Registering recipes";
    private static final String PHASE_SENDING_RUNTIME = "Sending Runtime";

    /**
     * Plugin phases nested inside {@link #STAGE_INGREDIENT_REGISTRATION}.
     */
    private static final List<String> INGREDIENT_REGISTRATION_PHASES = List.of(
            "Registering ingredients",
            "Registering extra ingredients",
            "Registering search ingredient aliases"
    );

    /**
     * Plugin phases nested inside the recipe manager stage, in source order.
     */
    private static final List<String> RECIPE_REGISTRATION_PHASES = List.of(
            "Registering categories",
            "Registering vanilla category extensions",
            "Registering recipe catalysts",
            "Registering advanced plugins",
            PHASE_REGISTERING_RECIPES
    );

    /**
     * Stages nested inside {@code Ingredient filter construction}. They are excluded from the
     * top-level stage sum so they are not double counted against the remaining startup budget.
     */
    private static final Set<String> NESTED_FILTER_STAGES = Set.of(
            "Ingredient list construction",
            "Ingredient filter construction",
            STAGE_SEARCH_INDEX,
            "Ingredient sorting"
    );

    private static final List<String> RECIPE_MANAGER_STAGES = List.of(
            "Recipe registry construction",
            "Advanced recipe-manager plugin wiring",
            "Recipe map compaction"
    );

    private static volatile GenerationSnapshot previousGenerationSnapshot;

    /**
     * Optimization report rows, one per profiled connection, kept for the life of the process.
     */
    private static final List<OptimizationRecord> OPTIMIZATION_RECORDS = Collections.synchronizedList(new ArrayList<>());

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
        if (!profilingRequested()) {
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
        if (profilingRequested()) {
            recipePacketStartedAt = System.nanoTime();
        }
    }

    /**
     * Profiling flags are read once here so the per-item hooks never touch the config layer, which
     * would otherwise run a config lookup on every recipe ingredient and every tooltip.
     */
    private static boolean profilingRequested() {
        return JETOptimizerConfig.ENABLED.get()
                && (JETOptimizerConfig.PROFILING.get() || JETOptimizerConfig.PLUGIN_PROFILING.get());
    }

    public static void beginJeiStartup() {
        if (!profilingRequested()) {
            return;
        }

        PendingRecipeSync sync = pendingRecipeSync;
        pendingRecipeSync = null;
        Session session = new Session(
                System.nanoTime(),
                sync,
                JETOptimizerConfig.PROFILING.get(),
                JETOptimizerConfig.PLUGIN_PROFILING.get()
        );
        session.generation = Math.max(1, CONNECTION_GENERATION.get());
        session.startedAtEpochMillis = System.currentTimeMillis();
        session.serverAddress = currentServerAddress();
        session.previousSnapshot = previousGenerationSnapshot;
        session.bulkRuntimeRemovalVisibilityEnabled = RuntimeRemovalVisibilityBatch.enabled();
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
        if (session.profiling) {
            logProfile(session, totalNanos);
            logOptimizationReport(session, totalNanos);
            previousGenerationSnapshot = GenerationSnapshot.from(session);
        }
        if (session.pluginProfiling) {
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
            return Optional.ofNullable(minecraft.getCurrentServer())
                    .map(serverData -> serverData.ip)
                    .filter(ip -> !ip.isBlank())
                    .orElse(UNKNOWN_TARGET);
        } catch (RuntimeException | LinkageError e) {
            return UNKNOWN_TARGET;
        }
    }

    public static void beginStage(String stageName) {
        Session session = ACTIVE_SESSION.get();
        if (session == null || !session.profiling) {
            return;
        }
        session.stageStartedAt.put(stageName, System.nanoTime());
        session.observedHooks.add(stageName);
        // A phase that opens a stage inside itself is not an independent region, so it must not be
        // counted again on top of that stage.
        for (PluginPhaseFrame frame : session.pluginPhaseStack) {
            frame.containsStage = true;
        }
        switch (stageName) {
            case STAGE_SEARCH_INDEX -> session.resetSearchTracking();
            case "Ingredient sorting" -> session.observedHooks.add("IngredientSorter.sortIngredients");
            default -> {
            }
        }
    }

    public static void finishStage(String stageName) {
        Session session = ACTIVE_SESSION.get();
        if (session == null || !session.profiling) {
            return;
        }
        if (STAGE_SEARCH_INDEX.equals(stageName)) {
            session.searchStageActive = false;
        }
        Long startedAt = session.stageStartedAt.remove(stageName);
        if (startedAt != null) {
            session.stageNanos.merge(stageName, System.nanoTime() - startedAt, Long::sum);
        }
    }

    public static void recordPluginCallback(String uid, long elapsedNanos) {
        Session session = ACTIVE_SESSION.get();
        if (session == null || !session.pluginProfiling) {
            return;
        }
        session.pluginNanos.merge(uid, elapsedNanos, Long::sum);
        session.pluginUids.add(uid);
        String phase = session.currentPluginPhase();
        if (phase != null) {
            session.pluginNanosByPhase.merge(new PluginCallbackKey(phase, uid), elapsedNanos, Long::sum);
        }
    }

    public static void beginPluginPhase(String title) {
        Session session = ACTIVE_SESSION.get();
        if (session == null) {
            return;
        }
        session.pluginPhaseStack.push(new PluginPhaseFrame(title, System.nanoTime(), session.stageStartedAt.isEmpty()));
        session.observedHooks.add("PluginCaller.callOnPlugins phase timing");
        session.refreshRegisteringRecipesFlag();
    }

    public static void finishPluginPhase(String title) {
        Session session = ACTIVE_SESSION.get();
        if (session == null || session.pluginPhaseStack.isEmpty()) {
            return;
        }
        PluginPhaseFrame frame = session.pluginPhaseStack.pop();
        session.refreshRegisteringRecipesFlag();
        if (!frame.title().equals(title)) {
            return;
        }
        long elapsed = System.nanoTime() - frame.startedAt();
        if (!session.profiling) {
            return;
        }
        session.pluginPhaseNanos.merge(title, elapsed, Long::sum);
        if (frame.isTopLevel()) {
            session.topLevelPluginPhaseNanos.merge(title, elapsed, Long::sum);
        }
    }

    public static long beginRecipeAddBatch(int batchSize) {
        Session session = ACTIVE_SESSION.get();
        if (session == null || !session.profiling || !session.registeringRecipes) {
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
                || !session.profiling
                || !session.registeringRecipes
                || roleOrdinal < 0
                || roleOrdinal >= RECIPE_INGREDIENT_ROLE_NAMES.length) {
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
        if (session != null && session.profiling && session.registeringRecipes) {
            session.recipeLayoutStarts.push(System.nanoTime());
        }
    }

    public static void recordRecipeLayoutBuilderAcquisition(boolean reused) {
        Session session = ACTIVE_SESSION.get();
        if (session != null && session.profiling) {
            if (reused) {
                session.recipeLayoutBuildersReused++;
            } else {
                session.recipeLayoutBuildersCreated++;
            }
        }
    }

    public static void finishRecipeLayoutBuild() {
        Session session = ACTIVE_SESSION.get();
        if (session != null && !session.recipeLayoutStarts.isEmpty()) {
            session.recipeLayoutNanos += System.nanoTime() - session.recipeLayoutStarts.pop();
            session.recipeLayoutCalls++;
        }
    }

    public static boolean isPluginProfilingActive() {
        Session session = ACTIVE_SESSION.get();
        return session != null && session.pluginProfiling;
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

    public static void recordSuccessfulRuntimeIngredientRemoval() {
        Session session = ACTIVE_SESSION.get();
        if (session != null && session.profiling) {
            session.runtimeIngredientRemovedEntries++;
        }
    }

    public static void recordRuntimeRemovalVisibilityBatch(
            int requestedIngredients,
            int individualNotifications,
            int singleDispatches,
            int batchedIngredients,
            int batchedDispatches,
            boolean listenersCompatible,
            long elapsedNanos
    ) {
        Session session = ACTIVE_SESSION.get();
        if (session == null || !session.profiling) {
            return;
        }
        session.runtimeRemovalVisibilityCalls++;
        session.runtimeRemovalVisibilityRequested += requestedIngredients;
        session.runtimeRemovalVisibilityIndividualNotifications += individualNotifications;
        session.runtimeRemovalVisibilitySingleDispatches += singleDispatches;
        session.runtimeRemovalVisibilityBatchedIngredients += batchedIngredients;
        session.runtimeRemovalVisibilityBatchedDispatches += batchedDispatches;
        session.runtimeRemovalVisibilityNanos += elapsedNanos;
        if (listenersCompatible) {
            session.runtimeRemovalVisibilityOptimizedCalls++;
        } else {
            session.runtimeRemovalVisibilityFallbackCalls++;
        }
    }

    public static void setCurrentSearchPrefix(String prefixId) {
        Session session = searchSession();
        if (session != null) {
            session.currentSearchPrefix = prefixId;
        }
    }

    public static void beginSearchStringSource(String prefixId) {
        Session session = searchSession();
        if (session != null) {
            session.metricsFor(prefixId).start();
        }
    }

    public static void finishSearchStringSource(String prefixId, int returnedStringCount) {
        Session session = searchSession();
        if (session == null) {
            return;
        }
        SearchPrefixMetrics metrics = session.searchPrefixMetrics.get(prefixId);
        if (metrics == null || !metrics.active) {
            return;
        }
        metrics.stringSourceNanos += System.nanoTime() - metrics.startedAt;
        metrics.returnedStringCandidates += returnedStringCount;
        metrics.active = false;
    }

    public static void beginBakedSubstringIndexBuild(int keyCount) {
        Session session = searchSession();
        if (session == null) {
            return;
        }
        String prefixId = session.currentSearchPrefix == null ? "unknown-prefix" : session.currentSearchPrefix;
        session.bakedIndexStarts.push(new BakedIndexFrame(prefixId, System.nanoTime(), keyCount));
    }

    public static void finishBakedSubstringIndexBuild() {
        Session session = searchSession();
        if (session == null || session.bakedIndexStarts.isEmpty()) {
            return;
        }
        BakedIndexFrame frame = session.bakedIndexStarts.pop();
        SearchPrefixMetrics metrics = session.metricsFor(frame.prefixId());
        metrics.bakedBuildNanos += System.nanoTime() - frame.startedAt();
        metrics.bakedBuildCalls++;
        metrics.bakedKeyEntries += frame.keyCount();
    }

    /**
     * The ingredient type uid is resolved once per tooltip and reused for the return hook, so the
     * mixin does not have to walk the typed ingredient twice per ingredient.
     */
    public static void beginTooltipStringSource(String ingredientTypeUid) {
        Session session = searchSession();
        if (session == null) {
            return;
        }
        session.tooltipStartedAt = System.nanoTime();
        session.tooltipTypeUid = ingredientTypeUid;
    }

    public static void recordTooltipSearchSkipped() {
        Session session = ACTIVE_SESSION.get();
        if (session != null && session.profiling) {
            session.tooltipSearchSkipped++;
        }
    }

    public static void finishTooltipStringSource() {
        Session session = searchSession();
        if (session == null || session.tooltipStartedAt == 0L) {
            return;
        }
        long[] metrics = session.tooltipNanosByType.computeIfAbsent(session.tooltipTypeUid, uid -> new long[2]);
        metrics[0] += System.nanoTime() - session.tooltipStartedAt;
        metrics[1]++;
        session.tooltipStartedAt = 0L;
        session.tooltipTypeUid = null;
    }

    /**
     * Times JEI's pure search-text pipeline. The tooltip has already been rendered when this runs,
     * so this window contains only {@code getString}, chat-format removal, lowercasing, whitespace
     * splitting and set insertion. It is the ceiling for any optimization that does not touch
     * mod-supplied tooltip rendering.
     *
     * <p>Gated on the session rather than the search stage because chat-format stripping is also
     * reached from display-name handling during list construction, which happens earlier.
     */
    public static void beginSearchTextPipeline() {
        Session session = ACTIVE_SESSION.get();
        if (session == null || session.searchTextPipelineStartedAt != 0L) {
            return;
        }
        session.searchTextPipelineStartedAt = System.nanoTime();
        session.searchTextPipelineCalls++;
    }

    public static void finishSearchTextPipeline() {
        Session session = ACTIVE_SESSION.get();
        if (session == null || session.searchTextPipelineStartedAt == 0L) {
            return;
        }
        session.searchTextPipelineNanos += System.nanoTime() - session.searchTextPipelineStartedAt;
        session.searchTextPipelineStartedAt = 0L;
    }

    public static void beginFastTextStrip() {
        Session session = ACTIVE_SESSION.get();
        if (session == null) {
            return;
        }
        session.fastStripCalls++;
        if (session.searchTextPipelineStartedAt != 0L && session.fastStripStartedAt == 0L) {
            session.fastStripStartedAt = System.nanoTime();
        }
    }

    public static void finishFastTextStrip(boolean applied) {
        Session session = ACTIVE_SESSION.get();
        if (session == null) {
            return;
        }
        if (session.fastStripStartedAt != 0L) {
            session.fastStripNanos += System.nanoTime() - session.fastStripStartedAt;
            session.fastStripStartedAt = 0L;
        }
        if (applied) {
            session.fastStripApplied++;
        }
    }

    public static void beginFastTextSplit() {
        Session session = ACTIVE_SESSION.get();
        if (session == null) {
            return;
        }
        session.fastSplitCalls++;
        if (session.searchTextPipelineStartedAt != 0L && session.fastSplitStartedAt == 0L) {
            session.fastSplitStartedAt = System.nanoTime();
        }
    }

    public static void finishFastTextSplit(boolean applied) {
        Session session = ACTIVE_SESSION.get();
        if (session == null) {
            return;
        }
        if (session.fastSplitStartedAt != 0L) {
            session.fastSplitNanos += System.nanoTime() - session.fastSplitStartedAt;
            session.fastSplitStartedAt = 0L;
        }
        if (applied) {
            session.fastSplitApplied++;
        }
    }

    /**
     * Records that a fast path handed control back to JEI's original implementation.
     */
    public static void recordOptimizationFallback(String optimization) {
        Session session = ACTIVE_SESSION.get();
        if (session == null) {
            return;
        }
        session.optimizationFallbacks.merge(optimization, 1, Integer::sum);
    }

    /**
     * Records one block of {@code JeiGuiStarter.start} between two consecutive gates. Gates are
     * keyed by label so a block is never double counted if a gate target is missing on some version.
     */
    public static void recordGuiRuntimeGate(String label, long nanos) {
        Session session = ACTIVE_SESSION.get();
        if (session == null || !session.profiling || nanos < 0L) {
            return;
        }
        session.guiRuntimeGates.merge(label, nanos, Long::sum);
    }

    /**
     * Records the one-callback, generation-local KubeJS remote item removal candidate index.
     */
    public static void recordKubeJSItemRemovalIndex(
            boolean enabled,
            int filterCount,
            int patternCount,
            int sourceEntries,
            int filterItemRegistryIds,
            int candidateEntries,
            int candidateLoopExpected,
            int candidateLoopEntries,
            int predicateTestsBypassed,
            int originalPredicateTests,
            int fullScanFallbacks,
            int fallbackFilters,
            int managerRemovalRequestEntries,
            int indexBuilds,
            int leafItemValues,
            long indexNanos
    ) {
        Session session = ACTIVE_SESSION.get();
        if (session == null || !session.profiling) {
            return;
        }
        session.observedHooks.add("KubeJSJEIPlugin.onRuntimeAvailable item removal index");
        session.kubeJsItemRemovalObserved = true;
        session.kubeJsItemRemovalEnabled = enabled;
        session.kubeJsItemRemovalFilterCount += filterCount;
        session.kubeJsItemRemovalPatternCount += patternCount;
        session.kubeJsItemRemovalSourceEntries += sourceEntries;
        session.kubeJsItemRemovalFilterItemRegistryIds += filterItemRegistryIds;
        session.kubeJsItemRemovalCandidateEntries += candidateEntries;
        session.kubeJsItemRemovalCandidateLoopExpected += candidateLoopExpected;
        session.kubeJsItemRemovalCandidateLoopEntries += candidateLoopEntries;
        session.kubeJsItemRemovalPredicateTestsBypassed += predicateTestsBypassed;
        session.kubeJsItemRemovalOriginalPredicateTests += originalPredicateTests;
        session.kubeJsItemRemovalFullScanFallbacks += fullScanFallbacks;
        session.kubeJsItemRemovalFallbackFilters += fallbackFilters;
        session.kubeJsItemRemovalRequestEntries += managerRemovalRequestEntries;
        session.kubeJsItemRemovalIndexBuilds += indexBuilds;
        session.kubeJsItemRemovalLeafItemValues += leafItemValues;
        session.kubeJsItemRemovalIndexNanos += indexNanos;
    }

    /**
     * Stores the checkpoint report built inside KubeJSJEIPlugin.onRuntimeAvailable for this generation.
     */
    public static void recordKubeJSCallbackPhases(String report) {
        Session session = ACTIVE_SESSION.get();
        if (session == null || !session.profiling || report == null || report.isEmpty()) {
            return;
        }
        session.observedHooks.add("KubeJSJEIPlugin.onRuntimeAvailable phase probe");
        session.kubeJSCallbackPhaseReport = report;
    }

    private static Session searchSession() {
        Session session = ACTIVE_SESSION.get();
        return session != null && session.searchStageActive ? session : null;
    }

    private static void logProfile(Session session, long totalNanos) {
        StringBuilder lines = new StringBuilder("[JETOptimizer] JEI initialization profile\n");
        appendConnectionIdentity(lines, session);

        if (session.recipeSync != null) {
            PendingRecipeSync sync = session.recipeSync;
            if (sync.packetStartedAt() > 0L) {
                appendTiming(lines, "Recipe handler to JEI start", session.startedAt - sync.packetStartedAt());
                appendTiming(lines, "Recipe handler to RecipesUpdatedEvent", sync.eventAt() - sync.packetStartedAt());
                appendTiming(lines, "RecipesUpdatedEvent to JEI start", session.startedAt - sync.eventAt());
            } else {
                appendTiming(lines, "RecipesUpdatedEvent to JEI start", session.startedAt - sync.eventAt());
            }
            lines.append("Client recipes: ").append(sync.recipeCount()).append('\n');
        } else {
            lines.append("Recipe synchronization timing: unavailable (no matching RecipesUpdatedEvent)\n");
        }

        session.stageNanos.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> appendTiming(lines, entry.getKey(), entry.getValue()));
        long topLevelStagesNanos = session.stageNanos.entrySet().stream()
                .filter(entry -> !NESTED_FILTER_STAGES.contains(entry.getKey()))
                .mapToLong(Map.Entry::getValue)
                .sum();
        long topLevelPluginNanos = sumValues(session.topLevelPluginPhaseNanos);
        appendTopLevelPluginPhases(lines, session);
        appendTiming(
                lines,
                "Other (unattributed)",
                Math.max(0L, totalNanos - topLevelStagesNanos - topLevelPluginNanos)
        );
        appendTiming(lines, "Total JEI start", totalNanos);
        lines.append("Ingredient count (final raw manager): ").append(formatCount(session.ingredientCount)).append('\n');
        appendIngredientCountAnalysis(lines, session);
        lines.append("Recipe category count: ")
                .append(formatCount(session.recipeCategoryCount))
                .append('\n');
        if (session.pluginProfiling) {
            lines.append("Plugin UIDs observed: ").append(session.pluginUids.size()).append('\n');
        }
        lines.append("Mixin hooks: ").append(session.observedHooks).append('\n');
        appendStructuralComparison(lines, session);
        appendRecipeRegistrationBreakdown(lines, session);
        appendIngredientRegistrationBreakdown(lines, session);
        appendSendingRuntimeBreakdown(lines, session);
        appendKubeJSItemRemovalIndex(lines, session);
        appendKubeJSCallbackPhases(lines, session);
        appendSearchIndexBreakdown(lines, session);
        appendGuiRuntimeGates(lines, session);
        JETOptimizer.LOGGER.info(lines.toString().stripTrailing());
    }

    private static void appendConnectionIdentity(StringBuilder lines, Session session) {
        lines.append("Connection generation: ").append(session.generation)
                .append(" (").append(describeJoinKind(session)).append(')')
                .append('\n');
        Optional.ofNullable(session.previousSnapshot).ifPresentOrElse(previous -> {
            lines.append("Server address: ").append(session.serverAddress)
                    .append(previous.serverAddress().equals(session.serverAddress) ? " (unchanged)" : " (CHANGED)")
                    .append('\n');
            long gapSeconds = Math.max(0L, session.startedAtEpochMillis - previous.startedAtEpochMillis()) / 1000L;
            lines.append("Previous connection in this process: generation ").append(previous.generation())
                    .append(", ").append(gapSeconds).append(" s earlier\n");
        }, () -> lines.append("Server address: ").append(session.serverAddress).append('\n'));
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
        Optional.ofNullable(session.previousSnapshot).ifPresentOrElse(previous -> {
            Map<String, Long> current = session.structuralValues();
            List<StructuralDifference> differences = previous.values().entrySet().stream()
                    .filter(entry -> current.containsKey(entry.getKey()))
                    .map(entry -> {
                        Long after = current.get(entry.getKey());
                        return new StructuralDifference(entry.getKey(), entry.getValue(), after, !after.equals(entry.getValue()));
                    })
                    .toList();
            long changed = differences.stream().filter(StructuralDifference::changed).count();
            lines.append("Structural comparison vs generation ").append(previous.generation())
                    .append(": ").append(changed).append(" of ").append(differences.size())
                    .append(" comparable fields differ\n");
            if (changed > 0L) {
                differences.forEach(difference -> lines.append("    ")
                        .append(difference.changed() ? "CHANGED  " : "unchanged")
                        .append(' ').append(difference.key()).append(": ")
                        .append(difference.changed()
                                ? difference.before() + " -> " + difference.after()
                                : String.valueOf(difference.after()))
                        .append('\n'));
            }
        }, () -> lines.append("Structural comparison vs previous connection: unavailable (first connection profiled in this process)\n"));
    }

    private static void appendIngredientCountAnalysis(StringBuilder lines, Session session) {
        lines.append("Ingredient manager at GUI list build (raw/typed): ")
                .append(formatCount(session.managerRawAtGuiBuild)).append('/')
                .append(formatCount(session.managerTypedAtGuiBuild)).append('\n');
        lines.append("IngredientFilter base-list entries: ")
                .append(formatCount(session.filterEntriesAtGuiBuild)).append('\n');
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

    private static long sumValues(Map<String, Long> values) {
        return values.values().stream().mapToLong(Long::longValue).sum();
    }

    /**
     * Phases that ran while no measured stage was open, for example {@code Sending Runtime}. They are
     * subtracted from the unattributed remainder, otherwise the remainder hides them entirely.
     */
    private static void appendTopLevelPluginPhases(StringBuilder lines, Session session) {
        if (session.topLevelPluginPhaseNanos.isEmpty()) {
            return;
        }
        lines.append("Top-level plugin phases (outside every measured stage):\n");
        session.topLevelPluginPhaseNanos.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .forEach(entry -> appendTiming(lines, "  " + entry.getKey(), entry.getValue()));
    }

    private static void appendSlowestPluginCallbacks(
            StringBuilder lines,
            Session session,
            String phase,
            int limit,
            String header,
            String indent
    ) {
        List<Map.Entry<PluginCallbackKey, Long>> slowest = session.pluginNanosByPhase.entrySet().stream()
                .filter(entry -> phase.equals(entry.getKey().phase()))
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .limit(limit)
                .toList();
        if (slowest.isEmpty()) {
            return;
        }
        lines.append(header).append(":\n");
        slowest.forEach(entry -> appendTiming(lines, indent + entry.getKey().pluginUid(), entry.getValue()));
    }

    private static void appendRecipeRegistrationBreakdown(StringBuilder lines, Session session) {
        long recipeStage = session.stageNanos.getOrDefault("Recipe and category registration", -1L);
        if (recipeStage < 0L) {
            return;
        }
        lines.append("Recipe/category registration detail (non-overlapping except noted):\n");

        long measured = 0L;
        for (String phase : RECIPE_REGISTRATION_PHASES) {
            long elapsed = session.pluginPhaseNanos.getOrDefault(phase, 0L);
            if (!PHASE_REGISTERING_RECIPES.equals(phase)) {
                measured += elapsed;
                appendTiming(lines, "  " + phase, elapsed);
                continue;
            }
            long recipeCallbacksWithoutAdd = Math.max(0L, elapsed - session.recipeAddNanos);
            appendTiming(lines, "  registerRecipes plugin work outside addRecipes", recipeCallbacksWithoutAdd);
            appendTiming(lines, "  RecipeManagerInternal.addRecipes (nested)", session.recipeAddNanos);
            measured += recipeCallbacksWithoutAdd + session.recipeAddNanos;
            lines.append("  Recipe addRecipes batches/recipes: ")
                    .append(session.recipeAddBatches).append('/').append(session.recipeAddRecipeCount).append('\n');
            appendTiming(lines, "    IngredientSupplierHelper category setRecipe", session.recipeLayoutNanos);
            lines.append("      calls: ").append(session.recipeLayoutCalls).append('\n');
            lines.append("      recipe layout builders created/reused: ")
                    .append(session.recipeLayoutBuildersCreated).append('/')
                    .append(session.recipeLayoutBuildersReused).append('\n');
            long recipeMapNanos = 0L;
            for (int role = 0; role < RECIPE_INGREDIENT_ROLE_NAMES.length; role++) {
                recipeMapNanos += session.recipeMapIndexNanos[role];
                appendTiming(lines, "    RecipeMap.addRecipe " + RECIPE_INGREDIENT_ROLE_NAMES[role], session.recipeMapIndexNanos[role]);
                lines.append("      calls: ").append(session.recipeMapInsertCalls[role]).append('\n');
            }
            appendTiming(
                    lines,
                    "    Other addRecipes work",
                    Math.max(0L, session.recipeAddNanos - session.recipeLayoutNanos - recipeMapNanos)
            );
        }

        for (String stage : RECIPE_MANAGER_STAGES) {
            long elapsed = session.stageNanos.getOrDefault(stage, 0L);
            measured += elapsed;
            appendTiming(lines, "  " + stage, elapsed);
        }
        appendTiming(lines, "  Other recipe-manager internals", Math.max(0L, recipeStage - measured));
        appendSlowestPluginCallbacks(
                lines,
                session,
                PHASE_REGISTERING_RECIPES,
                10,
                "  Slowest registerRecipes plugin callbacks (nested, top 10)",
                "    "
        );
    }

    /**
     * Ingredient registration is a single JEI stage made of three plugin phases. The phases also run
     * outside plugin callback time, so the remainder keeps their unattributed JEI-side work visible.
     */
    private static void appendIngredientRegistrationBreakdown(StringBuilder lines, Session session) {
        long stage = session.stageNanos.getOrDefault(STAGE_INGREDIENT_REGISTRATION, -1L);
        if (stage < 0L) {
            return;
        }
        lines.append("Ingredient registration detail (nested plugin phases):\n");
        long nested = 0L;
        for (String phase : INGREDIENT_REGISTRATION_PHASES) {
            long elapsed = session.pluginPhaseNanos.getOrDefault(phase, 0L);
            nested += elapsed;
            appendTiming(lines, "  " + phase, elapsed);
            appendSlowestPluginCallbacks(lines, session, phase, 5, "    Slowest callbacks (top 5)", "      ");
        }
        appendTiming(lines, "  Ingredient registration outside plugin phases", Math.max(0L, stage - nested));
    }

    /**
     * {@code Sending Runtime} runs after every measured stage, so it used to be indistinguishable from
     * the unattributed remainder. It is the one remaining large region with no other attribution.
     */
    private static void appendSendingRuntimeBreakdown(StringBuilder lines, Session session) {
        long elapsed = session.pluginPhaseNanos.getOrDefault(PHASE_SENDING_RUNTIME, -1L);
        if (elapsed < 0L) {
            return;
        }
        long attributed = session.pluginNanosByPhase.entrySet().stream()
                .filter(entry -> PHASE_SENDING_RUNTIME.equals(entry.getKey().phase()))
                .mapToLong(Map.Entry::getValue)
                .sum();
        lines.append("Sending Runtime detail (outside every measured stage):\n");
        appendTiming(lines, "  onRuntimeAvailable across plugins", elapsed);
        appendTiming(lines, "  Plugin callback time not attributed", Math.max(0L, elapsed - attributed));
        appendSlowestPluginCallbacks(
                lines,
                session,
                PHASE_SENDING_RUNTIME,
                10,
                "  Slowest onRuntimeAvailable callbacks (top 10)",
                "    "
        );
    }

    private static void appendKubeJSItemRemovalIndex(StringBuilder lines, Session session) {
        if (!session.kubeJsItemRemovalObserved) {
            return;
        }
        lines.append("KubeJS remote item-removal ID index (inside onRuntimeAvailable):\n");
        lines.append("  optimization: ").append(session.kubeJsItemRemovalEnabled ? "enabled" : "disabled").append('\n');
        lines.append("  filter trees/pattern entries: ").append(session.kubeJsItemRemovalFilterCount).append('/')
                .append(session.kubeJsItemRemovalPatternCount).append('\n');
        lines.append("  leaf candidate stacks/item registry IDs: ").append(session.kubeJsItemRemovalLeafItemValues).append('/')
                .append(session.kubeJsItemRemovalFilterItemRegistryIds).append('\n');
        lines.append("  source entries/candidate dense IDs: ").append(session.kubeJsItemRemovalSourceEntries).append('/')
                .append(session.kubeJsItemRemovalCandidateEntries).append('\n');
        lines.append("  KubeJS loop entries expected/observed: ").append(session.kubeJsItemRemovalCandidateLoopExpected).append('/')
                .append(session.kubeJsItemRemovalCandidateLoopEntries).append('\n');
        lines.append("  Ingredient.test calls bypassed/original fallback: ")
                .append(session.kubeJsItemRemovalPredicateTestsBypassed).append('/')
                .append(session.kubeJsItemRemovalOriginalPredicateTests).append('\n');
        lines.append("  manager removal request entries: ").append(session.kubeJsItemRemovalRequestEntries).append('\n');
        lines.append("  index builds/fallback filters/full-scan fallbacks: ")
                .append(session.kubeJsItemRemovalIndexBuilds).append('/')
                .append(session.kubeJsItemRemovalFallbackFilters).append('/')
                .append(session.kubeJsItemRemovalFullScanFallbacks).append('\n');
        appendTiming(lines, "  dense-ID candidate-index construction", session.kubeJsItemRemovalIndexNanos);
    }

    private static void appendKubeJSCallbackPhases(StringBuilder lines, Session session) {
        if (session.kubeJSCallbackPhaseReport == null) {
            return;
        }
        lines.append(session.kubeJSCallbackPhaseReport);
    }

    private static void appendSearchIndexBreakdown(StringBuilder lines, Session session) {
        long searchStage = session.stageNanos.getOrDefault(STAGE_SEARCH_INDEX, -1L);
        if (searchStage < 0L) {
            return;
        }
        lines.append("Ingredient search-index detail (nested in filter construction):\n");
        long sourceNanos = 0L;
        long bakeNanos = 0L;
        int bakeCalls = 0;
        long keyEntries = 0L;
        for (Map.Entry<String, SearchPrefixMetrics> entry : session.searchPrefixMetrics.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList()) {
            SearchPrefixMetrics metrics = entry.getValue();
            sourceNanos += metrics.stringSourceNanos;
            bakeNanos += metrics.bakedBuildNanos;
            bakeCalls += metrics.bakedBuildCalls;
            keyEntries += metrics.bakedKeyEntries;
            appendTiming(lines, "  source strings: " + entry.getKey(), metrics.stringSourceNanos);
            lines.append("    getter calls/candidate strings: ").append(metrics.getterCalls).append('/')
                    .append(metrics.returnedStringCandidates).append('\n');
            lines.append("    baked builds/index key entries: ").append(metrics.bakedBuildCalls).append('/')
                    .append(metrics.bakedKeyEntries).append('\n');
        }
        appendTiming(lines, "  Baked substring gram-index builds", bakeNanos);
        lines.append("  Baked index build calls/key entries: ").append(bakeCalls).append('/').append(keyEntries).append('\n');
        appendTiming(lines, "  Other search-index work", Math.max(0L, searchStage - sourceNanos - bakeNanos));
        appendTooltipStringsByType(lines, session);
        if (session.tooltipSearchSkipped > 0) {
            lines.append("  fast-join tooltip search: skipped tooltip rendering for ")
                    .append(session.tooltipSearchSkipped)
                    .append(" ingredients; displayed hover tooltips remain unchanged\n");
        }
        appendSearchTextOptimization(lines, session);
    }

    /**
     * The gated blocks inside {@code JeiGuiStarter.start}. This is the only breakdown of the GUI
     * runtime stage beyond the two blocks JEI times itself, so it is what attributes the part of the
     * stage that used to be unattributed.
     */
    private static void appendGuiRuntimeGates(StringBuilder lines, Session session) {
        if (session.guiRuntimeGates.isEmpty()) {
            return;
        }
        long stage = session.stageNanos.getOrDefault(STAGE_GUI_RUNTIME, 0L);
        long gates = sumValues(session.guiRuntimeGates);
        lines.append("GUI runtime construction, gated blocks (sum ").append(formatSeconds(gates)).append(
                        " of ").append(formatSeconds(stage)).append(", ").append(session.guiRuntimeGates.size())
                .append(" gates):\n");
        session.guiRuntimeGates.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .forEach(entry -> lines.append("    ").append(entry.getKey()).append(": ")
                        .append(formatSeconds(entry.getValue())).append('\n'));
        appendTiming(lines, "  GUI runtime construction not covered by a gate",
                Math.max(0L, stage - gates));
    }

    /**
     * The fast-path counters only start once the pipeline window is open. The residual is pipeline
     * work outside those measured replacements, not an estimate of savings versus the regexes.
     */
    private static void appendSearchTextOptimization(StringBuilder lines, Session session) {
        lines.append("  Search-text pipeline (pure work inside getStrings): ")
                .append(formatSeconds(session.searchTextPipelineNanos))
                .append(" over ").append(session.searchTextPipelineCalls).append(" calls\n");
        lines.append("    fast chat-format stripping: ")
                .append(formatSeconds(session.fastStripNanos))
                .append(" over ").append(session.fastStripCalls).append(" calls (")
                .append(session.fastStripApplied).append(" applied)\n");
        lines.append("    fast whitespace splitting: ")
                .append(formatSeconds(session.fastSplitNanos))
                .append(" over ").append(session.fastSplitCalls).append(" calls (")
                .append(session.fastSplitApplied).append(" applied)\n");
        appendTiming(lines, "    pipeline work outside fast paths", Math.max(0L,
                session.searchTextPipelineNanos - session.fastStripNanos - session.fastSplitNanos));
        if (!session.optimizationFallbacks.isEmpty()) {
            lines.append("    fallbacks to JEI implementation: ")
                    .append(session.optimizationFallbacks).append('\n');
        }
    }

    private static void appendTooltipStringsByType(StringBuilder lines, Session session) {
        if (session.tooltipNanosByType.isEmpty()) {
            return;
        }
        lines.append("  Tooltip search strings by ingredient type (top 6, time/calls):\n");
        session.tooltipNanosByType.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, long[]>>comparingLong(entry -> entry.getValue()[0]).reversed())
                .limit(6)
                .forEach(entry -> lines.append("    ").append(entry.getKey()).append(": ")
                        .append(formatSeconds(entry.getValue()[0]))
                        .append('/').append(entry.getValue()[1]).append('\n'));
    }

    /**
     * Per-generation summary of what the shipped optimizations actually did, kept across
     * connections so a cold join and a later reconnect can be read side by side.
     *
     * <p>The residual pipeline figure is not a savings estimate: it is the pipeline window minus
     * time spent inside the measured fast paths. Savings versus the original regexes require an
     * independent baseline and are not inferred here.
     */
    private static void logOptimizationReport(Session session, long totalNanos) {
        long pipelineOutsideFastPathsNanos = Math.max(0L,
                session.searchTextPipelineNanos - session.fastStripNanos - session.fastSplitNanos);
        OPTIMIZATION_RECORDS.add(new OptimizationRecord(
                session.generation,
                describeJoinKind(session),
                session.serverAddress,
                totalNanos,
                session.stageNanos.getOrDefault(STAGE_SEARCH_INDEX, 0L),
                tooltipSearchNanos(session),
                session.stageNanos.getOrDefault(STAGE_INGREDIENT_REGISTRATION, 0L),
                session.pluginPhaseNanos.getOrDefault(PHASE_REGISTERING_RECIPES, 0L),
                session.pluginNanosByPhase.getOrDefault(new PluginCallbackKey(PHASE_SENDING_RUNTIME, "kubejs:jei"), 0L),
                SearchTextOptimization.enabled(),
                session.searchTextPipelineNanos,
                session.searchTextPipelineCalls,
                session.fastStripNanos,
                session.fastStripCalls,
                session.fastSplitNanos,
                session.fastSplitCalls,
                pipelineOutsideFastPathsNanos,
                session.kubeJsItemRemovalObserved,
                session.kubeJsItemRemovalEnabled,
                session.kubeJsItemRemovalIndexNanos,
                session.kubeJsItemRemovalSourceEntries,
                session.kubeJsItemRemovalCandidateEntries,
                session.kubeJsItemRemovalCandidateLoopEntries,
                session.kubeJsItemRemovalPredicateTestsBypassed,
                session.kubeJsItemRemovalOriginalPredicateTests,
                session.kubeJsItemRemovalRequestEntries,
                session.kubeJsItemRemovalFallbackFilters + session.kubeJsItemRemovalFullScanFallbacks,
                Map.copyOf(session.optimizationFallbacks),
                runtimeRemovalVisibilitySummary(session),
                structuralChangeSummary(session)
        ));

        StringBuilder lines = new StringBuilder("[JETOptimizer] === OPTIMIZATION REPORT ===\n");
        lines.append("Connection generations profiled in this process: ")
                .append(OPTIMIZATION_RECORDS.size()).append('\n');
        for (OptimizationRecord record : OPTIMIZATION_RECORDS) {
            lines.append("  Generation ").append(record.generation()).append(" (")
                    .append(record.joinKind()).append(", ").append(record.serverAddress()).append(")\n");
            appendTiming(lines, "    total JEI start", record.totalNanos());
            lines.append("    fast search-text path: ")
                    .append(record.fastSearchText() ? "enabled" : "disabled (JEI implementation used)")
                    .append('\n');
            appendTiming(lines, "    pure search-text pipeline", record.pipelineNanos());
            lines.append("      pipeline calls: ").append(record.pipelineCalls()).append('\n');
            appendTiming(lines, "      fast chat-format stripping", record.stripNanos());
            appendTiming(lines, "      fast whitespace splitting", record.splitNanos());
            appendTiming(lines, "      pipeline work outside fast paths", record.pipelineOutsideFastPathsNanos());
            lines.append("      strip calls/split calls: ")
                    .append(record.stripCalls()).append('/').append(record.splitCalls()).append('\n');
            if (record.kubeJsItemRemovalObserved()) {
                lines.append("    KubeJS item-removal ID index: ")
                        .append(record.kubeJsItemRemovalEnabled() ? "enabled" : "disabled")
                        .append("; source/candidates ")
                        .append(record.kubeJsItemRemovalSourceEntries()).append('/')
                        .append(record.kubeJsItemRemovalCandidateEntries())
                        .append("; loop entries ").append(record.kubeJsItemRemovalCandidateLoopEntries())
                        .append("; predicate tests bypassed/original ")
                        .append(record.kubeJsItemRemovalPredicateTestsBypassed()).append('/')
                        .append(record.kubeJsItemRemovalOriginalPredicateTests())
                        .append("; manager removal request ").append(record.kubeJsItemRemovalRequestEntries())
                        .append("; fallbacks ").append(record.kubeJsItemRemovalFallbacks()).append('\n');
                appendTiming(lines, "      KubeJS dense-ID index construction", record.kubeJsItemRemovalIndexNanos());
            } else {
                lines.append("    KubeJS item-removal ID index: unavailable (callback not observed)\n");
            }
            lines.append("    runtime removal visibility batching: ")
                    .append(record.runtimeRemovalVisibilitySummary()).append('\n');
            lines.append("    fallbacks to JEI implementation: ")
                    .append(record.fallbacks().isEmpty() ? "none" : record.fallbacks()).append('\n');
            lines.append("    structural correctness vs previous generation: ")
                    .append(record.structuralChangeSummary()).append('\n');
        }
        appendColdReconnectComparison(lines);
        JETOptimizer.LOGGER.info(lines.toString().stripTrailing());
    }

    private static long tooltipSearchNanos(Session session) {
        long total = 0L;
        for (long[] values : session.tooltipNanosByType.values()) {
            total += values[0];
        }
        return total;
    }

    private static void appendColdReconnectComparison(StringBuilder lines) {
        OptimizationRecord cold = null;
        for (OptimizationRecord record : OPTIMIZATION_RECORDS) {
            if ("first join in this process".equals(record.joinKind())
                    && !INTEGRATED_TARGET.equals(record.serverAddress())
                    && !UNKNOWN_TARGET.equals(record.serverAddress())) {
                cold = record;
                break;
            }
        }
        OptimizationRecord reconnect = null;
        if (cold != null) {
            for (OptimizationRecord record : OPTIMIZATION_RECORDS) {
                if ("in-game reconnect to a remote server".equals(record.joinKind())
                        && cold.serverAddress().equals(record.serverAddress())) {
                    reconnect = record;
                }
            }
        }

        lines.append("[JETOptimizer] === COLD vs RECONNECT ===\n");
        if (cold == null || reconnect == null) {
            lines.append("  unavailable (matching cold remote join and same-server reconnect not both profiled)\n");
            return;
        }
        lines.append(String.format(Locale.ROOT, "  %-22s %10s %10s%n", "", "COLD (s)", "RECONNECT (s)"));
        appendComparisonRow(lines, "JEI total", cold.totalNanos(), reconnect.totalNanos());
        appendComparisonRow(lines, "Search index", cold.searchNanos(), reconnect.searchNanos());
        appendComparisonRow(lines, "Tooltip extraction", cold.tooltipNanos(), reconnect.tooltipNanos());
        appendComparisonRow(lines, "Ingredient registration", cold.ingredientRegistrationNanos(), reconnect.ingredientRegistrationNanos());
        appendComparisonRow(lines, "Recipe registration", cold.recipeRegistrationNanos(), reconnect.recipeRegistrationNanos());
        appendComparisonRow(lines, "KubeJS callback", cold.kubeJsNanos(), reconnect.kubeJsNanos());
    }

    private static void appendComparisonRow(StringBuilder lines, String label, long coldNanos, long reconnectNanos) {
        lines.append(String.format(Locale.ROOT, "  %-22s %10.3f %10.3f%n", label,
                coldNanos / 1_000_000_000.0, reconnectNanos / 1_000_000_000.0));
    }

    private static String runtimeRemovalVisibilitySummary(Session session) {
        String lifecycle = "; filter base entries " + session.filterEntriesAtGuiBuild
                + "; removal requests/effective index removals " + session.runtimeIngredientRemoveRequests
                + "/" + session.runtimeIngredientRemovedEntries
                + "; final manager entries " + session.finalManagerRaw;
        if (session.runtimeRemovalVisibilityCalls == 0) {
            return session.bulkRuntimeRemovalVisibilityEnabled
                    ? "enabled; no removal notifications observed" + lifecycle
                    : "disabled; dispatch counters unavailable" + lifecycle;
        }
        return (session.bulkRuntimeRemovalVisibilityEnabled ? "enabled" : "disabled")
                + "; calls " + session.runtimeRemovalVisibilityCalls
                + "; request entries " + session.runtimeRemovalVisibilityRequested
                + "; individual notifications " + session.runtimeRemovalVisibilityIndividualNotifications
                + "; original single dispatches " + session.runtimeRemovalVisibilitySingleDispatches
                + "; batched entries/dispatches " + session.runtimeRemovalVisibilityBatchedIngredients
                + "/" + session.runtimeRemovalVisibilityBatchedDispatches
                + "; dispatches avoided " + Math.max(0, session.runtimeRemovalVisibilityIndividualNotifications
                - session.runtimeRemovalVisibilitySingleDispatches - session.runtimeRemovalVisibilityBatchedDispatches)
                + "; optimized/fallback calls " + session.runtimeRemovalVisibilityOptimizedCalls
                + "/" + session.runtimeRemovalVisibilityFallbackCalls
                + "; wrapper time " + formatSeconds(session.runtimeRemovalVisibilityNanos)
                + lifecycle;
    }

    private static String structuralChangeSummary(Session session) {
        GenerationSnapshot previous = session.previousSnapshot;
        if (previous == null) {
            return "unavailable (first connection profiled in this process)";
        }
        Map<String, Long> current = session.structuralValues();
        long comparable = 0L;
        long changed = 0L;
        for (Map.Entry<String, Long> entry : previous.values().entrySet()) {
            Long after = current.get(entry.getKey());
            if (after == null) {
                continue;
            }
            comparable++;
            if (!after.equals(entry.getValue())) {
                changed++;
            }
        }
        return changed + " of " + comparable + " comparable fields differ";
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

    private static String formatSeconds(long nanos) {
        return String.format(Locale.ROOT, "%.3f s", nanos / 1_000_000_000.0);
    }

    private static final class Session {
        private final long startedAt;
        private final PendingRecipeSync recipeSync;
        private final boolean profiling;
        private final boolean pluginProfiling;
        private int generation = 1;
        private long startedAtEpochMillis;
        private String serverAddress = UNKNOWN_TARGET;
        private GenerationSnapshot previousSnapshot;
        private final Map<String, Long> stageNanos = new HashMap<>();
        private final Map<String, Long> stageStartedAt = new HashMap<>();
        private final Map<String, Long> pluginNanos = new HashMap<>();
        private final Set<String> pluginUids = new HashSet<>();
        private final Set<String> observedHooks = new LinkedHashSet<>();
        private final Map<String, Long> pluginPhaseNanos = new HashMap<>();
        private final Map<String, Long> topLevelPluginPhaseNanos = new HashMap<>();
        private final Map<PluginCallbackKey, Long> pluginNanosByPhase = new HashMap<>();
        private final Deque<PluginPhaseFrame> pluginPhaseStack = new ArrayDeque<>();
        private final Map<String, SearchPrefixMetrics> searchPrefixMetrics = new HashMap<>();
        private final Deque<BakedIndexFrame> bakedIndexStarts = new ArrayDeque<>();
        private final Deque<Long> recipeLayoutStarts = new ArrayDeque<>();
        private final Map<String, long[]> tooltipNanosByType = new HashMap<>();
        private long searchTextPipelineNanos;
        private int searchTextPipelineCalls;
        private long searchTextPipelineStartedAt;
        private long fastStripNanos;
        private long fastStripStartedAt;
        private int fastStripCalls;
        private int fastStripApplied;
        private long fastSplitNanos;
        private long fastSplitStartedAt;
        private int fastSplitCalls;
        private int fastSplitApplied;
        private final Map<String, Integer> optimizationFallbacks = new LinkedHashMap<>();
        private final Map<String, Long> guiRuntimeGates = new LinkedHashMap<>();
        private boolean kubeJsItemRemovalObserved;
        private boolean kubeJsItemRemovalEnabled;
        private int kubeJsItemRemovalFilterCount;
        private int kubeJsItemRemovalPatternCount;
        private int kubeJsItemRemovalLeafItemValues;
        private int kubeJsItemRemovalFilterItemRegistryIds;
        private int kubeJsItemRemovalSourceEntries;
        private int kubeJsItemRemovalCandidateEntries;
        private int kubeJsItemRemovalCandidateLoopExpected;
        private int kubeJsItemRemovalCandidateLoopEntries;
        private int kubeJsItemRemovalPredicateTestsBypassed;
        private int kubeJsItemRemovalOriginalPredicateTests;
        private int kubeJsItemRemovalFullScanFallbacks;
        private int kubeJsItemRemovalFallbackFilters;
        private int kubeJsItemRemovalRequestEntries;
        private int kubeJsItemRemovalIndexBuilds;
        private long kubeJsItemRemovalIndexNanos;
        private String kubeJSCallbackPhaseReport;
        private long tooltipStartedAt;
        private String tooltipTypeUid;
        private int tooltipSearchSkipped;
        private String currentSearchPrefix;
        private boolean searchStageActive;
        private boolean registeringRecipes;
        private long recipeAddNanos;
        private int recipeAddBatches;
        private int recipeAddRecipeCount;
        private long recipeLayoutNanos;
        private int recipeLayoutCalls;
        private int recipeLayoutBuildersCreated;
        private int recipeLayoutBuildersReused;
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
        private int runtimeIngredientRemovedEntries;
        private boolean bulkRuntimeRemovalVisibilityEnabled;
        private int runtimeRemovalVisibilityCalls;
        private int runtimeRemovalVisibilityOptimizedCalls;
        private int runtimeRemovalVisibilityFallbackCalls;
        private int runtimeRemovalVisibilityRequested;
        private int runtimeRemovalVisibilityIndividualNotifications;
        private int runtimeRemovalVisibilitySingleDispatches;
        private int runtimeRemovalVisibilityBatchedIngredients;
        private int runtimeRemovalVisibilityBatchedDispatches;
        private long runtimeRemovalVisibilityNanos;

        private Session(long startedAt, PendingRecipeSync recipeSync, boolean profiling, boolean pluginProfiling) {
            this.startedAt = startedAt;
            this.recipeSync = recipeSync;
            this.profiling = profiling;
            this.pluginProfiling = pluginProfiling;
        }

        private void resetSearchTracking() {
            searchStageActive = true;
            searchPrefixMetrics.clear();
            bakedIndexStarts.clear();
            tooltipNanosByType.clear();
            tooltipStartedAt = 0L;
            tooltipTypeUid = null;
            currentSearchPrefix = null;
        }

        private SearchPrefixMetrics metricsFor(String prefixId) {
            return searchPrefixMetrics.computeIfAbsent(prefixId, ignored -> new SearchPrefixMetrics());
        }

        private String currentPluginPhase() {
            PluginPhaseFrame frame = pluginPhaseStack.peek();
            return frame == null ? null : frame.title();
        }

        /**
         * Derived from the phase stack so the recipe hooks never repeat the stack peek and string
         * comparison: recipe map insertion runs once per recipe role and dominates hot-path cost.
         */
        private void refreshRegisteringRecipesFlag() {
            registeringRecipes = PHASE_REGISTERING_RECIPES.equals(currentPluginPhase());
        }

        /**
         * Connection-independent structural values. These are the only quantities a reuse
         * decision may rely on: no timings, no runtime object references, no ingredient payloads.
         */
        private Map<String, Long> structuralValues() {
            Map<String, Long> values = new LinkedHashMap<>();
            values.put("client recipes (RecipeManager size)", (long) Optional.ofNullable(recipeSync).map(PendingRecipeSync::recipeCount).orElse(-1));
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
            values.put("runtime ingredient entries removed", (long) runtimeIngredientRemovedEntries);
            values.put("runtime removal visibility individual notifications", (long) runtimeRemovalVisibilityIndividualNotifications);
            values.put("runtime removal visibility single dispatches", (long) runtimeRemovalVisibilitySingleDispatches);
            values.put("runtime removal visibility batched dispatches", (long) runtimeRemovalVisibilityBatchedDispatches);
            values.put("runtime removal visibility batched ingredients", (long) runtimeRemovalVisibilityBatchedIngredients);
            searchPrefixMetrics.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        values.put("search getter calls " + entry.getKey(), (long) entry.getValue().getterCalls);
                        values.put("search candidate strings " + entry.getKey(), entry.getValue().returnedStringCandidates);
                    });
            values.put("baked index build calls", (long) bakeCallsTotal());
            values.put("baked index key entries", bakeKeyEntriesTotal());
            tooltipNanosByType.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> values.put("tooltip string calls " + entry.getKey(), entry.getValue()[1]));
            values.put("search-text pipeline calls", (long) searchTextPipelineCalls);
            values.put("chat-format stripping calls", (long) fastStripCalls);
            values.put("whitespace splitting calls", (long) fastSplitCalls);
            values.put("chat-format stripping applied", (long) fastStripApplied);
            values.put("whitespace splitting applied", (long) fastSplitApplied);
            values.put("optimization fallbacks", (long) optimizationFallbacks.values().stream().mapToInt(Integer::intValue).sum());
            values.put("KubeJS item-removal source entries", (long) kubeJsItemRemovalSourceEntries);
            values.put("KubeJS item-removal filter item IDs", (long) kubeJsItemRemovalFilterItemRegistryIds);
            values.put("KubeJS item-removal candidate entries", (long) kubeJsItemRemovalCandidateEntries);
            values.put("KubeJS item-removal request entries", (long) kubeJsItemRemovalRequestEntries);
            values.put("KubeJS item-removal predicate tests bypassed", (long) kubeJsItemRemovalPredicateTestsBypassed);
            values.put("observed plugin UIDs", (long) pluginUids.size());
            values.put("observed plugin UID set hash", pluginUids.isEmpty() ? -1L : pluginUids.hashCode());
            return values;
        }

        private int bakeCallsTotal() {
            return searchPrefixMetrics.values().stream().mapToInt(metrics -> metrics.bakedBuildCalls).sum();
        }

        private long bakeKeyEntriesTotal() {
            return searchPrefixMetrics.values().stream().mapToLong(metrics -> metrics.bakedKeyEntries).sum();
        }
    }

    private record GenerationSnapshot(int generation, long startedAtEpochMillis, String serverAddress,
                                      Map<String, Long> values) {
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

    /**
     * Mutable because a phase can start outside every measured stage and still open one: JEI's
     * {@code Registering Runtime} phase calls {@code JeiGuiStarter.start} through its own NeoForge GUI
     * plugin, so the GUI-runtime stage is nested inside the phase rather than after it.
     */
    private static final class PluginPhaseFrame {
        private final String title;
        private final long startedAt;
        private final boolean startedOutsideStage;
        private boolean containsStage;

        private PluginPhaseFrame(String title, long startedAt, boolean startedOutsideStage) {
            this.title = title;
            this.startedAt = startedAt;
            this.startedOutsideStage = startedOutsideStage;
        }

        private String title() {
            return title;
        }

        private long startedAt() {
            return startedAt;
        }

        private boolean isTopLevel() {
            return startedOutsideStage && !containsStage;
        }
    }

    private record BakedIndexFrame(String prefixId, long startedAt, int keyCount) {
    }

    private record StructuralDifference(String key, Long before, Long after, boolean changed) {
    }

    private record OptimizationRecord(
            int generation,
            String joinKind,
            String serverAddress,
            long totalNanos,
            long searchNanos,
            long tooltipNanos,
            long ingredientRegistrationNanos,
            long recipeRegistrationNanos,
            long kubeJsNanos,
            boolean fastSearchText,
            long pipelineNanos,
            int pipelineCalls,
            long stripNanos,
            int stripCalls,
            long splitNanos,
            int splitCalls,
            long pipelineOutsideFastPathsNanos,
            boolean kubeJsItemRemovalObserved,
            boolean kubeJsItemRemovalEnabled,
            long kubeJsItemRemovalIndexNanos,
            int kubeJsItemRemovalSourceEntries,
            int kubeJsItemRemovalCandidateEntries,
            int kubeJsItemRemovalCandidateLoopEntries,
            int kubeJsItemRemovalPredicateTestsBypassed,
            int kubeJsItemRemovalOriginalPredicateTests,
            int kubeJsItemRemovalRequestEntries,
            int kubeJsItemRemovalFallbacks,
            Map<String, Integer> fallbacks,
            String runtimeRemovalVisibilitySummary,
            String structuralChangeSummary
    ) {
    }

    private static final class SearchPrefixMetrics {
        private long startedAt;
        private boolean active;
        private long stringSourceNanos;
        private long returnedStringCandidates;
        private int getterCalls;
        private long bakedBuildNanos;
        private int bakedBuildCalls;
        private long bakedKeyEntries;

        private void start() {
            this.startedAt = System.nanoTime();
            this.active = true;
            this.getterCalls++;
        }
    }

    private record PendingRecipeSync(long packetStartedAt, long eventAt, int recipeCount) {
    }
}
