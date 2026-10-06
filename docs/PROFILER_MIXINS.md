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

Each phase frame also records whether a measured JEI stage was open when the phase began, and whether a stage was opened while the phase was running. A phase that satisfies neither condition is a *top-level* phase: it is printed under `Top-level plugin phases (outside every measured stage)` and subtracted from `Other (unattributed)`. A phase that opens a stage inside itself is not subtracted, because that stage is already counted. This distinction is not theoretical: JEI's `Registering Runtime` phase calls `JeiGuiStarter.start` from its own `NeoForgeGuiPlugin.registerRuntime`, so the 11.7 s `JEI GUI runtime construction` stage is nested inside that phase rather than following it, and the phase and its stage agree to within 0.007 s across three generations. Subtracting both would double count roughly a third of the startup.

Two new detail sections close the remaining per-source gaps, and both take their phase names from JEI source (`PluginLoader.registerIngredients`, `JeiStarter.start`) rather than from guesses. Real output from the three-generation run:

```text
Ingredient registration detail (nested plugin phases):
  Registering ingredients:                3.845 s
    Slowest callbacks (top 5):
      jei:minecraft:                      3.836 s
      bigreactors:jeiplugin:              0.002 s
  Registering extra ingredients:          0.006 s
  Registering search ingredient aliases:    0.025 s
  Ingredient registration outside plugin phases:    0.006 s
```

```text
Sending Runtime detail (outside every measured stage):
  onRuntimeAvailable across plugins:      2.939 s
  Plugin callback time not attributed:    0.002 s
  Slowest onRuntimeAvailable callbacks (top 10):
    kubejs:jei:                           2.755 s
    ae2:core:                             0.083 s
    createthrusters:jei:                  0.044 s
```

`Ingredient registration outside plugin phases` and `Plugin callback time not attributed` are the residuals of their parents, so the phase lines always sum back to the stage or phase total that contains them.

`Sending Runtime` runs after every `PluginLoader` stage closes, so before this reporting it was indistinguishable from the unattributed remainder: 2.939 s, 3.002 s and 10.425 s against residuals of 2.720 s, 2.850 s and 10.396 s. With the phases printed and the correctly nested ones held inside their stage, the remainder closes to a raw -0.025 s to -0.041 s, so the profiled regions account for about 100.1% of JEI's own `LoggedTimer` total instead of leaving 2.7-10.4 s unaccounted.

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

## Optimization mixins

These are not profiling mixins: they change what JEI does, so each one is gated, fail-open, and has
its own counters.

`SearchTextOptimization` replaces the two regular expressions in JEI's search-word construction:
`ChatFormatting.stripFormatting` behind `StringUtil.removeChatFormatting`, and the `\s+` split in
`ListElementInfo.addSplitStrings`. Both are pure and run once per tooltip line of every ingredient.
Equivalence with the originals, including the `trim()` versus `\s` difference and the full
`(?i)[K-O]` range, is verified differentially against the two regexes as oracles.

`StringUtil.removeChatFormatting` is only reached from search-word construction - `getStrings`,
creative tab names, and `DisplayNameUtil` - so nothing that is rendered is affected.

Both injections fail open three ways: they do nothing while `fastSearchText` is disabled, they leave
`null` to JEI's own method, and if the replacement throws, the return value is left unset so JEI's
original body runs. Each fallback is counted and reported by name.

The gate is `optimizations.fastSearchText`, default `true`, cached once per process so the hot path
never touches the config spec. A config read that throws before the config is registered is not
cached, so it disables the optimization for that call rather than for the whole session.

### KubeJS `onRuntimeAvailable` phase probe and category-map skip

`KubeJSJEIPluginMixin` targets KubeJS `dev.latvian.mods.kubejs.integration.jei.KubeJSJEIPlugin`
by string (`@Pseudo`). KubeJS is not on the compile classpath, so JEI-typed redirect handlers are
used where the callee is a JEI type, and string `@At` targets with `@Inject` cover the KubeJS-side
boundaries.

