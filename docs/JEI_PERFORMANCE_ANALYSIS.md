# JEI startup performance analysis

**Source baseline:** JEI 19.57.0.449, Minecraft 1.21.1, NeoForge 21.1.x.
**Current goal:** explain the major JEI startup regions with measurements. No reconnect cache or behavior-changing optimization is implemented.

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

Use the next ATM10A profile to determine whether the observed `68,186 → 51,744` change is matched by runtime removal requests and whether any same-time raw/typed/list discrepancy remains. Do not use the post-callback count as an initial filter or cache fingerprint without clarifying that delta.

## Profiler smoke test and next measurement

The updated hooks were smoke-tested in a small vanilla integrated `runClient` with JEI 19.57.0.449. All JEI startup, phase, recipe-ingestion, and search-index hooks applied without Mixin errors. One run reported:

* total JEI start 0.671 s; 1,290 client recipes; 16 categories; 1,691 raw/typed manager ingredients and 1,691 base-list entries both before and after runtime callbacks; zero runtime ingredient add/remove requests;
* recipe/category registration 0.309 s, split into category callbacks 0.020 s, vanilla extensions 0.005 s, catalysts 0.002 s, advanced callbacks 0.010 s, recipe callbacks outside `addRecipes` 0.155 s, and nested `RecipeManagerInternal.addRecipes` 0.084 s; the latter received 3,499 recipes in 17 batches;
* inside `addRecipes`, category supplier/layout creation 0.042 s (3,499 calls), role map insertion 0.036 s across input/output/catalyst/render-only maps (3,499 calls per role), and other batch processing 0.006 s;
* search-index construction 0.091 s: returned string candidates—mod names 3,382, tags 7,132, tooltips 5,933, unprefixed names 1,691; baked index construction 0.008 s across 7 builds / 8,120 key entries; remaining search work 0.019 s;
* filter sorting 0.004 s and full filter construction 0.110 s. These are nested intervals.

These values validate hook coverage/output only; they are not representative ATM performance benchmarks. The local world has very few recipes and ingredients. The source-string and per-role counters were tested at this small scale; the large-pack run is needed to assess profiler overhead.

Re-run ATM10A with `profiling=true` and `pluginProfiling=true`, then compare the new disjoint recipe-category breakdown and search source/bake/residual breakdown against the supplied 27.447-second baseline. The hierarchy is:

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

Plugin UID totals and these sub-stages overlap their enclosing callback/parent stage and should remain in the diagnostic view, not be added into the exclusive 27.447-second total. This work changes instrumentation only; it does not cache, skip JEI work, or change displayed recipes.
