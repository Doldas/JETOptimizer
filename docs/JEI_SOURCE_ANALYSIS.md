# JEI 19.57.0.449 source analysis for reuse and optimization

This document records what the actual JEI source says about the three regions that dominate ATM10A
startup, so that an optimization decision rests on ownership and lifetime facts instead of on
timings. Every claim below is checked against the `-sources.jar` artifacts for
`jei-1.21.1-{common,gui,lib,common-api}-19.57.0.449`, which are the same sources the
`@Pseudo` mixins target.

Reference measurements are from the instrumented run `E1` (`Starting JEI took 31.24 s`), reproduced in
`docs/JEI_PERFORMANCE_ANALYSIS.md`. Every recorded launch was a new process with one join, so these
numbers describe a cold first connection, not a reconnect.

## Summary of the three candidate regions

| Region | Time | Where it comes from | Reusable across a reconnect? |
|---|---:|---|---|
| Tooltip search strings | 7.265 s | 68,186 calls to JEI's own tooltip getter, one per registered ingredient | **No.** Inputs are bound to `ClientLevel` and the local `Player`. |
| Recipe supplier extraction | 4.079 s | 211,643 `setRecipe` calls, 1:1 with the 211,632 recipes submitted | **No gain available.** There is no redundancy to remove. |
| Recipe map indexing | 2.598 s | 4 roles x 211,281 `RecipeMap.addRecipe` calls | **No gain available.** Per-role ingredient objects are distinct, so UIDs cannot be shared. |

The remainder is 4.826 s of `registerRecipes` plugin work outside `addRecipes` (dominated by
`jei:minecraft:` at 4.497 s) and 4.001 s of ingredient registration.

## 1. Tooltip search strings: 7.265 s, connection-bound

Call chain, per the sources:

```text
ElementSearch.<init>                              (mezz.jei.gui.search.ElementSearch)
  for each PrefixInfo, for each IListElementInfo
    PrefixInfo.getStrings(info)                   (mezz.jei.common.search.PrefixInfo)
      -> IListElementInfo::getTooltipStrings     (mezz.jei.gui.ingredients.ListElementInfo)
           SafeIngredientUtil.getPlainTooltipForSearch(ingredientManager, renderer, value, flag)
             ingredientRenderer.getTooltip(ingredient,
                 Item.TooltipContext.of(minecraft.level),
                 minecraft.player,
                 tooltipFlag)
```

The decisive detail is inside `mezz.jei.common.util.SafeIngredientUtil.getPlainTooltipForSearch`:

```java
Minecraft minecraft = Minecraft.getInstance();
Item.TooltipContext tooltipContext;
Player player;
if (minecraft == null) {
    tooltipContext = Item.TooltipContext.EMPTY;
    player = null;
} else {
    tooltipContext = Item.TooltipContext.of(minecraft.level);
    player = minecraft.player;
}
return ingredientRenderer.getTooltip(ingredient, tooltipContext, player, tooltipFlag);
```

Every mod's tooltip generator therefore receives the live `ClientLevel`'s registry access and the
local `Player`. Both are replaced on every join. Consequences:

* A tooltip string cached from one connection is not a function of the ingredient alone, so it
  cannot be validated by an ingredient-level fingerprint. Any such cache would silently serve stale
  text for any mod that consults the world or the player.
* `getTooltipStrings` also applies `services.PLATFORM.getInputHelper().getSearchTooltipFlag(...)` and
  `config.searchAdvancedTooltips()`, so the flag itself is part of the key.

There is also no redundancy inside a single connection. `PrefixInfoMixin` counted exactly 68,186
getter calls for 68,186 base-list entries and a single baked index build with 524,020 key entries.
Splitting the 8.178 s search-index stage by the existing hooks:

```text
source strings: tooltips               7.265 s   <- 89% of the stage
source strings: mod_names              0.334 s
source strings: tags                   0.141 s
source strings: unprefixed             0.004 s
source strings: colors/creative_tabs/identifiers  0.000 s  (DISABLED by default)
Baked substring gram-index builds      0.224 s
Other search-index work                0.211 s  (604,680 put() calls: ~0.35 us each)
```

So the stage is not index construction; it is 68,186 real tooltip generations at about 107
microseconds each, which is roughly 300x the cost of inserting one string into the index. `colors`, `creative_tabs` and `identifiers` report zero calls because
`IIngredientFilterConfig` leaves those prefixes `SearchMode.DISABLED`, so `ElementSearch` skips them
entirely.

