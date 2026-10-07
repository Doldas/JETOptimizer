# JEI startup performance analysis

**Source baseline:** JEI 19.57.0.449, Minecraft 1.21.1, NeoForge 21.1.x.
**Current goal:** make measured JEI data-processing work cheaper with generation-local identity and bulk operations, while preserving the public JEI lifecycle and falling back whenever a fast path cannot prove its preconditions. The measured KubeJS category-map removal and the new runtime-removal notification prototype are described below.

**Read this first:** the wall-clock numbers below are single launches on a machine whose same-build spread is 1.1-1.7 s. Structural counters are reproducible to the unit; timings are not. Jump to [The 30-second login delay is JEI](#the-30-second-login-delay-is-jei) for the headline result, then [Eight Prism/ATM10A launches](#eight-prismatm10a-launches-on-the-same-saved-test-server) for the launch table and what is still unmeasured.

**Candidate regions were then checked against the JEI sources rather than only against timings.** See `docs/JEI_SOURCE_ANALYSIS.md` for why the three dominant regions offer no safe redundancy, and `docs/PROFILER_MIXINS.md` for the connection-generation and by-type tooltip instrumentation that now exists to answer the remaining questions.

## KubeJS `onRuntimeAvailable` root cause and fix (category-map skip)

**Status: measured, isolated, and optimized in this iteration.** KubeJS's JEI callback was a fixed
~2.6-2.9 s on every join inside `Sending Runtime` (reported as `kubejs:jei`). Phase instrumentation
merged into `KubeJSJEIPlugin.onRuntimeAvailable` (see `docs/PROFILER_MIXINS.md`) attributed the
whole cost to a single line of KubeJS:

```java
var categories = new HashMap<>(runtime.getRecipeManager().createRecipeCategoryLookup()
    .get()
    .collect(Collectors.toMap(cat -> cat.getRecipeType().getUid(), Function.identity())));
```

Bytecode-level timing after the callback begin (cold remote join):

| Sub-region | Cost |
|---|---:|
| Total callback | 2.638 s |
| `IRecipeCategoriesLookup.get()` | **2637.087 ms** |
| `createRecipeCategoryLookup()` | 0.005 ms |
| `Stream.collect(Collectors.toMap)` | 0.094 ms |
| first-segment remainder (manager getters, `HashMap` copy, key lambda) | ~0.6 ms |

`get()` delegates to `RecipeManagerInternal.getRecipeCategoriesForTypes(List.of(), EMPTY_FOCUS,
false)`. Its `recipeCategoriesVisibleCache` is null on the first join, so JEI computes it over all
524 categories, and each `isCategoryHidden` test runs two `hasRecipeCatalysts` scans plus
`getRecipesStream().findAny()` (JEI 19.57.0.449 source). That first-call cache computation is the
2.6 s.

The resulting map is consumed only by two event posts that are guarded by
`RecipeViewerEvents.REMOVE_CATEGORIES`/`.REMOVE_RECIPES .hasListeners()`, and by the remote-removal
loop that runs only when `KubeJSJEIPlugin.remote != null`. In this pack both listeners are absent
and `remote` is null, so the 2.6 s are **dead work on every join**.

The fix is a gated, fail-open `@Redirect` on `IRecipeCategoriesLookup.get()`: when
`enabled`, `experimentalOptimizations`, and `skipUnusedKubeJsCategoryMap` are true, neither removal
event has a listener and `remote == null` (both read reflectively), it returns `Stream.empty()`, so
the `toMap` collect produces an empty map and `onRuntimeAvailable` continues unchanged. Any disabled
flag, reflection failure, or present listener builds the full map exactly as before.

Measured effect, cold remote join (generation 1, same server, same harness):

| Quantity | Before | After |
|---|---:|---:|
| KubeJS callback total | 2.638 s | 0.001 s |
| `Total JEI start` | 26.342 s | **23.671 s** |
| JEI GUI runtime construction | 10.468 s | 10.391 s |
| Ingredient filter construction | 7.354 s | 7.325 s |

The 2.6 s did **not** migrate into the GUI stage: GUI runtime construction and the ingredient
filter are unchanged, so no other JEI startup path computes the visible-category cache in this
pack. The saving is net, and behaviour is preserved whenever the map is actually consumed.

## Ingredient lifecycle and bulk runtime-removal visibility prototype

JEI 19.57.0.449 constructs the ingredient filter before runtime callbacks:

```text
JeiStarter.start
  -> PluginCaller "Registering Runtime"
     -> NeoForgeGuiPlugin.registerRuntime
        -> JeiGuiStarter.start
           -> IngredientListElementFactory.createBaseList (68,186 entries in the measured pack)
           -> IngredientFilter constructor
              -> createElementSearch (tooltip/search index is built here)
  -> construct IJeiRuntime
  -> PluginCaller "Sending Runtime"
     -> KubeJSJEIPlugin.onRuntimeAvailable
        -> removeIngredientsAtRuntime calls
```

`JeiGuiStarter.start` creates the filter and installs it in `IRuntimeRegistration` before the
`registerRuntime` phase returns. JEI then exposes that runtime to `onRuntimeAvailable` plugins.
Moving `IngredientFilter` construction after those callbacks would change this API ordering: runtime
plugins can query the already-installed filter. This prototype therefore preserves JEI's order.

The measured structure is: 68,186 base-list entries, 17,729 requested removal entries across 34
manager calls, one requested addition, and 51,744 manager entries after callbacks. The net manager
decrease is 16,442; requested removals are not the same as successful unique removals because requests
can repeat or refer to entries no longer present. `ListElementInfo.getTooltipStrings` and every enabled
`PrefixInfo` source run while `IngredientFilter` is constructed, before the removal callbacks, so
every entry in the base list has already paid search/tooltip indexing cost. The report now counts
successful `RegisteredIngredientIndex` removals separately from removal requests. In this ATM10A run,
the pre-filter manager and filter base-list cardinalities both equal 68,186, so successful removals
are comparable to the indexed population. No second UID/identity set is retained solely for profiling;
if another pack's base-list cardinality differs, the report does not claim an exact intersection.

The safe reachable prototype targets repeated JEI visibility updates rather than reordering that
lifecycle. In JEI source, `IngredientManager.removeIngredientsAtRuntime` removes registered UIDs, then
`IngredientBlacklistInternal.onIngredientsRemoved` sends one visibility notification per affected
ingredient. `IngredientFilter` processes those single-item callbacks individually and invalidates its
source-list cache/notifies listeners for each changed item; `RecipeManagerInternal` also invalidates
its visible-category cache per callback.

With `enabled`, `experimentalOptimizations`, and `bulkRuntimeRemovalVisibility` enabled, the mixin
buffers those per-removal-call notifications by their exact `UidContext` set, then uses JEI's existing
collection callback once per group. The fast path requires that `IngredientVisibility` contains
exactly JEI's `IngredientFilter` and `RecipeManagerInternal` listeners. An extra listener, failed
reflection, or unavailable target delegates every notification to the original JEI method. No
ingredient membership, UIDs, recipes, or visibility contexts are changed. The buffer retains only
references for one synchronous removal call and is released in `finally`.

The optimization report includes requested entries, successful registry-index removals, original
single-item visibility dispatches, batched entries/dispatches, optimized/fallback calls, and wrapper
time. The first cold-join + same-process reconnect validation is below.

## ATM10A cold-join and reconnect validation (2026-10-07)

The captured log is preserved at `/tmp/opencode/atm10a-optimizer-test-20261007.log`. Both generations
used `play.gamitronservers.com`; the Mixin hooks applied and the optimized counts were emitted.

| Measure | Cold, generation 1 | Same-process reconnect, generation 2 |
|---|---:|---:|
| Total JEI start | 27.298 s | 23.178 s |
| Ingredient search index | 7.905 s | 6.493 s |
| Tooltip extraction | 7.020 s | 5.535 s |
| Ingredient registration | 3.696 s | 3.393 s |
| Recipe registration phase | 11.102 s | 9.514 s |
| KubeJS callback | 0.002 s | <0.001 s |
| IngredientFilter base-list entries | 68,186 | 70,223 |
| Runtime removal requests | 17,729 | 19,764 |
| Successful registered-index removals | 17,600 | 19,635 |
| Final manager entries | 51,744 | 51,746 |

These are not a controlled A/B comparison: the reconnect had 2,037 more base-list ingredients,
264 more recipes and 2,035 more removal requests. Do not attribute the 4.120 s total-time difference
to the new batching optimization.

The timestamps locate the removal/index ordering precisely. Six calls totaling 1,271 requested entries
occurred before `JeiGuiStarter.start` began building the ingredient list at 21:15:07.788. The other 28
calls totaling 16,458 item entries occurred after JEI logged `Added 68,186 ingredients` at 21:15:16.232,
after the filter/search index was complete. Thus **16,458 removal requests arrived after indexing**;
the final manager has 16,442 fewer entries than the filter base list, with one runtime addition request
in between. Successful index removals total 17,600 across the whole JEI startup, including those before
filter construction. The run does not directly split successful map removals at the index boundary;
the post-index effective removal count is 16,442–16,443 depending on whether the one requested addition
was accepted. In either case, approximately 16.4k indexed ItemStacks were then removed.

For scale, JEI performed 67,412 item-stack tooltip extractions taking 7.012 s on the cold generation.
Applying that average cost to ~16.4k post-index removals estimates roughly **1.71 s of tooltip work**
on entries that are later removed. This is an average-based estimate, not per-ingredient timing. Those
entries also traversed the enabled `PrefixInfo` sources and were inserted into the baked search storage;
the log reports 524,035 tooltip candidate strings and 604,695 baked keys for the whole cold index.

The batching fast path applied to 28 of 34 removal calls in each generation. Cold: 16,458 entries were
sent through 28 collection notifications; 1,248 notifications across six calls used the original
single-item path. That is **16,430 fewer listener dispatches**. Reconnect: 18,493 entries in 28
collection notifications, the same 1,248 singles, and 18,465 dispatches avoided. The six fallbacks
line up with the six calls before `JeiGuiStarter.start`, when the filter listener was not yet installed;
the listener-precondition guard correctly kept those calls on JEI's original path. Wrapper time was
45 ms cold and 35 ms reconnect; this includes the wrapped removal/notification work and is not itself
a savings measurement.

KubeJS's unused category-map skip was confirmed in both generations and reduced its callback to about
2 ms / below 1 ms. The KubeJS remote item-removal index had zero filter trees and zero candidates
because `remote` was null. No JETOptimizer Mixin failures were found. The log does contain unrelated
CompactMachines recipe-plugin exceptions caught by JEI and a Polymorph/JEI Mixin incompatibility that
disabled Polymorph's JEI integration; neither prevented JEI startup or came from JETOptimizer.

## ATM10 Aeronautics baseline supplied for this iteration

| Measurement | Time/count |
|---|---:|
| Total JEI start | 27.447 s |
| Client recipe-handler entry → JEI start | 0.523 s |
| `RecipesUpdatedEvent` → JEI start | 0.093 s |
| Client recipes | 75,632 |
| JEI `IngredientFilter` initial element count | 68,186 |
| JETOptimizer ingredient count | 51,744 |
| Recipe categories | 524 |
| JEI plugin UIDs observed | 163 |

Broad startup timings:

| Stage | Time | Relationship |
|---|---:|---|
| Recipe/category registration | 11.031 s | Top-level JEI stage. |
| JEI GUI runtime construction | 10.912 s | Top-level region; corresponds closely to `jei:neoforge_gui` callback time. |
| Ingredient filter construction | 7.785 s | Nested inside GUI runtime. |
| Ingredient search-index construction | 7.602 s | Nested inside ingredient-filter construction. |
| Ingredient registration | 2.863 s | Top-level JEI stage. |
| Ingredient list construction | 0.307 s | Nested inside GUI runtime. |
| Other/unattributed | 2.555 s | Residual after the disjoint top-level stages. |

Plugin callback totals:

| UID | Time |
|---|---:|
| `jei:neoforge_gui` | 10.914 s |
| `jei:minecraft` | 7.044 s |
| `kubejs:jei` | 2.402 s |
| `farmersdelight:jei_plugin` | 1.033 s |
| `create:jei_plugin` | 0.847 s |
| `createthrusters:jei` | ~0.040 s |
| `jeistuff:jeistuff` | ~0.025 s |

**Do not add these numbers together.** Plugin UID totals span callbacks nested in JEI stages. `jei:neoforge_gui` substantially overlaps `JEI GUI runtime construction`; filter, search-index, and ingredient-list times are children of that GUI region. Recipe registration callback timing is also nested in `Recipe/category registration`.

The disjoint top-level numbers supplied for this run account for 27.361 s (11.031 recipe/category + 10.912 GUI runtime + 2.863 ingredient registration + 2.555 unattributed), approximately 99.7% of the 27.447-second total. The remaining 0.086 s is in smaller timed top-level stages omitted from that summary. This is accounting coverage, not causal explanation of the 2.555-second residual; the detailed re-profile is still needed to identify what consumes each parent interval.

## The 30-second login delay is JEI

ModernFix independently times the whole player-facing path in the same logs:

```text
Time from main menu to in-game was <X> seconds
```

That window contains the `Starting JEI took ...` interval. Across all eight launches, JEI accounts for **76-78%** of it, and the correlation between the two numbers is 0.96:

| Run | `profiling` | JEI start | main menu → in-game | JEI share | non-JEI part |
|---|---|---:|---:|---:|---:|
| A | on | 27.447 s | 36.873 s | 74.4% | 9.426 s |
| B | on | 31.413 s | 41.185 s | 76.3% | 9.772 s |
| C | on | 30.347 s | 39.850 s | 76.2% | 9.503 s |
| D1 | off | 30.500 s | 39.690 s | 76.8% | 9.190 s |
| D2 | off | 29.500 s | 38.554 s | 76.5% | 9.054 s |
| D3 | off | 29.110 s | 37.740 s | 77.1% | 8.630 s |
| E1 | on | 31.244 s | 39.965 s | 78.2% | 8.721 s |
| D4 | off | 30.800 s | 40.069 s | 76.9% | 9.269 s |

Two things follow, and they are the reason this mod exists at all:

* The non-JEI remainder is **tight: 8.63-9.77 s, a range of 1.14 s**, while JEI's own range is 3.966 s. The correlation between JEI start and the non-JEI remainder is 0.10, i.e. none. So the run-to-run movement in login delay is JEI, not other client work. `corr(JEI, menu→in-game) = 0.96`.
* Because JEI runs on the render thread inside login, a ~30 s JEI startup is a ~30 s delay before the world is playable. Anything that shortens JEI start shows up directly as a shorter wait, and the ~1 s profiler overhead is ~1 s of the user's login.

This also means the correct optimisation target is the JEI startup total, not the GUI or recipe phase in isolation. Cutting recipe registration or search construction helps only in proportion to its share of that 30 s.

## Eight Prism/ATM10A launches on the same saved test server

The current 0.1.0 JAR was installed into the existing Prism ATM10A instance and launched eight times against its saved test server, on Minecraft 1.21.1 / NeoForge 21.1.250 with JEI 19.57.0.449, with no pack, config, or server changes except toggling the two profiling options.

| # | Time | `profiling` | `Starting JEI` | Log after rotation |
|---|---|---|---:|---|
| A | 20:53 | on (pre-expansion hooks) | 27.447 s | `logs/2026-10-05-1.log.gz` |
| B | 21:39 | on | 31.413 s | `logs/2026-10-05-2.log.gz` |
| C | 21:44 | on | 30.347 s | `logs/2026-10-05-3.log.gz` |
| D1 | 21:59 | **off** | 30.500 s | `logs/2026-10-05-4.log.gz`, `/tmp/opencode/atm10a-control-1-latest.log` |
| D2 | 22:04 | **off** | 29.500 s | `logs/2026-10-05-5.log.gz`, `/tmp/opencode/atm10a-control-2-latest.log` |
| D3 | 22:06 | **off** | 29.110 s | `logs/2026-10-05-6.log.gz`, `/tmp/opencode/atm10a-control-3-latest.log` |
| E1 | 22:08 | on | 31.244 s | `logs/2026-10-05-7.log.gz`, `/tmp/opencode/atm10a-on-1-latest.log` |
| D4 | 22:10 | **off** | 30.800 s | `logs/latest.log`, `/tmp/opencode/atm10a-control-4-latest.log` |

Run A used the earlier, lighter hook set: its log lists fewer observed mixin hooks and prints the older `Ingredient count: 51744` row instead of the manager before/after rows, so it is not comparable hook-for-hook with the rest.

With profiling off, JEI starts normally and prints its own `Starting JEI took ...` line but no `[JETOptimizer]` profile block; that behaviour was confirmed in D1-D4. This gives an instrumented-versus-uninstrumented comparison on one identical jar, which is the only way here to separate instrumentation cost from pack state:

| Arm | n | Mean | Median | Min | Max | Range |
|---|---:|---:|---:|---:|---:|---:|
| `profiling = true` (B, C, E1) | 4 | 30.113 s | 30.796 s | 30.347 s | 31.413 s | 1.066 s |
| `profiling = false` (D1-D4) | 4 | 29.977 s | 30.000 s | 29.110 s | 30.800 s | 1.690 s |

Mean difference **0.135 s**, which is far below the ~1.1-1.7 s run-to-run spread. The profiler's own cost is **not resolvable at this sample size**, and the honest conclusion is that it is at or below the noise floor rather than about 1 s as an earlier three-versus-four comparison suggested. Including run A in the instrumented arm only moves the mean to 30.088 s, a 0.111 s difference. Warm-up drift is also ruled out: the totals are not monotonic (31.413, 30.347, 30.500, 29.500, 29.110, **31.244**, 30.800), and instrumented run E1 launched immediately after the fastest uninstrumented run D3 and came in 2.134 s *slower*, the opposite of continued cache warm-up.

Run A's 27.447 s is 1.66 s below the best instrumented run and 1.66 s below the best uninstrumented run, which the profiler overhead does not explain. Run A also had the fastest login window (36.873 s), so it was a genuinely faster session rather than a logging artefact, but its cause is still unknown and it must not be used as the comparison baseline.

### Same-build variance of the expanded profiler (B vs C)

Counters were identical across B, C and E1; only timings moved.

| Counter | B | C | E1 |
|---|---:|---:|---:|
| Client recipes | 75,632 | 75,632 | 75,632 |
| Recipe categories | 524 | 524 | 524 |
| Plugin UIDs observed | 163 | 163 | 163 |
| Manager at GUI list build | 68,186 | 68,186 | 68,186 |
| Manager after `onRuntimeAvailable` | 51,744 | 51,744 | 51,744 |
| Net raw delta | −16,442 | −16,442 | −16,442 |
| Runtime removals / calls | 17,729 / 34 | 17,729 / 34 | 17,729 / 34 |
| Runtime additions / calls | 1 / 1 | 1 / 1 | 1 / 1 |
| `addRecipes` batches / recipes | 2,521 / 211,632 | 2,521 / 211,632 | 2,521 / 211,632 |
| Supplier-helper calls | 211,643 | 211,643 | 211,643 |
| `RecipeMap.addRecipe` calls per role | 211,281 | 211,281 | 211,281 |
| Baked index builds / key entries | 7 / 604,658 | 7 / 604,661 | 7 / 604,680 |
| Tooltip candidate strings | 523,998 | 524,001 | 524,020 |

Every structural counter is reproducible to the unit. The one exception is tooltip candidate strings, which drifted 523,998 → 524,001 → 524,020 (+22 over three runs) and moved the baked key count with it, while the ingredient count stayed exactly 68,186. That points at a small number of dynamic tooltip components rather than a changed ingredient set, so sub-1% differences in tooltip candidate counts are also noise.

Timings moved in both directions, which is what uncorrelated machine noise looks like:

| Stage | Run B | Run C | Delta |
|---|---:|---:|---:|
| `Recipe and category registration` | 12.846 s | 12.435 s | −0.411 s |
| ├ `registerRecipes` callbacks outside `addRecipes` | 4.733 s | 5.173 s | +0.440 s |
| └ `RecipeManagerInternal.addRecipes` | 7.240 s | 6.495 s | −0.745 s |
| `JEI GUI runtime construction` | 11.296 s | 11.704 s | +0.408 s |
| `Ingredient filter construction` | 8.160 s | 8.462 s | +0.302 s |
| `Ingredient search index construction` | 7.955 s | 8.231 s | +0.276 s |
| `Ingredient registration` | 4.488 s | 3.377 s | −1.111 s |
| `Ingredient sorting` | 0.053 s | 0.069 s | +0.016 s |
| `Ingredient list construction` | 0.327 s | 0.330 s | +0.003 s |
| `Other (unattributed)` | 2.656 s | 2.705 s | +0.049 s |
| `Total JEI start` | **31.413 s** | **30.347 s** | **−1.066 s** |

Nested children of the same stage also moved independently: tooltip getter 7.053 s → 7.340 s while `RecipeMap` `INPUT` fell 1.386 s → 1.204 s and `RENDER_ONLY` fell 1.225 s → 1.089 s. Plugin totals moved the same way: `jei:neoforge_gui` 11.300 s → 11.708 s and `jei:minecraft` 9.293 s → 8.035 s, even though both plugins' per-phase work was unchanged.

Practical consequences:

* Instrumented runs span 1.066 s and uninstrumented runs span 1.690 s on this machine. A single run cannot resolve anything smaller, so any before/after claim needs repeated same-build samples in both arms.
* The profiler's own cost is below the ~1.1-1.7 s noise floor, so it does not distort the ranking of the large stages.
* `Ingredient registration` alone swung 1.111 s between two identical runs while its stage has no per-source detail. That stage is currently the least explained large region after `Other (unattributed)`.
* Hook call volume is deterministic, so instrumentation cost is stable even though timings are not. Per run the profiler makes 211,643 supplier-helper calls, 845,124 role-map insertions, and about 477,000 prefix string-getter calls.

## Fresh Prism/ATM10A run with the expanded profiler

The rest of this section describes run B, which completed at 21:39 with 31.413 s `Starting JEI`. Run C repeated it at 30.347 s and E1 at 31.244 s, as tabulated above.

This run reported 75,632 synchronized client recipes, 524 categories and 163 plugin UIDs. The expanded recipe breakdown accounted for the 12.846-second parent:

| Disjoint child region inside `createRecipeManager` | Time |
|---|---:|
| `Registering categories` callbacks | 0.347 s |
| `Registering vanilla category extensions` callbacks | 0.338 s |
| `Registering recipe catalysts` callbacks | 0.013 s |
| `Registering advanced plugins` callbacks | 0.119 s |
| `registerRecipes` callback work outside JEI `addRecipes` | 4.733 s |
| `RecipeManagerInternal.addRecipes` calls | 7.240 s |
| `RecipeManagerInternal` construction | 0.021 s |
| Advanced recipe-manager plugin wiring | <0.001 s |
| Role-map compaction | 0.028 s |
| Other recipe-manager method glue | 0.007 s |
| **Parent total** | **12.846 s** |

The 7.240-second `addRecipes` region further split into:

| Child region, nested inside `addRecipes` | Time/calls |
|---|---:|
| `IngredientSupplierHelper.getIngredientSupplier` / category `setRecipe` slot extraction | 4.245 s / 211,643 calls |
| `RecipeMap.addRecipe` `INPUT` | 1.386 s / 211,281 calls |
| `RecipeMap.addRecipe` `OUTPUT` | 0.233 s / 211,281 calls |
| `RecipeMap.addRecipe` `CATALYST` | 0.017 s / 211,281 calls |
| `RecipeMap.addRecipe` `RENDER_ONLY` | 1.225 s / 211,281 calls |
| Other add-batch work | 0.134 s |

JEI plugins submitted 211,632 recipes in 2,521 `addRecipes` batches. Eleven more supplier-helper calls than submitted list entries were observed inside the callback phase; helper calls are counted at their own source method boundary and are not assumed to be one-to-one with batch entries. The 211,281 per-role writes represent recipes that reached role-map insertion; recipe batches can include hidden, unhandled, or otherwise rejected entries. Slow `registerRecipes` callback totals were led by `jei:minecraft` (4.612 s), `farmersdelight:jei_plugin` (1.314 s), `create:jei_plugin` (0.969 s), and `silentgear:plugin/main` (0.924 s). These per-UID values are children of the 4.733-second callback-work row and must not be added again.

The 7.955-second search-index region was accounted for as follows:

| Non-overlapping search child | Time | Candidate strings / baked keys |
|---|---:|---:|
| `mod_names` string getter | 0.322 s | 105,526 candidates / 364 keys |
| `tags` string getter | 0.142 s | 817,290 candidates / 5,852 keys |
| `tooltips` string getter | **7.053 s** | 523,998 candidates / 523,998 keys |
| `unprefixed` name getter | 0.003 s | 74,445 candidates / 74,444 keys |
| `colors`, `creative_tabs`, `identifiers` | ~0 s | Disabled in this config; no string getters called, but empty prefix builders still ran. |
| Baked substring gram-index building | 0.224 s | 7 builder calls, 604,658 total key entries |
| Other search-index work | 0.211 s | UID map population, trim/put and prefix wiring residual |
| **Search-index parent** | **7.955 s** | |

Thus tooltip string generation alone accounts for about **88.7%** of the search-index stage and 7.053 s of the previous 7.602-second search estimate. In source, `ListElementInfo.getTooltipStrings` calls the ingredient renderer's safe plain-tooltip path, strips formatting, lowercases/translates text, splits tooltip lines on whitespace, builds a set, and removes strings already represented by names/IDs/resource paths. The current profiler measures that whole per-ingredient getter, not a separate renderer-versus-normalization split. The baked gram-index work is only 0.224 s in this run, so index data-structure construction is not the principal part of this search interval. Tag and mod-name `LimitedStringStorageBuilder`s reduce candidate strings to substantially fewer unique keys.

Sorting measured 0.053 s, outside `createElementSearch`; the filter constructor was 8.160 s and the GUI runtime was 11.296 s. Do not sum those parents with the 7.955-second search child.

The ingredient-count comparison was directly reproduced:

| Snapshot | Raw manager | Typed manager | Filter base-list entries |
|---|---:|---:|---:|
| GUI base-list construction, before the remainder of runtime callbacks | 68,186 | 68,186 | 68,186 |
| After JEI `onRuntimeAvailable` callbacks | 51,744 | 51,744 | — |
| Net raw manager change during JEI startup | **−16,442** | **−16,442** | — |

The profiler observed **17,729 requested removals in 34 calls** and **one requested addition in one call** between the beginning and end of JEI startup. Run C reproduced all five count rows exactly. This confirms that the two originally quoted counts are not simultaneous: the initial filter list is built at 68,186 entries, and runtime callbacks mutate the ingredient manager before JETOptimizer reads the final 51,744 entries. Requested mutation sizes are not guaranteed successful unique changes—JEI removes by ingredient UID—so the 1,287-request difference from the observed net delta is not evidence of missing rows. The current hooks count mutation requests globally, not by plugin UID, so attribution of those requests to KubeJS versus other plugins remains open.

JEI's own log line `Ingredients are being added at runtime: 1 net.minecraft.world.item.ItemStack` appears in run C at 21:44:44, independently corroborating the single requested addition. It does not corroborate the removals, which JEI does not log in bulk.

The same run measured `kubejs:jei` at 2.505 s during `Sending Runtime`; this is distinct from recipe registration and the GUI/search phases. No KubeJS optimization was attempted. A caught Compact Machines `registerRecipes` NPE was also logged by JEI's normal plugin error handler and startup continued.

## Recipe/category registration: exact work and new breakdown

In JEI 19.57.0.449, `PluginLoader.createRecipeManager(...)` encloses this sequence:

1. `createRecipeCategories(...)`: `registerCategories` callbacks, then `registerVanillaCategoryExtensions` callbacks and category setup.
2. `registerRecipeCatalysts` callbacks.
3. Construction of `RecipeManagerInternal`, including category sorting, the three role-indexed `RecipeMap`s and recipe-category/catalyst data. JEI labels this `Building recipe registry`.
4. `registerAdvanced` callbacks, including recipe-manager plugins, category decorators, and button controllers; `RecipeManagerInternal.addPlugins(...)` attaches plugin managers.
5. `registerRecipes` callbacks.
6. `RecipeManagerInternal.compact()` and public `RecipeManager` finalization.

The new profile makes these phase durations non-overlapping within the `createRecipeManager` parent: the five named `PluginCaller` callback phases; `RecipeManagerInternal` construction; advanced plugin wiring; compaction; and an `Other recipe-manager internals` residual.

### Work inside `registerRecipes`

JEI's `RecipeRegistration.addRecipes` delegates to `RecipeManagerInternal.addRecipes`. The latter iterates the submitted recipe list. For each handled recipe it calls `IngredientSupplierHelper.getIngredientSupplier`, which invokes that category's `setRecipe` to build recipe ingredient slots. It then updates role-specific `RecipeMap`s, records focus links, and stores the recipe in `RecipeTypeData`.

Each role-specific `RecipeMap.addRecipe` walks the role's ingredients, computes recipe-context UIDs through ingredient helpers, de-duplicates a recipe's same-role ingredient UIDs, and updates the UID→recipe-type index and the recipe table. JEI has maps for `INPUT`, `OUTPUT`, `CATALYST`, and `RENDER_ONLY`. The per-recipe UID/index writes are expected to scale approximately as O(R × S), where R is recipes submitted by JEI plugins and S is the total ingredient slots indexed across roles; hash lookups are expected O(1) but invoke mod-supplied ingredient helpers.

Compaction is explicit: `RecipeMap.compact()` calls `RecipeIngredientTable.compact()`, which visits its `IngredientToRecipesMap`s and trims each per-UID recipe `ArrayList`. Its work scales with the number of indexed UID buckets and recipe-list capacity; it is not the same operation as recipe registration or category callbacks.

New profiler rows separate:

* named callbacks: `Registering categories`, `Registering vanilla category extensions`, `Registering recipe catalysts`, `Registering advanced plugins`, and `Registering recipes`;
* the aggregate `RecipeManagerInternal.addRecipes` call time, batch count and recipes submitted;
* category `setRecipe`/slot extraction in `IngredientSupplierHelper.getIngredientSupplier`;
* `RecipeMap.addRecipe` time and call counts by role;
* recipe-manager constructor/index construction, advanced plugin wiring, map compaction, and residual method glue;
* the top ten plugin UIDs inside the `Registering recipes` phase when plugin profiling is enabled.

`RecipeManagerInternal.addRecipes`, supplier building, and map updates are nested inside the `Registering recipes` callback phase. The detailed output splits those nested operations out of the callback subtotal; none should be counted twice. Batch `recipes submitted` is a JEI plugin registration count and can differ from Minecraft's synchronized client recipe count (75,632 in the supplied profile).

## Search/filter construction: exact work and new breakdown

### Sorting is not in the 7.602-second search-index boundary

`JeiGuiStarter.start()` calls `IngredientSorter.sortIngredients(...)` before the `IngredientFilter` constructor reaches `ElementPrefixParser` and `createElementSearch`. Sorting is inside JEI's broad `Building ingredient filter` timer and the full constructor timer, but it is outside `IngredientFilter.createElementSearch`. JETOptimizer now times sorting separately so that the broad 7.785-second filter duration can be split into sorting, search construction, and the constructor's remaining setup/visibility work.

### ElementSearch work and data structures

On the normal search path, `IngredientFilter.createElementSearch(...)` creates `ElementSearch`; with JEI's low-memory/slow-search option enabled it instead creates `ElementSearchLowMem`, which copies the list and performs linear scans on queries. The 7.602-second ATM index-construction timing corresponds to the indexed path.

`ElementSearch` first makes an `allElements` `HashMap` by iterating all element infos and computing ingredient UIDs. Then it walks enabled `PrefixInfo`s and, for each element info, asks for that prefix's `Collection<String>`, trims/skips blank strings and inserts the remaining values into its storage builder. Finally, it builds a prefixed storage for each source and combines them. This is a per-ingredient × enabled-prefix walk plus string generation, storage insertion and index building.

The JEI source uses `BakedSubstringIndexBuilder` by default. Its shaded binary class is included in the JEI runtime/source artifacts but its implementation is not present in the JEI source JAR; its bytecode in the exact 19.57.0.449 jar was inspected. The builder:

* stores parallel key/value lists;
* for each key, emits 1-, 2-, and 3-Java-`char` grams into a fastutil `Long2ObjectOpenHashMap<IntArrayList>` posting index;
* uses a `Long2IntOpenHashMap` to avoid adding the same gram more than once for a given key;
* converts the key/value lists to arrays and each posting list to `int[]` at `build()` time.

The build work is approximately O(sum of indexed key lengths × up to three gram widths), plus hash-map growth and postings conversion. This is distinct from *generating* source strings, which can invoke ingredient helpers/renderers for every element. Search-query tokenization is a later `IngredientFilter.getElements()` operation, not this startup index build.

`LimitedStringStorageBuilder` wraps the default index for mod-name, tag, creative-tab and color prefixes: it associates duplicate strings with identity sets and only sends a new string key to its backing index. Other sources can send each generated value directly to the baked index.

### Indexed sources and dependencies

JEI 19.57.0.449 defines these `ElementPrefixParser` sources:

| Prefix | Source work | Settings/state that affect it |
|---|---|---|
| `unprefixed` | Existing lowercase display names and aliases. | `searchIngredientAliases` affects alias/name construction in the preceding list-build stage; this source is always enabled. |
| `mod_names` | Display mod names, optionally mod IDs, configured aliases and short names; case/whitespace normalization and de-duplication. | `modNameSearchMode`, `searchModIds`, `searchModAliases`, `searchShortModNames`, mod aliases and localization. |
| `tags` | Ingredient-helper tag stream converted to tag path strings. | `tagSearchMode`, registry tag data and ingredient helper behavior. |
| `tooltips` | Safe plain renderer tooltips, formatting removal, lowercase normalization, whitespace splitting and de-duplication. | `tooltipSearchMode`, `searchAdvancedTooltips`, renderer/helper behavior and language/tooltip data. |
| `creative_tabs` | For item ingredients, iterate visible category tabs and call `CreativeModeTab.contains(stack)`, then normalize matching names. | `creativeTabSearchMode`, creative-tab contents and resource state. |
| `colors` | Ingredient-helper colors mapped to localized closest-color names and de-duplicated. | `colorSearchMode`, helper/color palette and localization. |
| `identifiers` | Resource-location strings. | `resourceLocationSearchMode`, registered ingredient/resource IDs. |

Disabled source modes skip their N-element string getter loop, although JEI still creates/builds an empty prefix storage. JEI plugins can override the advanced search storage factory; the baked-builder counters identify the stock builder path and do not claim custom builders use the same data structure.

New opt-in search detail reports getter time/calls and returned string-candidate counts per prefix, plus elapsed baked gram-index build time/call count/key entries per prefix. `Collection.size()` is read once per source getter; key counts read the existing builder list size at the build boundary. These are not counts of unique substrings/grams and require no extra pass over ingredients, strings, or postings. Search-stage residual includes UID-map construction, trim/put and combined-prefix wiring.

## Ingredient count comparison

The supplied values are different lifecycle snapshots:

* JEI's `IngredientFilter` log reports the `IngredientListElementFactory.createBaseList` output during `JeiGuiStarter.start` in `registerRuntime`.
* The current JETOptimizer final ingredient count is read after JEI's `onRuntimeAvailable` callbacks. KubeJS is one such callback, measured at 2.402 seconds in the provided run, and its known integration path can add/remove manager ingredients at runtime.

The 16,442 lower final count is consistent with post-list runtime removals, but the supplied trace has no initial manager snapshot or runtime mutation totals, so it does not prove which plugin removed what. The new profile records manager raw/typed sizes at base-list construction, filter-list entry count, manager raw/typed sizes after runtime callbacks, and requested runtime additions/removals. At the same snapshot, `getAllIngredients` and `getAllTypedIngredients` are backed by the same `RegisteredIngredientIndex`; those counts should match. `createBaseList` can be smaller if `ListElementInfo.createFromElement` catches broken ingredient metadata, so the base-list count is recorded separately from manager size.

Use the next ATM10A profile to determine whether the observed `68,186 → 51,744` change is matched by runtime removal requests and whether any same-time raw/typed/list discrepancy remains. **Resolved by runs B and C:** both recorded 68,186 raw/typed with 68,186 filter base-list entries before the remainder of runtime callbacks, 51,744 raw/typed after `onRuntimeAvailable`, a −16,442 net raw delta, and 17,729 removals in 34 calls plus one addition in one call. Raw and typed counts matched at both snapshots, and `IngredientFilter` base-list entries matched the manager exactly at GUI-list build, so no raw/typed/list discrepancy remains at those two snapshots. Still open: per-plugin attribution of the removals, and whether the 1,287-request surplus corresponds to repeated or overlapping UIDs.

## Three generations in one process: cold join, reconnect, and local world

`~/jetoptimizer-runs/atm10a-reconnect-20261005-230658.log` is the run the generation tracking was
built for. One client process, three joins, so the first row is a cold first connection and the other
two are same-process joins. This is the first recorded evidence about reconnects, so it is reported
separately from the eight cold-launch rows and is not averaged with them.

| | Generation 1 | Generation 2 | Generation 3 |
|---|---:|---:|---:|
| Join | cold first join | in-game reconnect | in-game join |
| Target | `play.gamitronservers.com` | `play.gamitronservers.com` | integrated (local) world |
| Total JEI start | 29.371 s | 26.196 s | 37.492 s |
| Recipe and category registration | 12.650 s | 10.515 s | 11.915 s |
| `addRecipes` (nested) | 6.944 s | 5.999 s | 6.597 s |
| `setRecipe` calls | 4.255 s / 211,643 | 3.307 s / 211,907 | 3.688 s / 213,122 |
| JEI GUI runtime construction | 11.057 s | 10.149 s | 11.387 s |
| Ingredient search index construction | 7.662 s | 6.837 s | 7.685 s |
| `Sending Runtime` (values read from JEI's own log) | 2.750 s | 2.875 s | 10.430 s |
| Other (unattributed) | 2.720 s | 2.850 s | 10.396 s |
| Ingredients at GUI list build | 68,186 | 70,223 | 70,221 |
| Ingredients after runtime callbacks | 51,744 | 51,746 | 51,744 |
| Structural fields differing from previous | n/a | 20 of 40 | 21 of 40 |

Generation 1 vs 2 is the useful comparison: same process, same mod set, same target, 79 s apart.

**The reuse hypothesis is now disproved rather than merely unsupported.** Generation 2 kept every
mod-side field identical: 163 plugin UIDs with the same set hash (`-886537338`), 524 recipe
categories, 75,632 client recipes, 2,521 `addRecipes` batches, 7 baked-index builds, and identical
call counts for every non-`item_stack` tooltip type. A fingerprint built from those fields would have
matched. Yet 20 of 40 fields changed, because the *server* delivered different content: recipes
211,632 → 211,896, ingredients 68,186 → 70,223, runtime removal requests 17,729 → 19,764, tooltip
candidate strings 524,003 → 540,483. Every field that moved is server payload; every field that held
still is client/mod-side. A cache keyed on the stable half would have served stale results on the
first reconnect.

**The 3.175 s reconnect gain is JIT warmup, not avoided work.** Generation 2 did *more* work, about
3% more recipes and ingredients, and still finished 10.8% faster, so per-unit cost fell roughly 13%.
The drop is spread across every region rather than concentrated: `setRecipe` −22.3%, `addRecipes`
−13.6%, search index −10.8%, GUI runtime −8.2%, ingredient registration −6.0%. The hottest loops fall
most, which is the JIT signature. No region collapses, so nothing is being skipped on a reconnect, and
there is no region whose work a cache could be removing today.

**The supplier and recipe-map regions stay exactly 1:1 across all three generations.** `setRecipe`
calls exceed submitted recipes by exactly 11 every time (211,643/211,632, 211,907/211,896,
213,122/213,111), so the `getFocusLinks` fallback contributes a constant 11 calls, as the source
predicted. `RecipeMap.addRecipe` calls equal recipes minus exactly 351 every time
(211,281/211,632, 211,545/211,896, 212,760/213,111), the 351 recipes under hidden or unknown
categories. Neither region contains a repeat to memoise.

**The tooltip cost is diffuse across plain items, not concentrated in a mod or an exotic type.**
Per-type rows for generation 1:

```text
item_stack: 6.753 s / 67412 calls   (~100 us per item)
fluid_stack: 0.003 s / 430
mekanism.api.chemical.ChemicalStack: 0.002 s / 112
cy.jdkdigital.productivebees...BeeIngredient: 0.001 s / 221
com.ultramega.refinedtypes.type.TypeStack: 0.000 s / 3
it.zerono.mods.extremereactors.api.coolant.Coolant: 0.000 s / 1
```

Generation 2 repeats it: `item_stack` 5.768 s / 69,449 calls, everything else below 0.003 s combined.
`item_stack` is 98.8-99.5% of the stage in both generations, so there is no outlier ingredient type
to exclude and no mod-specific pathology to report. This is ordinary `appendHoverText` cost.

**The post-index removal waste is structural and reproduces 3 out of 3.** In every generation the
search index is built over the full base list first, then the large removal batch arrives about 3 s
later:

| | Index built over | Large removal batch | Gap |
|---|---:|---:|---:|
| Generation 1 | 68,186 at 23:02:05.929 | 14,239 at 23:02:08.983 | 3.05 s |
| Generation 2 | 70,223 at 23:03:22.055 | 16,274 at 23:03:25.068 | 3.01 s |
| Generation 3 | 70,221 at 23:04:54.515 | 16,274 at 23:04:57.839 | 3.32 s |

A smaller set of plugin removals also happens *before* the index in all three, during `registerRecipes`.
So roughly a quarter of the tooltip work is spent on ingredients that are gone before the player opens
JEI, and the ordering is not incidental. Avoiding it means building the index after the runtime is
known, which changes when mod tooltip code runs relative to the player joining; that is a behaviour
change, not a safe optimisation.

**`Sending Runtime` is the largest region that had no named line in the profile.** It is 2.750-2.875 s
on the remote joins and 10.430 s for the local world, and it was landing in `Other (unattributed)`
(2.720 / 2.850 / 10.396 s) purely because the report never printed it. Generation 3's apparent 37 s
regression is almost entirely this one phase, not JEI registration. It needs no new hook, because
`PluginCaller.callOnPlugins` already routes it through `PluginCallerMixin`; the profiler now prints
it as a top-level plugin phase, subtracts it from the unattributed remainder, and breaks it down per
plugin, so the next run attributes it rather than merely measuring it.

## Second three-generation run: the unattributed region is closed

The same three-generation procedure was repeated on a fresh cold launch after the phase reporting was
added, in the same mod set: cold remote join, in-game reconnect to the same remote server, then a
local single-player world.

| Join | cold remote join | in-game reconnect | local world |
|---|---:|---:|---:|
| Total JEI start | 29.734 s | 25.095 s | 39.905 s |
| Disjoint stage sum | 26.833 s | 22.116 s | 29.509 s |
| `Sending Runtime` | 2.939 s | 3.002 s | 10.425 s |
| `Configuring JEI` | 0.003 s | 0.002 s | 0.003 s |
| Remainder before phase reporting | 2.720 s | 2.850 s | 10.396 s |
| Remainder after phase reporting | 0.000 s | 0.000 s | 0.000 s |
| Structural fields differing from previous | n/a | 20 of 40 | 21 of 40 |

**The profile now accounts for essentially all of JEI's startup.** Disjoint stages plus `Sending Runtime` plus `Configuring JEI` leave a raw residual of -0.041 s, -0.025 s and -0.032 s, so the measured regions slightly overlap JEI's own `LoggedTimer` total and the printed remainder clamps to zero. Before, 2.7-10.4 s had no name at all. The same 20-of-40 and 21-of-40 structural differences reproduced, so the payload-instability result from the first run replicates.

**`Sending Runtime` is now attributed, and it is two mods, not JEI.** `Plugin callback time not attributed` is 0.002-0.007 s, so essentially the whole phase is plugin callbacks:

| `onRuntimeAvailable` callback | cold remote join | in-game reconnect | local world |
|---|---:|---:|---:|
| `kubejs:jei` | 2.755 s | 2.887 s | 2.907 s |
| `createthrusters:jei` | 0.044 s | 0.035 s | **7.386 s** |
| `ae2:core` | 0.083 s | 0.040 s | 0.061 s |
| Phase total | 2.939 s | 3.002 s | 10.425 s |

KubeJS costs about 2.9 s on every join regardless of target. Create Thrusters costs 0.04 s on the remote joins and **7.386 s on the local world**, which is 71% of that phase and 18.5% of the entire 39.905 s JEI startup. This is the whole of the previously "unexplained" local-world regression, and it is Create Thrusters' own `onRuntimeAvailable`, not JEI and not JETOptimizer. The 7.386 s figure is cross-validated: the independent per-plugin totals that the profiler has always emitted report `createthrusters:jei 7.386` for the local world in both this run and the previous one, matching the new per-phase attribution exactly.

**`Ingredient registration` is JEI's own vanilla plugin, not mod code.** `Registering ingredients` is 3.845 / 2.399 / 4.435 s and `jei:minecraft` accounts for 3.836 / 2.396 / 4.432 s of it, i.e. 99.0-99.9%. All other mod callbacks in that phase together are below 0.01 s. `Registering extra ingredients` is 0.006 s and `Registering search ingredient aliases` is 0.025 s. This answers the swing recorded earlier in this document, 4.488 / 3.377 / 2.638 / 3.737 s across runs with no per-source detail: it is JEI's own vanilla ingredient registration varying, and no mod callback is responsible. The measurement gap is closed rather than merely moved.

**`Registering Runtime` turned out to be JEI's own GUI plugin, not a separate region.** The first version of this reporting decided whether a phase was top-level at phase start only, so it classified `Registering Runtime` (11.670 / 9.870 / 12.189 s) as an independent region. It is not: `NeoForgeGuiPlugin.registerRuntime` calls `JeiGuiStarter.start`, so the `JEI GUI runtime construction` stage (11.663 / 9.866 / 12.184 s) is nested inside the phase, and the two agree to within 0.007 s. The per-plugin totals confirm it directly: `jei:neoforge_gui` is 11.666 / 9.866 / 12.185 s against the stage's 11.663 / 9.866 / 12.184 s. Subtracting both from the total double counted roughly a third of startup. A phase frame now records whether a stage was opened while it was running, and only a phase that neither started inside a stage nor opened one is subtracted.

**Consequence: the dominant region is not mod code.** The 11.7 s `JEI GUI runtime construction` is `jei:neoforge_gui` calling JEI's own `JeiGuiStarter.start`, and inside it 8.0 s is `Ingredient filter construction`, 7.8 s is search-index construction, and 6.8 s is `tooltips` string extraction across 68,186 ingredients that are three-quarters vanilla. There is no mod callback to remove there. The only mod-attributable costs of consequence are KubeJS at a fixed ~2.9 s per join and Create Thrusters at 7.386 s on the local world, both inside mod code that JETOptimizer cannot change.

**Profiler overhead is unchanged.** Totals moved 29.371 → 29.734 s, 26.196 → 25.095 s and 37.492 → 39.905 s against the previous run. The local-world spread alone is 2.4 s, which swamps the per-callback movement, and the hot paths in this iteration do strictly less work than before (no config read per sample, no stack peek and string compare per recipe insert, one type-UID lookup per tooltip instead of two). No overhead regression is visible, and the effect is below the noise floor, so per the existing guidance no further launches should be spent bounding it.

**Conclusion for this iteration.** With a real reconnect measured, none of the three candidate
regions yields a safe optimisation: the tooltip phase is connection-bound and diffuse, the supplier
and recipe-map phases have no redundancy in any generation, and the only provable waste requires
reordering JEI's startup. The measurement is the deliverable; a code change here would be a guess.
That conclusion is now stronger rather than weaker: the profile accounts for ~100% of startup, the two
regions that looked like JEI's own unattributed overhead are JEI's own vanilla plugin and JEI's own
GUI plugin, and the only remaining mod-sized costs are inside KubeJS and Create Thrusters.

## Profiler smoke test and next measurement

The updated hooks were smoke-tested in a small vanilla integrated `runClient` with JEI 19.57.0.449. All JEI startup, phase, recipe-ingestion, and search-index hooks applied without Mixin errors. One run reported:

* total JEI start 0.671 s; 1,290 client recipes; 16 categories; 1,691 raw/typed manager ingredients and 1,691 base-list entries both before and after runtime callbacks; zero runtime ingredient add/remove requests;
* recipe/category registration 0.309 s, split into category callbacks 0.020 s, vanilla extensions 0.005 s, catalysts 0.002 s, advanced callbacks 0.010 s, recipe callbacks outside `addRecipes` 0.155 s, and nested `RecipeManagerInternal.addRecipes` 0.084 s; the latter received 3,499 recipes in 17 batches;
* inside `addRecipes`, category supplier/layout creation 0.042 s (3,499 calls), role map insertion 0.036 s across input/output/catalyst/render-only maps (3,499 calls per role), and other batch processing 0.006 s;
* search-index construction 0.091 s: returned string candidates—mod names 3,382, tags 7,132, tooltips 5,933, unprefixed names 1,691; baked index construction 0.008 s across 7 builds / 8,120 key entries; remaining search work 0.019 s;
* filter sorting 0.004 s and full filter construction 0.110 s. These are nested intervals.

These values validate hook coverage/output only; they are not representative ATM performance benchmarks. The local world has very few recipes and ingredients. The source-string and per-role counters were tested at this small scale; the large-pack run is needed to assess profiler overhead.

## Next measurement

The expanded profiler has now been run eight times on ATM10A. JEI accounts for 76-78% of the login delay, so that total is the optimisation target. Profiler overhead sits below the noise floor, and the disconnect/rejoin path is still unmeasured.

**Method of the eight recorded launches.** Each launch was a separate Minecraft process that performed one manually initiated join to the same saved server, and the client remained in that single world until the run ended. No client process survived a disconnect and rejoined, so all eight rows are cold-process/first-connection measurements and none of them is a reconnect benchmark. A disconnect/rejoin was performed interactively during development, but no profile block from that sequence was kept, so it cannot serve as a data point. JETOptimizer did not log a connection generation at the time, which is exactly the gap the generation tracking now closes; the reconnect case is covered separately by the three-generation run above and is deliberately not folded into the eight-row average.

1. **Profiler overhead is a solved-enough question.** Four instrumented and four uninstrumented launches differ by 0.135 s against a 1.1-1.7 s spread. Do not spend more launches on this; if a bound is ever needed, alternate both arms over six launches each and discard the first after any config change.
2. **Reconnect and invalidation: resolved for the same-server case.** One process now covers a cold join, an in-game reconnect to the same remote server, and a join to a local world. See "Three generations in one process" above. The generation lines behave as intended, and the `Structural comparison vs previous connection` block is what makes the result trustworthy: it showed 20 of 40 fields differing on a same-server reconnect, which is the evidence that rules out a reuse layer keyed on mod-side state. Two caveats are recorded in code. First, the profiler originally labelled any generation above 1 as "in-game reconnect", which was wrong for a local world that has no address; `currentServerAddress()` now names an integrated server explicitly and the join kind is derived from the generation *and* the observed target. Second, JEI runs a full startup for the local world too, so a local join is not a valid data point for remote-server reconnects.
3. **Unexplained regions: closed.** The second three-generation run attributes everything. `Other (unattributed)` is 0.000 s with a raw residual of -0.025 to -0.041 s, so the profiled regions account for about 100.1% of JEI's `LoggedTimer` total. `Sending Runtime` is 2.939 / 3.002 / 10.425 s of which `kubejs:jei` is a fixed ~2.9 s and `createthrusters:jei` is 7.386 s on the local world only. `Ingredient registration` is 99.0-99.9% `jei:minecraft`. **Next action:** none. Any further reduction has to come from KubeJS's or Create Thrusters's own `onRuntimeAvailable`, which is mod code, or from reordering JEI's startup, which is a behaviour change.
4. **Do not reuse run A.** Its 27.447 s is unexplained and is not a valid baseline; use the mean of a fresh arm instead.
5. **Do not treat the dominant region as reuse-able work.** `docs/JEI_SOURCE_ANALYSIS.md` shows the 7.265 s tooltip stage is bound to `ClientLevel` and the local `Player` inside `SafeIngredientUtil.getPlainTooltipForSearch`, and that the supplier and recipe-map regions have no duplicate computation to remove. Any proposal to cache those needs new evidence, not the existing timings.

The disjoint stage hierarchy to compare against is:

```text
Total JEI start
├─ Recipe/category registration
│  ├─ named callback phases
│  ├─ registerRecipes callback time outside RecipeManagerInternal.addRecipes
│  ├─ RecipeManagerInternal.addRecipes
│  │  ├─ IngredientSupplierHelper category layout/slot extraction
│  │  ├─ RecipeMap.addRecipe by role
│  │  └─ other per-batch work
│  ├─ RecipeManagerInternal construction / advanced plugin wiring / compaction
│  └─ other recipe-manager internals
└─ JEI GUI runtime construction
   └─ IngredientFilter construction
      ├─ sorting
      └─ search-index construction
         ├─ PrefixInfo string sources by prefix
         ├─ baked substring gram-index builds
         └─ UID maps, key puts and other search glue
```

Plugin UID totals and these sub-stages overlap their enclosing callback/parent stage and should remain in the diagnostic view, not be added into the total. All wall-clock numbers in this document come from single launches on a machine whose same-build spread is 1.1-1.7 s; quote them as one sample of a noisy interval, not as measurements. This work changes instrumentation only; it does not cache, skip JEI work, or change displayed recipes.

## Phase 4: first optimization, and the region it exposed

### What the source trace closed

`ListElementInfo.getStrings` is the last step of the tooltip region and the only part of it that JEI
owns:

```java
Set<String> getStrings(List<Component> tooltip) {
    for (FormattedText component : tooltip) {
        String string = component.getString();
        string = StringUtil.removeChatFormatting(string);   // regex (?i)§[0-9A-FK-OR]
        string = Translator.toLowercaseWithLocale(string);
        addSplitStrings(result, string);                    // regex \s+ split
    }
}
```

Everything before it is `ingredientRenderer.getTooltip(...)`, which is mod-supplied and bound to the
live `ClientLevel` and `Player`. Both regexes are pure and run once per tooltip line per ingredient,
so they are the only part of the region that can be replaced without touching connection-bound state.

Two other structural questions were answered from source and closed:

- `ElementSearch.findElement` is a `HashMap` lookup on the ingredient UID, and
  `RegisteredIngredientIndex.removeAll` is hash based, so the 17,729 runtime removals are **not**
  accidental O(N*M). The per-removal cascade costs a few microseconds each.
- `IngredientBlacklistInternal` does notify visibility once per removed ingredient with a
  single-element set, but that listener path only flips a field and invalidates a cache, so it is
  cheap too.

The search index is built over all 68,186 ingredients *before* `onRuntimeAvailable` removes 17,729 of
them, so roughly a quarter of the region is discarded work. That is real, but the only way to avoid it
is to construct the `IngredientFilter` after the removals, and the filter is built inside
`JeiGuiStarter.start`, which plugins need during `registerRuntime`. Deferring it would move about 7 s
from startup into the first JEI screen open, which is a worse trade, so it was not done.

### Shipped optimization: non-regex search text

`dev.jetoptimizer.SearchTextOptimization` replaces the two regexes with linear scans:

| Original | Replacement | Semantics preserved |
| --- | --- | --- |
| `ChatFormatting.stripFormatting` | `stripFormatting` | `(?i)§[0-9A-FK-OR]`, non-overlapping 2-char removal |
| `WHITESPACE_PATTERN.split` | `splitOnWhitespace` | `trim()` first, then `\s` = `[ \t\n\x0B\f\r]` only |

Two details are easy to get wrong and are handled explicitly:

- `String.trim()` removes every character `<= ' '`, while `\s` matches only six characters. Trimming
  stays a separate step, otherwise control characters such as `\u0001` would start splitting tokens.
- `(?i)[K-O]` matches `k l m n o`, not only `k` and `o`. An early version of the replacement dropped
  the italic, strikethrough and underline codes; the differential test caught it.

Equivalence is checked against the two regexes used as oracles over 34 targeted inputs and 500,000
random strings drawn from an alphabet containing formatting codes, all ASCII whitespace, control
characters, and non-ASCII letters: every input produces identical output. A microbenchmark of the
replaced work over 400,000 realistic tooltip lines measures 0.129 s for the regex path against 0.070 s
for the fast path, a 1.83x speedup of that work.

**Honest sizing:** that is roughly 0.05 s of the 29.7 s cold total. This is a real and free saving, but
it is not a meaningful reduction of startup time. It is shipped because it is provably equivalent and
because the same instrumentation proves whether anything larger is available in this region.

The new `search-text pipeline` counter times JEI's whole pure pipeline. The difference between that
number and the tooltip source-string total is mod-supplied tooltip rendering, and it is the hard
ceiling for any optimization that does not touch mod code.

### The region the counters exposed

Comparing the GUI runtime stage against the only two blocks JEI times itself:

| Generation | GUI runtime | Ingredient filter | Not covered by JEI's own timers |
| --- | --- | --- | --- |
| 1, cold remote | 11.663 s | 8.034 s | 3.629 s |
| 2, reconnect | 9.866 s | 6.587 s | 3.279 s |
| 3, local world | 12.184 s | 8.691 s | 3.493 s |

`JeiGuiStarterMixin` places ten ordered invocation boundaries inside `JeiGuiStarter.start`,
covering ingredient-list construction, the filter, bookmark factory/codec, lookup history, both
overlays, bookmark construction/loading, the recipes GUI, and input handlers. A method-entry gate
also captures the setup before ingredient-list construction, giving eleven measured intervals in
total; the final interval closes at `RETURN`. That opening setup includes helper/config retrieval and
`JeiGuiColors.onResourceManagerReload`, which reads JEI's `gui/colors.json` resource stack and parses
its color entries. The report retains an uncovered remainder for timer/instrumentation gaps. All ten
invocation targets were verified to resolve exactly once, in ascending bytecode order, against the
compiled `JeiGuiStarter.start` of JEI 19.57.0.449.

The corrected Mixin was exercised by an automated cold join, reconnect, and second reconnect on the
same server. No `JETOptimizer` Mixin errors occurred; all three runs reported eleven intervals with
zero uncovered GUI-runtime time, and the whitespace fast path applied on every call:

| Generation | Total JEI start | GUI runtime | Ingredient filter | Recipes GUI | Tooltip source strings | Pure search-text pipeline |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1, cold | 25.963 s | 10.130 s | 7.188 s | 2.577 s | 6.246 s | 0.119 s |
| 2, reconnect | 24.361 s | 9.610 s | 6.490 s | 2.824 s | 5.487 s | 0.105 s |
| 3, reconnect | 24.713 s | 9.725 s | 6.649 s | 2.772 s | 5.555 s | 0.113 s |

The GUI runtime is now attributed: ingredient-filter construction consumes 6.5-7.2 s, mostly
mod-supplied tooltip rendering; the pure search-text work is about 0.1 s; and the recipes GUI
constructor is 2.6-2.8 s. A candidate cache for JEI's recipe-category transfer-handler comparator
reused 495 of 992 checks, but the entire sort took only 0.001 s and the recipes-GUI gate remained
2.7-2.8 s. That optimization was discarded.

## Automated ATM10A runs and the three-second target

`tools/atm10a-jei-test.py --reconnects 1` launches the existing Prism instance with its saved account,
joins the configured server, checks that the Mixin gates and fast paths applied, disconnects through
the UI, and reconnects in the same process. `--attach --reconnects N` repeats reconnects in an
already-running instance. The runner does not edit the mod list or any mod configuration; it requires
profiling and `fastSearchText` to already be enabled.

The full synchronous JEI startup still takes 23.5-26.6 s. Three independent required regions account
for approximately 18-20 s before counting the rest: recipe/category registration (9.2-10.2 s),
ingredient-filter construction (6.1-7.5 s), and `Sending Runtime` (2.6-2.7 s). Most of the filter's
cost is live tooltip rendering; `Sending Runtime` is primarily the KubeJS JEI callback. These execute
JEI and other mods' registration/rendering work, so a behavior-preserving change confined to
JETOptimizer cannot reduce them to three seconds. The safe regex replacement only targets about a
tenth of a second of the pure text pipeline.

Reaching a three-second *world-entry* time would therefore require delaying or suppressing substantial
JEI functionality while the world opens; reaching a three-second *complete JEI-ready* time would
require skipping required recipe, ingredient, or mod callback work. Both are different behavior and
are not produced by a cache or a faster JETOptimizer hot path.
