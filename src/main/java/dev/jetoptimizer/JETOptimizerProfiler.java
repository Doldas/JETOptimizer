package dev.jetoptimizer;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RecipesUpdatedEvent;

import java.util.ArrayDeque;
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
    private static final String STAGE_INGREDIENT_REGISTRATION = "Ingredient registration";

    private static final String PHASE_REGISTERING_RECIPES = "Registering recipes";
    private static final String PHASE_SENDING_RUNTIME = "Sending Runtime";

    /** Plugin phases nested inside {@link #STAGE_INGREDIENT_REGISTRATION}. */
    private static final List<String> INGREDIENT_REGISTRATION_PHASES = List.of(
        "Registering ingredients",
        "Registering extra ingredients",
        "Registering search ingredient aliases"
    );

    /** Plugin phases nested inside the recipe manager stage, in source order. */
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
        if (frame.topLevel()) {
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
        appendSearchIndexBreakdown(lines, session);
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
        private long tooltipStartedAt;
        private String tooltipTypeUid;
        private String currentSearchPrefix;
        private boolean searchStageActive;
        private boolean registeringRecipes;
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

    private record PluginPhaseFrame(String title, long startedAt, boolean topLevel) {
    }

    private record BakedIndexFrame(String prefixId, long startedAt, int keyCount) {
    }

    private record StructuralDifference(String key, Long before, Long after, boolean changed) {
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