The connection-independent part of the whole search stage is `mod_names` + `tags` + `unprefixed`,
about 0.48 s, roughly 1.5% of startup. Even a perfect reuse cache for all three would be noise.

`colors`, `creative_tabs` and `tags` are registry-dependent: `getTagStrings` reads
`ingredientHelper.getTagStream(...)` and `getCreativeTabsStrings` iterates `CreativeModeTabs.allTabs()`
and calls `contains(itemStack)`. Those are the kind of data a reuse layer would most like to keep,
and they are also exactly the data a new server can invalidate.

## 2. Recipe supplier extraction: 4.079 s, no redundancy

`mezz.jei.library.util.IngredientSupplierHelper.getIngredientSupplier` builds an
`IngredientSupplierBuilder`, calls `recipeCategory.setRecipe(builder, recipe, FocusGroup.EMPTY)` and
returns the built supplier. It is a pure function of `(recipe, recipeCategory, ingredientManager)`
because the focus is always `FocusGroup.EMPTY`.

`RecipeManagerInternal.getFocusLinks` is the one call site that can invoke it outside registration,
as a fallback when `RecipeTypeData.getFocusLinks` returns `null`:

```java
private <T> List<FocusLink> getFocusLinks(RecipeTypeData<T> recipeTypeData, T recipe) {
    List<FocusLink> focusLinks = recipeTypeData.getFocusLinks(recipe);
    if (focusLinks != null) {
        return focusLinks;
    }
    RecipeIngredientSupplier ingredientSupplier = IngredientSupplierHelper.getIngredientSupplier(...);
    return ingredientSupplier.getFocusLinks();
}
```

That looked like a promising memo target, so it was checked against the measurements rather than
assumed. The recorded run shows:

```text
Recipe addRecipes batches/recipes: 2521/211632
  IngredientSupplierHelper category setRecipe: 4.079 s
    calls: 211643
```

211,643 supplier calls against 211,632 submitted recipes is a difference of 11. The fallback path
contributed at most 11 calls in the whole startup, so memoizing it cannot recover a meaningful
amount of time. Every registration call is one distinct recipe; there is nothing to reuse.

Note also that `Client recipes: 75632` is the vanilla `RecipeManager` size, while JEI registered
211,632 recipes. Plugin-provided recipes are roughly two thirds of the workload, so any reasoning
that scales from the vanilla recipe count understates the work.

## 3. Recipe map indexing: 2.598 s, per-role objects are distinct

`RecipeManagerInternal.addRecipe` loops over all four `RecipeIngredientRole` values and calls
`recipeMap.addRecipe(recipeType, recipe, ingredientSupplier)` once per role. Each of those calls
iterates `ingredientSupplier.getIngredients(role)` and computes
`ingredientHelper.getUid(typedIngredient, UidContext.Recipe)` for every ingredient.

Measured per-role insert times: INPUT 1.245 s, RENDER_ONLY 1.121 s, OUTPUT 0.232 s, CATALYST
0.018 s, each 211,281 calls. INPUT and RENDER_ONLY being nearly equal means RENDER_ONLY usually
carries about as many ingredients as INPUT. `AbstractCookingCategory` is a concrete source of that:
it declares `RecipeIngredientRole fuelRole = RecipeIngredientRole.RENDER_ONLY` and, when the recipe
does not specify a fuel, registers the entire `furnaceFuels` list into that one slot, so for those
categories the fuel ingredients are re-indexed for every recipe.

The obvious optimization is to compute each UID once and reuse it across roles. The source blocks
it:

* `IngredientSupplierBuilder.addSlot` creates one `IngredientSlotBuilder` per slot.
* `IngredientSlotBuilder` owns a private `DisplayIngredientAcceptor`.
* `DisplayIngredientAcceptor.addIngredient` / `addIngredients` / `addItemStacks` each call
  `TypedIngredient.createAndFilterInvalid(...)`, which creates a new `ITypedIngredient` every time.
  There is no interning and no cache.

So an ingredient that appears in both an INPUT slot and a RENDER_ONLY slot is represented by two
distinct `ITypedIngredient` instances, and an identity-keyed memo never hits. A value-keyed memo
would have to hash the underlying `ItemStack`, which is the same order of work that
`StackHelper.getUidForStack` already does: it looks up the subtype interpreter and normally returns
the bare `Item`, or `List.of(item, subtypeData)` when a subtype exists. Keying by the ingredient
first would replace a two-map-lookup UID with a component-walking hash, which is not a win.

