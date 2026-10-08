# Recipe and GUI startup optimization candidate

The target is a lower **complete JEI-ready** time, initially below 20 seconds and eventually below
15 seconds. These are acceptance targets, not achieved measurements. No ATM10A client or server is
available in the implementation environment, so this patch has no new modpack A/B timing result.

## Removed work

### Unfocused recipe visibility

In JEI 19.57.0.449, `RecipeManagerInternal.isRecipeVisible(RecipeTypeData, Object, IFocusGroup)` calls
`FocusLink.getVisibleIngredientIndexes` and tests only whether its result is null. The callee builds
focus-match sets, candidate-index sets, and complete visible-index sets. For an empty focus group,
the caller needs only whether there is **any** index whose ingredients are visible in every linked
slot. The new path returns after finding the first such index and creates none of those sets.

This preserves the null/non-null result, including empty candidate ranges and blank/null ingredients.
It does not return actual visible indexes. The redirect is therefore limited to the manager's boolean
query; layout rendering, linked rotations and nonempty focus queries still use JEI's original method.
It still calls the live visibility predicate and keeps JEI's existing cache invalidation/lifecycle.
Visibility predicates are expected to be queries; this changes how many times they are called.

The recorded recipes-GUI gate is 2.6–2.8 seconds. Category visibility is on that constructor's call
path, making this a useful candidate, but that gate is an upper bound on recoverable time from this
path, not a promised saving. The patch does not move GUI initialization to the first screen open.

### Recipe ingredient snapshots and empty-role indexing

`IngredientSupplierBuilder.buildIngredientSupplier` creates nested stream pipelines per role and
slot for every recipe. The replacement loops keep the same order, drop nulls from role lookup lists,
and retain nulls in linked-slot lists. JEI's own `RecipeIngredientSupplier`, `FocusLink` and `Slot`
constructors still make immutable owned copies. No recipe/category callback is skipped or cached.

`RecipeMap.addRecipe` also creates a temporary `IngredientUidIndex` even for roles with no
ingredients. The patch bypasses this empty operation only for the exact JEI immutable supplier class.
Nonempty roles and custom/subclass suppliers keep the original index-building algorithm. Existing
role invocation counts still include the bypassed calls, so profiler counts remain comparable.

The builder pool now checks its enabled state before clearing anything and is explicitly confined
to the `Registering recipes` phase. Previously the RETURN hook cleared builders even when pooling
was disabled, and the pool could become active again after registration ended.

## Configuration and compatibility

Keep the same existing configuration for both A/B arms and change only these options:

```toml
[general]
enabled = true
experimentalOptimizations = true
profiling = true
pluginProfiling = true

[general.optimizations]
fastUnfocusedRecipeVisibility = true
fastRecipeSuppliers = true
fastJoinSkipTooltipSearch = false
```

The two new switches default to true inside the existing disabled-by-default experimental gate.
They are cached for each JEI runtime and reset at start/stop. They activate only on JEI 19.57.0.449;
other versions use the original algorithms. This version guard applies to the new paths, not to
every older JETOptimizer experiment. Optional injection misses can still fall back, so verify the
activation counters instead of assuming a non-crashing launch proves that a hook applied.

## Validation

`bash gradlew build` runs the JUnit suite and produces the mod. CI builds/tests on Java 21 and
uploads the candidate jar and test report. Differential tests compare the actual pinned JEI
visibility and snapshot implementations across 100,000 and 10,000 deterministic randomized cases.
Targeted tests cover blanks, empty/mismatched linked slots, immutable ownership, recipe ordering,
and early exit. In a two-slot 4,096-candidate fixture, the optimized existence query makes two
predicate calls versus 8,192 for JEI's complete enumeration. This is an operation-count result,
not a wall-clock modpack benchmark.

Local algorithm validation can also run with `python tools/headless-recipe-tests.py --java-home
/path/to/jdk21 --deps-dir /path/to/deps`. This isolates game linkage: it compiles the pinned source's
actual `DisplayIngredientAcceptor.getMatches` methods into a small class with an unused no-op
constructor, and runs the original JEI `FocusLink` and supplier-builder bytecode. The constructor
fixture supplies its own ingredients. This checks algorithm equivalence, not Minecraft integration,
Mixin transformation, or whole-mod compilation. The normal Gradle/CI run uses full dependencies.

The implementation environment passed the five isolated tests. Full Gradle validation was blocked
by plugin dependency resolution in this environment, after working around its missing filesystem
mount metadata locally. A built jar and in-game verification remain required before release.

## ATM10A A/B procedure

1. Use the same Java version, heap, JEI jar, resource pack, server recipe payload and mod list.
   Keep every other optimization fixed. Run baseline with both new switches false, visibility only,
   suppliers only, then both true. Alternate baseline/candidate launches to control warm-up drift.
2. Collect at least five fresh-process cold joins per arm and compare medians and ranges. Evaluate
   same-process reconnects separately. `tools/atm10a-jei-test.py --reconnects 1` can collect the existing
   profile for the configured Prism instance; it does not enable these options automatically.
3. In candidate profiles require nonzero `fast recipe suppliers`,
   `empty recipe-role indexes avoided`, and `fast unfocused visibility checks (startup-wide)`.
   Confirm the count of suppliers is consistent with layout calls and recipe totals. If a counter
   stays zero, investigate the gate/Mixin before attributing any speedup to this patch.
4. Compare `Total JEI start`, recipe/category registration, each recipe-map role, recipes-GUI gate,
   GUI runtime and tooltip source strings. Do not add nested child timers to their parents. Also
   measure menu-to-world separately, as it includes work outside JEI.
5. Verify category lists, ingredient visibility, crafting/smelting/fuel uses, linked slot rotations,
   recipe transfer, bookmarks and tooltip-word search. Exercise KubeJS hide/remove/add behavior,
   reconnect, server switch, local worlds, `/reload` and resource reload. Check Mixin errors.
6. Require unchanged recipe/ingredient counts and expected GUI behavior before accepting a time
   reduction. Repeat representative timings with profiling disabled before release.

If preserving full tooltip search still leaves startup above 20 seconds, the remaining 5–7 seconds
of live tooltip rendering needs a separate solution. The existing `fastJoinSkipTooltipSearch` option
can test the upper speed bound, but disables tooltip-word searching and is not a behavior-preserving
15-second result. No background execution of mod tooltip callbacks or reconnect cache is added here.

The subsequent [prepared recipe cache](PREPARED_RECIPE_CACHE.md) adds guarded persistence, shared ingredient blocks and bounded detached-data workers. It preserves the original synchronous JEI callback/batch order.
