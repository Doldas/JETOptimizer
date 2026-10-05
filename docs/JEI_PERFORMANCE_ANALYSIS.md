# JEI startup performance analysis

**Source baseline:** JEI 19.57.0.449, Minecraft 1.21.1, NeoForge 21.1.x.
**Current goal:** explain the major JEI startup regions with measurements. No reconnect cache or behavior-changing optimization is implemented.

**Read this first:** the wall-clock numbers below are single launches on a machine whose same-build spread is 1.1-1.7 s. Structural counters are reproducible to the unit; timings are not. Jump to [The 30-second login delay is JEI](#the-30-second-login-delay-is-jei) for the headline result, then [Eight Prism/ATM10A launches](#eight-prismatm10a-launches-on-the-same-saved-test-server) for the launch table and what is still unmeasured.

**Candidate regions were then checked against the JEI sources rather than only against timings.** See `docs/JEI_SOURCE_ANALYSIS.md` for why the three dominant regions offer no safe redundancy, and `docs/PROFILER_MIXINS.md` for the connection-generation and by-type tooltip instrumentation that now exists to answer the remaining questions.

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
| `Sending Runtime` (from JEI's own log) | 2.750 s | 2.875 s | 10.430 s |
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

**`Sending Runtime` is the largest unattributed region.** It is 2.750-2.875 s on the remote joins and
10.430 s for the local world, and because it has no stage hook it lands in `Other (unattributed)`
(2.720 / 2.850 / 10.396 s). Generation 3's apparent 37 s regression is almost entirely this one phase,
not JEI registration. It is worth its own hook: 9% of a remote join and 28% of a local join, currently
invisible inside a residual bucket.

**Conclusion for this iteration.** With a real reconnect measured, none of the three candidate
regions yields a safe optimisation: the tooltip phase is connection-bound and diffuse, the supplier
and recipe-map phases have no redundancy in any generation, and the only provable waste requires
reordering JEI's startup. The measurement is the deliverable; a code change here would be a guess.

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
3. **Unexplained regions.** `Other (unattributed)` is 2.656-2.850 s on remote joins but 10.396 s for a local world, and it is dominated by JEI's `Sending Runtime` phase (2.750 / 2.875 / 10.430 s), which has no stage hook. `Ingredient registration` also swung 4.488 s → 3.377 s → 2.638 s → 3.737 s across runs with no per-source detail. **Next action:** add a hook around `Sending Runtime` and per-source attribution for ingredient registration. Both are measurement gaps, not proven costs.
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