A **phase probe** marks eleven bytecode boundaries inside `onRuntimeAvailable` (category map built,
remove-categories events done, ingredient fetch start, both type-table init passes, remote
item-removal start, remote add-entries start, callback end) plus a `HEAD`/`RETURN` bracket, and
reports the gaps as `KubeJS onRuntimeAvailable phase timing:`. To make the expensive first segment
attributable, redirects time `IJeiRuntime.getRecipeManager` (ordinals 0 and 1),
`IJeiRuntime.getIngredientManager`, `IRecipeManager.createRecipeCategoryLookup`,
`IRecipeCategoriesLookup.get` and the `Stream.collect`, while `Lazy.get` is bounded with
`@Inject` before/after pairs because KubeJS's `Lazy` type is not available to the redirect handler.

A **category-map skip** removes the 2.6 s cost that the probe isolated. KubeJS builds
`categories = new HashMap<>(createRecipeCategoryLookup().get().collect(toMap(...)))`, and JEI's
first `get()` computes `recipeCategoriesVisibleCache` by running `isCategoryHidden` over all 524
categories (double `hasRecipeCatalysts` plus `getRecipesStream().findAny()` each). The map is only
consumed by two event posts guarded by `RecipeViewerEvents.REMOVE_CATEGORIES/.REMOVE_RECIPES
.hasListeners()` and by a `remote != null` loop. The `get()` redirect therefore checks, purely
reflectively and fail-open, whether either removal event has a listener (via
`EventHandler.hasListeners()` on those public static fields) and whether the plugin's private
`remote` field is null; only when all three are absent does it return `Stream.empty()`, so the
`toMap` collect yields an empty map and `onRuntimeAvailable` proceeds identically. Any reflection
failure returns `false` and the full map is built as before. Measurement on a cold remote join:
callback total 2.638 s -> 0.001 s, `Total JEI start` 26.342 s -> 23.671 s, JEI GUI runtime
construction unchanged at 10.391 vs 10.468 s (no cost migration).

## GUI runtime gate waterfall

`JeiGuiStarterMixin` records ten ordered invocation boundaries inside `JeiGuiStarter.start`, plus a
method-entry gate, in addition to timing the whole stage. Together these produce eleven measured
intervals: setup before ingredient-list construction; ingredient list; ingredient filter; bookmark
factory/codec; lookup history; ingredient overlay; bookmark list; bookmark config load; bookmark
overlay; recipes GUI; and input handlers through method return. The setup block includes helper/config
retrieval and `JeiGuiColors.onResourceManagerReload`, which parses JEI's GUI color resource stack.
JEI's own `LoggedTimer` only wraps the ingredient list and the ingredient filter, which left roughly
3.3 to 3.6 s of that stage unattributed on every connection.

A gate marks where a named block starts, so its duration is the interval up to the next gate, and the
final block is measured at the `RETURN` hook. Blocks are recorded in an ordered `LinkedHashMap` rather
than as begin/end pairs: `defaultRequire` is 0, so a boundary whose target does not match this JEI
version drops out of the waterfall instead of unbalancing it. All ten invocation targets were verified
to resolve exactly once, in ascending bytecode order, against the compiled `JeiGuiStarter.start` of
JEI 19.57.0.449.

## Optimization report

Every profiled connection appends a row to a `[JETOptimizer] === OPTIMIZATION REPORT ===` block that
lives for the life of the process, so a cold join and a later reconnect are readable side by side.

The report shows each fast path's measured time and call count, plus `pipeline work outside fast paths`
(the pipeline total minus those measured sections). That residual is **not** a savings estimate; the
report does not infer time saved versus the original regexes. Estimate savings separately with a
controlled baseline or a differential microbenchmark. Each row also carries the per-generation
totals, fallback count by name, and structural comparison summary against the previous generation.
