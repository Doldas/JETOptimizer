# Profiler mixin compatibility notes

These hooks are diagnostic only. They are configured as optional, version-specific hooks with a default injection requirement of zero. A missing method should leave JEI running normally; the profile reports which hooks were observed so incomplete instrumentation is visible. There are no runtime/cache modifications.

The supported source baseline is JEI 19.57.0.449. All JEI targets below were checked against its Maven source artifacts.

| Mixin | Target | Hook and purpose | Failure behavior |
|---|---|---|---|
| `ClientPacketListenerMixin` | Minecraft `ClientPacketListener.handleUpdateRecipes` | At method entry, mark the start of the client recipe packet handler so it can be compared with `RecipesUpdatedEvent` and JEI startup. | Vanilla method mapping/injection mismatch is optional (`defaultRequire: 0`); packet handling continues without this timing. |
| `JeiStarterMixin` | JEI `mezz.jei.library.startup.JeiStarter.start` | Starts immediately after `LoggedTimer.start("Starting JEI")` and finishes immediately after its matching `LoggedTimer.stop()` (the second `stop()` call in this method). This aligns the summary with JEI's own total and excludes post-timer recipe verification. | The timer call/ordinal is source-version-specific; if it changes, the optional hook is skipped and no JETOptimizer summary is produced. JEI itself is unchanged. |
| `PluginLoaderMixin` | JEI `mezz.jei.library.load.PluginLoader` | Times exact source methods for subtype, ingredient, mod-alias, search-factory, recipe/category, transfer, and GUI-handler registration. | Missing method hooks are skipped. Existing JEI registration proceeds untouched. |
| `JeiGuiStarterMixin` | JEI `mezz.jei.gui.startup.JeiGuiStarter.start` | Times JEI's GUI-runtime construction boundary. | Missing hook omits this row; it does not bypass GUI construction. |
| `IngredientListElementFactoryMixin` | JEI `mezz.jei.gui.ingredients.IngredientListElementFactory.createBaseList` | Times the ingredient-list factory; at return, reads raw/typed manager collection sizes and the returned list size to compare counts at the same lifecycle point. | Missing hook omits the stage and pre-callback count snapshot; no list behavior is modified. |
| `IngredientFilterMixin` | JEI `mezz.jei.gui.ingredients.IngredientFilter.<init>` and `createElementSearch` | Separately bounds the full filter constructor (sorting, search setup and initial visibility work) and JEI's actual element-search/index factory. The index timer is nested inside the filter timer. | Missing hook omits the corresponding breakdown; JEI filter and search construction remain intact. |
| `IngredientSorterMixin` | JEI `mezz.jei.gui.ingredients.IngredientSorter.sortIngredients` | Times JEI's actual sort operation, separate from `createElementSearch`. | Missing hook leaves the filter-constructor timer and normal sorting intact. |
| `PrefixInfoMixin` | JEI `mezz.jei.common.search.PrefixInfo.createStorageBuilder` / `getStrings` | Tags per-prefix string-source getter elapsed time and returned collection size during the search-index phase; sets the active source ID for the following builder call. This samples once per getter, not per string. | Missing hook leaves prefix behavior intact; prefix attribution is absent and time remains in search-index residual. |
| `ListElementInfoMixin` | JEI `mezz.jei.gui.ingredients.ListElementInfo.getTooltipStrings` | Times the tooltip search-string extraction per call and aggregates it by the ingredient type's UID, because that call is the largest single identified startup region. The UID is resolved once at entry and reused by the return hook. It is read defensively and falls back to `unknown`. | Missing hook omits the by-type tooltip rows; tooltip extraction is unchanged and its time stays attributed to the `tooltips` source row. |
| `BakedSubstringIndexBuilderMixin` | JEI shaded `BakedSubstringIndex.Builder.build` | Times gram-index baking and reads the existing key-list size before build. Only JEI's default baked builder path is counted. | Missing hook omits baked index details; search still builds. Custom plugin factories are not assumed to use this builder. |
| `IngredientManagerMixin` | JEI `mezz.jei.library.ingredients.IngredientManager.addIngredientsAtRuntime` / `removeIngredientsAtRuntime` | Counts requested entries and calls at method entry during JEI startup; these are request counts, not guaranteed successful unique mutations. | Missing hook leaves manager updates intact; before/after snapshots still show net count change. |
| `PluginCallerMixin` | JEI `mezz.jei.library.load.PluginCaller.callOnPlugins` | Times every named callback phase and redirects the existing callback only when opt-in plugin profiling is active; aggregates callback durations by plugin UID and phase. It does not log recipes or ingredients. | When callback profiling is disabled, immediately delegates to the original `Consumer.accept`. Missing hooks leave callbacks intact; partial phase coverage is visible in profile output. |
| `RecipeManagerInternalMixin` | JEI `mezz.jei.library.recipes.RecipeManagerInternal.<init>`, `addPlugins`, and `compact` | Reads the constructor's real category collection size; separately times registry construction, advanced plugin wiring, and map compaction. | Missing hooks report unavailable/missing stage timings; JEI manager construction and finalization proceed unchanged. |
| `RecipeRegistrationMixin` | JEI `mezz.jei.library.load.registration.RecipeRegistration.addRecipes` invocation of `RecipeManagerInternal.addRecipes` | Narrow `@Redirect` delegates to the exact original manager call in `try/finally`, aggregating addRecipes wall time, batch count, and submitted recipe count only inside JEI's `Registering recipes` phase. | The redirected body immediately invokes the original method; no arguments/results are changed. If the target changes, this detail is skipped and the broader callback timer remains. |
| `RecipeMapMixin` | JEI `mezz.jei.library.recipes.collect.RecipeMap.addRecipe` | Times UID/index writes separately for each actual `RecipeIngredientRole`; one sample per recipe-role map call, only during `Registering recipes`. | Missing hook leaves recipe indexing intact; time remains in the addRecipes residual. |
| `IngredientSupplierHelperMixin` | JEI `mezz.jei.library.util.IngredientSupplierHelper.getIngredientSupplier` | Times category `setRecipe`/slot supplier building, only in the `Registering recipes` callback phase. | Missing hook leaves recipe extraction intact; time remains in `addRecipes` residual. |