For the same reason, the 1.121 s of RENDER_ONLY indexing is not removable without changing JEI's
index contents.

## 4. The one measurable waste, and why it is not reachable

JEI builds its search index over every registered ingredient and then removes some of them:

```text
Ingredient manager at GUI list build (raw/typed): 68186/68186
IngredientFilter base-list entries: 68186
Ingredient manager after onRuntimeAvailable (raw/typed): 51744/51744
Ingredient manager raw delta during JEI start: -16442
Runtime ingredient add requests/calls: 1/1
Runtime ingredient remove requests/calls: 17729/34
```

24% of the ingredients that were tooltip-indexed are gone before the player ever opens JEI, so
roughly 1.75 s of the tooltip phase is spent on ingredients that are never shown. This is the only
proportion of the startup that is provably wasted.

The log ordering shows why it cannot be avoided from outside JEI:

```text
22:08:31.795  Adding 68186 ingredients      <- search index built over all of them
22:08:31.934  Added 68186 ingredients
22:08:35.088  Ingredients are being removed at runtime: 10 ...
22:08:35.141  Ingredients are being removed at runtime: 14239 ...
22:08:37.907  Sending Runtime took 2.819 seconds
```

The removals come from plugins calling `IIngredientManager.removeIngredientsAtRuntime` during
`onRuntimeAvailable`, about three seconds after the index is built. They are plugin decisions about
which ingredients are valid on that server, so they cannot be predicted at index time. Avoiding the
waste would mean reordering JEI's own startup so the index is built after runtime sync, which is a
behavior change and would delay the first paint of the ingredient list.

The related memory point is that `IngredientFilter.onIngredientsRemoved` is documented in JEI as
"ignore this, it's handled by onIngredientVisibilityChanged", so the search storage keeps entries for
removed ingredients for the lifetime of the runtime. Pruning them would save memory, not startup
time, and would have to preserve `ElementSearch.findElement` behaviour for visibility updates.

## What the measured generations added to this

The three-generation run in
`~/jetoptimizer-runs/atm10a-reconnect-20261005-230658.log` (cold remote join, in-game reconnect to
the same remote server, join to a local world) tested the source conclusions against a real reconnect
and confirmed all of them. Details are in `docs/JEI_PERFORMANCE_ANALYSIS.md`.

The `getFocusLinks` fallback predicted at most 11 calls; measured, it contributes exactly 11 in every
generation. `RecipeMap.addRecipe` calls equal recipes minus exactly 351 in every generation, the
recipes under hidden or unknown categories, so there is no repeat to memoise in either region.

The reuse key is the decisive result. Generation 2 held every mod-side field identical to generation 1
— 163 plugin UIDs with the same order-independent set hash, 524 categories, 75,632 client recipes,
2,521 `addRecipes` batches, 7 baked-index builds — and still differed in 20 of 40 fields, because the
server sent 264 more recipes and 2,037 more ingredients. A cache keyed on the stable half would have
hit while the payload had moved. This is the concrete failure mode that the `ClientLevel` and `Player`
argument-bound dependency predicted from the source.

The by-type tooltip attribution also closes the "one bad mod" hypothesis: `item_stack` is 98.8-99.5%
of the tooltip stage in both profiled generations, at roughly 83-100 microseconds per item, with every
other ingredient type together under 0.01 s. The cost is ordinary `appendHoverText` work spread over
~67,000 items, not a pathological type that could be excluded.

## Conclusion

Within a single connection the dominant regions are all first-occurrence work whose inputs are
connection-bound, and the source shows no safe redundancy to remove:

* No duplicate computation exists in the tooltip, supplier or recipe-map regions.
* The dominant tooltip region depends on `ClientLevel` and `Player`, so cross-connection reuse
  cannot be validated from an ingredient fingerprint.
* The only provable waste, 24% of ingredients being indexed then removed, happens after the index is
  built and is driven by plugin calls.

Consequently no optimization is implemented, and the measured reconnect is what makes that a
conclusion rather than a shrug: the reuse hypothesis is disproved for the same-server case, and the
remaining waste would require reordering JEI's startup.

What is needed next is still measurement, not a change:

1. Hook `Sending Runtime`, the largest unattributed region at 2.750-10.430 s and the entire cause of
   the local-world run's apparent 37 s.
2. Add per-source attribution to `Ingredient registration`, which swung 4.488 s → 2.638 s → 3.737 s
   with no source detail.
3. Re-run the same-server reconnect after those hooks land. Both remaining regions are large enough
   to be worth measuring before any change is contemplated.