When inactive, the callback redirect only checks whether a profiler session exists and immediately delegates. Active timing uses monotonic clocks and in-memory aggregates; the extra per-recipe-role and per-layout samples are limited to recipe registration, while search source metrics sample per-prefix getter calls rather than each string. Plugin UID totals overlap callback-phase and containing JEI stage durations. Recipe add/map/layout stages are nested inside `Registering recipes`; prefix-string and baked-index stages are nested inside `createElementSearch`. Use the parent/child breakdown in `docs/JEI_PERFORMANCE_ANALYSIS.md` and never add overlapping totals.

## Plugin phase attribution

`Sending Runtime`, `Configuring JEI`, `Registering Runtime` and the three ingredient-registration phases were never unmeasured. `PluginCaller.callOnPlugins` routes every JEI lifecycle callback through the same method, so `PluginCallerMixin` already records each phase name in `pluginPhaseNanos` together with per-plugin totals in `pluginNanosByPhase`. The gap was in reporting: the profile only printed a fixed list of five recipe-manager phases, so everything else was only visible inside the `Other (unattributed)` remainder.

Each phase frame now also records whether a measured JEI stage was open when the phase began. A phase that ran with no stage open is a *top-level* phase, is printed under `Top-level plugin phases (outside every measured stage)`, and is subtracted from `Other (unattributed)`. That matters because `Sending Runtime` runs after the last `PluginLoader` stage closes: its 2.75 s to 10.43 s measured runtime was previously indistinguishable from unattributed remainder, and now it is a named line. Phases nested inside a stage stay attached to their stage and are not subtracted, so the remainder no longer double-counts them.

Two new detail sections close the remaining per-source gaps, and both read the phase names from JEI source (`PluginLoader.registerIngredients`) rather than from guesses. Times below are shown as placeholders because the recorded runs predate these rows:

```text
Ingredient registration detail (nested plugin phases):
  Registering ingredients:                 <time>
    Slowest callbacks (top 5):
      <plugin uid>
  Registering extra ingredients:           <time>
  Registering search ingredient aliases:   <time>
  Ingredient registration outside plugin phases: <time>
```

```text
Sending Runtime detail (outside every measured stage):
  onRuntimeAvailable across plugins:      <time>
  Plugin callback time not attributed:    <time>
  Slowest onRuntimeAvailable callbacks (top 10):
    <plugin uid>
```

`Ingredient registration outside plugin phases` and `Plugin callback time not attributed` are the residuals of their parents, so the phase lines always sum back to the stage or phase total that contains them.

## Profiling overhead

The recording paths are the hot code in this mod, not the reporting. In the recorded ATM10A runs, `RecipeMap.addRecipe` is sampled about 211,000 times per role, so roughly 845,000 samples in total; `PrefixInfo.getStrings` is sampled once per ingredient per active prefix, which is about 273,000 pairs; and `ListElementInfo.getTooltipStrings` is sampled once per ingredient, about 68,000 times. Those paths therefore use primitive arrays and counters, direct field access and a `ThreadLocal` lookup, and they deliberately do **not** use stream pipelines or `Optional`, because both allocate on every sample. Streams and `Optional` are confined to report generation, which runs once per session.

Two structural changes removed work from the hot paths rather than adding any:

- Profiling flags are read from the config layer once, at session start, and cached in the session. Previously `PROFILING.get()` ran inside `activeSearchSession()`, which is called twice per search sample and once per tooltip sample, plus once per recipe-map insert.
- The active search stage is a boolean field instead of a `containsKey` lookup on the stage-name map, and whether the current plugin phase is `Registering recipes` is a boolean derived once per phase push/pop instead of a stack peek plus string comparison on every recipe-role insert. Search start timestamps moved into the per-prefix metrics object, which removes a second `HashMap` and its boxing from every search sample.

`ListElementInfoMixin` resolves the ingredient-type UID once per tooltip and passes it to the entry hook, so `finishTooltipStringSource()` takes no argument. Previously the return hook walked `getTypedIngredient().getType().getUid()` a second time for the same result.

## Connection generation and structural comparison

These need no new mixins. `JETOptimizer` already listens to `ClientPlayerNetworkEvent.LoggingIn` and `ClientPlayerNetworkEvent.LoggingOut`, so the profiler counts connections itself: `onLoggingIn` increments an `AtomicInteger` and the value is copied into the session when `JeiStarter.start` is observed. The count survives `LoggingOut` on purpose, which is what makes a second join inside the same game process distinguishable from a cold launch; it dies with the process, so it never leaks between launches.

Each profile therefore starts with:

```text
Connection generation: 2 (in-game reconnect to a remote server)
Server address: 10.0.0.5:25565 (unchanged)
Previous connection in this process: generation 1, 47 s earlier
```

The parenthetical is the *join kind*, and it is derived from the generation and the observed target rather than from the generation alone, because a generation count only proves the process logged in again. The four values are:

| Parenthetical | Meaning |
|---|---|
| `first join in this process` | Generation 1, so a cold launch. |
| `in-game reconnect to a remote server` | Generation above 1 with a readable remote address. |
| `in-game join to a local single player world` | Generation above 1, and `Minecraft.hasSingleplayerServer()` was true. |
| `in-game join, target could not be identified` | Generation above 1 with no address and no integrated server. |

The local-world case matters because `ServerData.ip` is null for an integrated server, so it would otherwise print as unknown and look like an unreachable remote server. `currentServerAddress()` therefore reports `integrated server (local world)` and `unknown (no server address available)` as distinct targets, and both are ordinary strings, so a target change is still detected as `CHANGED`. This also means a local-world join is not a substitute for a remote reconnect when reasoning about server payload: JEI runs the same full startup for both, but the recipe and ingredient content comes from somewhere else entirely.

When a previous generation was profiled in the same process, the profile also prints `Structural comparison vs generation N: X of Y comparable fields differ`, followed by one `unchanged`/`CHANGED` line per field. The compared set is deliberately limited to connection-independent quantities: client recipe count, recipe-category count, `addRecipes` batches and recipes, `setRecipe` calls, per-role `RecipeMap.addRecipe` call counts, ingredient-manager counts before and after `onRuntimeAvailable`, runtime add/remove request counts, per-prefix getter calls and candidate-string counts, baked index build calls and key entries, and the observed plugin UID count plus an order-independent set hash. No timings, no `IJeiRuntime`, no `ClientLevel`, no `ItemStack` and no plugin objects are retained, so the snapshot cannot keep a connection alive or change what JEI does.

The comparison is diagnostic and fails open. Fields that are absent on either side are skipped rather than reported as differences, an unreachable server address prints `unknown (no server address available)` instead of throwing, and the snapshot is only stored while `profiling` is enabled, so a run with profiling off simply prints `unavailable (first connection profiled in this process)`.

`ListElementInfoMixin` adds the by-type tooltip rows to the search-index detail and the tooltip call counts to the same structural comparison. It keeps one `long[2]` per distinct ingredient-type UID, which is a handful of entries, and adds two `nanoTime()` calls per ingredient inside a stage that already costs about 107 microseconds per ingredient, so it is not a meaningful share of the region it measures.
