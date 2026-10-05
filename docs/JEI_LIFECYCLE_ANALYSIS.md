# JEI 19.57.0.449 lifecycle analysis

**Target:** Minecraft 1.21.1, NeoForge 21.1.x, JEI 19.57.0.449
**Source reviewed:** JEI Maven source artifacts for `jei-1.21.1-neoforge`, `jei-1.21.1-lib`, and `jei-1.21.1-gui`, version `19.57.0.449`.
**Purpose:** establish the actual startup/shutdown boundaries and safe profiler hooks before proposing reconnect caching.

This is source analysis, not a runtime trace from ATM10 Aeronautics. Timings and ingredient counts quoted elsewhere in the project remain observations from that test pack; they are not measurements made by this report.

## Findings at a glance

1. JEI does not start merely because the player has connected. In 1.21.1 its `StartEventObserver` waits for both a client `LoggingIn` event and `RecipesUpdatedEvent`, handling either order and associating the events with the active network `Connection`.
2. `RecipesUpdatedEvent` is fired after the client `RecipeManager` has received/synchronized server recipes. JEI's event handler snapshots the updated recipes, and the observer then starts JEI. The expensive JEI work therefore runs synchronously in the client event handling path after recipe synchronization; it is separate from the time spent transporting/decoding the server recipe packet.
3. Disconnect causes JEI to stop. The observer clears the client recipe snapshot and transitions to its listening state. `JeiStarter.stop()` calls plugin `onRuntimeUnavailable`, unregisters/clears runtime state, runs stop callbacks for the ingredient/helper/recipe managers, and clears the active `RegistryAccess` reference.
4. Reconnect starts a new JEI runtime through the same persistent `JeiStarter` and plugin instances. Core ingredient, recipe, search, and GUI runtime objects are reconstructed. The plugin objects, configuration manager, and starter survive; JEI gives plugins explicit unavailable/available callbacks around each runtime.
5. JEI already reports aggregate timings for named plugin callback phases and the overall `Starting JEI` operation. It also reports an individual plugin callback when it takes more than 10 ms. The source includes timers for `Building runtime`, `Building recipe registry`, `Building ingredient list`, and `Building ingredient filter`.
6. The existing `Building ingredient filter` timer groups sorting, prefix parser/search construction, and initial visibility checks. It does **not** give an independent search-index duration. `IngredientFilter.getElements()` performs filtered list creation lazily, so its cost is not guaranteed to be inside that constructor timer.
7. No safe reconnect cache boundary is established by this source review. The runtime structures directly depend on the current level's `RegistryAccess`, live JEI managers, plugin callbacks, config/listeners, and server-connection state. Keeping `IJeiRuntime` or those structures alive across disconnect would violate the observed shutdown ownership.

## Initialization call graph

```text
                                                ├─ StartEventObserver observes both events
Minecraft recipe sync -> RecipesUpdatedEvent ───┘  (either order; current Connection is checked)
  ├─ JustEnoughItemsClient.onRecipesUpdatedEvent
  │    └─ snapshot RecipeManager.getRecipes() into Internal client-synced recipe state
  └─ StartEventObserver.onRecipesUpdatedEvent
       └─ startIfReady -> transition to JEI_STARTED -> JeiStarter.start
            ├─ require Minecraft.level; get its RegistryAccess; set RegistryUtil access
            ├─ possibly install vanilla fallback recipes if no client recipes are recorded
            ├─ configureJei callbacks
            ├─ register item/fluid subtypes
            ├─ register ingredients, extra ingredients, ingredient aliases
            ├─ create IngredientManager and helper/visibility state
            ├─ register mod aliases
            ├─ build RecipeManager
            │    ├─ register categories, vanilla extensions and catalysts
            │    ├─ build recipe registry
            │    ├─ advanced plugin callbacks and plugin recipes
            │    ├─ compact recipe maps
            │    └─ register recipe transfer handlers
            ├─ Building runtime
            │    ├─ register GUI handlers
            │    ├─ registerRuntime callbacks
            │    │    └─ JeiGuiStarter.start (JEI GUI plugin)
            │    │         ├─ build ingredient list
            │    │         ├─ build ingredient filter/search structures
            │    │         └─ create overlays, bookmarks, recipe GUI and input handlers
            │    └─ construct JeiRuntime
            ├─ onRuntimeAvailable callbacks (includes KubeJS runtime work)
            ├─ publish active runtime through Internal.setRuntime
            └─ verify recipe synchronization and stop total timer
```

The observer has a fallback path: if a container screen opens while JEI has not started, it logs missing start events and starts JEI late. This is correctness recovery, not the normal join path.

### What starts “Starting JEI”

`JeiStarter.start()` starts its `LoggedTimer` after it checks `Minecraft.level`, sets `RegistryUtil` to the current level's access, and handles a possible vanilla fallback recipe load. It starts just before the `configureJei` callbacks. Its total includes registration/build work, GUI runtime registration, all `onRuntimeAvailable` callbacks, and runtime publication. The post-timer recipe-sync warning verification is outside that total.

### Recipe synchronization boundary

NeoForge documents `RecipesUpdatedEvent` as firing on the logical client main event bus after the client `RecipeManager` receives and synchronizes recipes. JEI's source comment in `StartEventObserver` explicitly says that in Minecraft 1.21.1 it waits for this event so it does not briefly start against fallback client recipes. `JustEnoughItemsClient.onRecipesUpdatedEvent` copies `event.getRecipeManager().getRecipes()` and calls `Internal.setClientSyncedRecipes` before/alongside the observer's low-priority listener. JEI initialization is downstream of this event, not the server's login/authentication operation.

JETOptimizer marks entry to `ClientPacketListener.handleUpdateRecipes`, not network receipt or packet deserialization. Its handler-to-event interval covers client main-thread recipe processing before the captured `RecipesUpdatedEvent` listener, while the event-to-JEI interval covers the remaining listener dispatch before JEI's startup timer. These measurements do not include network transport, packet decode, or time queued before the client handler. JEI's own `Starting JEI` timer alone cannot attribute those preceding costs.

## Shutdown and reconnect call graph

```text
ClientPlayerNetworkEvent.LoggingOut with player
  └─ StartEventObserver listener
       ├─ Internal.clearClientRecipes()
       └─ transitionState(LISTENING)
            └─ jeiStarter.stop()
                 ├─ running = false
                 ├─ PluginCaller.callOnPlugins("Sending Runtime Unavailable", ...)
                 ├─ clear pending recipe transfers
                 ├─ Internal.onRuntimeStopped()
                 │    ├─ close JEI recipe screen if open
                 │    ├─ remove runtime config/listener registrations
                 │    ├─ clear toggle listeners and stop server connection runtime state
                 │    └─ set Internal.jeiRuntime = null
                 ├─ run and clear IngredientManager / JeiHelpers / RecipeManager stop callbacks
                 └─ RegistryUtil.setRegistryAccess(null)

Next connection:
LoggingIn + RecipesUpdatedEvent -> StartEventObserver -> same JeiStarter.start()
```

`JeiStarter` and its plugin list were constructed once in `JustEnoughItemsClient` and are retained. The `running` guard is reset on stop. The same plugin objects are reused, with `onRuntimeUnavailable` and then `onRuntimeAvailable` called for each runtime lifecycle. JEI cannot assume third-party plugin instances are stateless or deterministic across this boundary.

## State ownership: destroyed, retained, and rebuilt

| State/object | Disconnect/reconnect behavior from source | Cache implication |
|---|---|---|
| `JeiStarter`, `StartData`, plugin list | Retained by `JustEnoughItemsClient`; one starter is created at client setup | Plugin instance survival is not proof that plugin-derived data is reusable. Plugins receive lifecycle callbacks and can hold arbitrary state. |
| JEI synced recipe snapshot (`Internal.ClientRecipes`) | Set on `RecipesUpdatedEvent`; cleared on logging out. It also records a connection address and whether it is synced or fallback data. | It is a recipe-sync/startup state marker, not a fingerprint and not a reusable JEI index. Address equality is not state validity. |
| `RegistryUtil` current access | Set from `Minecraft.level.registryAccess()` at start; set to `null` on stop | Must not retain old level registry access. |
| `IngredientManager`, registered ingredient index, helper/visibility listeners | Built during start; stop callback clears manager listeners | Structures refer to live ingredient types/helpers, can be changed at runtime, and are listener-owned. Not safe to retain as-is. |
| `RecipeManager` / `RecipeManagerInternal`, recipe maps and category data | Built during start; stop callback removes its category-sort listener | Recipe categories and recipes are supplied by plugins and linked to the current ingredient manager/visibility/registries. Not safe to carry across disconnect. |
| Search/filter structures | `JeiGuiStarter` constructs a new `IngredientFilter`; config changes mark its search/sort data dirty; runtime ingredient and visibility events mutate it | It depends on current ingredients, aliases, sorting/search config, visibility, and helper data. A serialized/pure derived cache might be investigable, but only after proving invalidation inputs and plugin effects. |
| GUI overlays, bookmark/history lists, `RecipesGui`, input/event handlers | Created by `JeiGuiStarter.start()` under `registerRuntime`; runtime subscriptions/listeners are removed by runtime shutdown | These contain screens, client/UI state, registries, managers and event handlers; never retain them across disconnect. |
| Resource-reload GUI handler | Registered separately; invalidates/reloads JEI GUI colors and calls `ResourceReloadHandler` for the active filter/ingredient overlay | It is GUI-resource handling, not evidence that all JEI runtime data is reusable or rebuilt on every resource reload. |
| Client configs, static JEI feature state, internal key mappings/textures | Held outside the per-runtime manager graph; some are initialized once and others are runtime-listener-cleaned | Persistent configuration is distinct from reusable server-derived runtime state. |

### Data dependencies found

* **Client level / RegistryAccess:** `JeiStarter.start()` requires `Minecraft.level`, takes its registry access, and hands it to edit-mode serialization. `JeiGuiStarter` also uses the level registry access for bookmarks and history.
* **Recipes/server connection:** synced recipe state is captured from the client recipe manager. Recipe transfer registration, cheat handlers, and runtime GUI setup receive a connection-to-server object.
* **JEI plugins:** callbacks can register subtypes, ingredients, aliases, categories, recipes, transfer handlers, search builders, runtime objects, and arbitrary code in `onRuntimeAvailable`.
* **Client config and resource state:** search/filter construction uses client search settings and sort configuration. Resource reload handlers update GUI/filter/overlay state.
* **Runtime mutation:** ingredient APIs support adding/removing ingredients; ingredient visibility, recipe visibility and sorting changes invalidate or update indexes.

## Existing JEI measurements and profiler targets

JEI source already calls `LoggedTimer` around several actual internal boundaries and `PluginCaller` around plugin phases. Its log entries should be captured in the initial benchmark before adding any invasive hooks.

| Desired measurement | Source boundary / existing measurement | Limitation / next instrumentation need |
|---|---|---|
| Full JEI start | `JeiStarter.start()` `LoggedTimer` titled `Starting JEI` | Begins after initial level/registry/fallback setup; excludes recipe packet handling and post-start verification. |
| Plugin callback phases | `PluginCaller.callOnPlugins(title, plugins, func)` logs phase duration; `PluginCallerTimer` logs each individual callback only after >10 ms. JETOptimizer additionally aggregates callback wall time by UID and callback phase when plugin profiling is enabled. | Callback totals overlap the containing JEI method stages. They are diagnostic subdivisions, not additive stage contributions. |
| Ingredient registration/enumeration | `PluginLoader.registerIngredients()` wraps registration, extra ingredients, and aliases in named `PluginCaller` phases; `IngredientManagerBuilder.registerInternal()` loops registered ingredient values and creates/validates typed ingredients | Distinguish time in plugin-supplied collection production from JEI's per-item typed ingredient conversion/registration. Prefer callback timings first; add internal timing only if needed. |
| Recipe categories / recipe registration | `PluginLoader.createRecipeManager()` wraps category/catalyst/advanced/recipe callbacks. JETOptimizer records each named `PluginCaller` phase, `RecipeManagerInternal` construction, `addPlugins`, `compact`, recipe batches through `RecipeRegistration.addRecipes`, `IngredientSupplierHelper.getIngredientSupplier`, and `RecipeMap.addRecipe` by role. | Add-recipes, supplier and map-role timers are nested in the `Registering recipes` callback phase and in `createRecipeManager`. See `docs/JEI_PERFORMANCE_ANALYSIS.md` for the parent/child arithmetic. |
| Ingredient list | `JeiGuiStarter.start()` timer `Building ingredient list`; `IngredientListElementFactory.createBaseList(...)` | Source-backed list construction boundary. Ingredient count can be obtained from manager ingredient collections without enumerating/logging each ingredient. |
| Filter/search-index construction | JEI's timer `Building ingredient filter` wraps sorting, filter construction, `createElementSearch`, and visibility updates. JETOptimizer measures `IngredientSorter.sortIngredients`, the full `IngredientFilter` constructor, `createElementSearch`, per-prefix string-source generation, and default baked substring index building/key counts. | Per-prefix generation and baking are nested within `createElementSearch`; the whole search stage is nested in the filter and GUI stages. The lazy `getElements()` query path remains separate if traces show it runs during startup. |
| GUI/runtime construction | `JeiStarter.start()` `Building runtime` timer covers screen-helper callbacks, `registerRuntime` and the JEI GUI plugin's `JeiGuiStarter.start()` | JETOptimizer separately times `JeiGuiStarter.start()` and its list/filter/index construction children. These child timings overlap the GUI-runtime parent. |
| KubeJS / other `onRuntimeAvailable` work | `PluginCaller.callOnPlugins("Sending Runtime", ...)` has phase and slow-callback timings | This matches the known ~5s KubeJS callback location; collect raw JEI timing logs rather than repeating disproven removal-rule A/B tests. |
| Minecraft recipe sync | NeoForge `RecipesUpdatedEvent`; JEI's handler snapshots recipe list, observer starts after required events | Outside `Starting JEI`; JETOptimizer marks `ClientPacketListener.handleUpdateRecipes` entry and separates handler-to-event from event-to-JEI time. It does not measure transport/decode/queue time. |

`PluginCallerTimer` itself creates a scheduled executor and checks the active callback every 100 ms. This is existing JEI profiler overhead and is a reason to compare repeated baselines and avoid installing per-ingredient probes. JETOptimizer's new per-prefix search instrumentation samples once per prefix getter call and reads the returned collection size; it does not enumerate/log each ingredient or string.

## Disconnect/reload behavior and lifecycle details

* `StartEventObserver` tracks the current `Connection` through a `WeakReference`. On a connection identity change, previously observed login/recipe events are cleared. It ignores very early events when it cannot yet find the current/pending connection.
* On logout, it clears `Internal`'s synced recipe snapshot and stops JEI when a player is present. On game shutdown, `JustEnoughItemsClient` also calls `JeiStarter.stop()` and then `Internal.onClientStopping()`.
* A later `RecipesUpdatedEvent` after JEI has started causes a stop/start cycle when `Internal.hasClientSyncedRecipes()` is true. This is JEI's recipe-update restart path and is distinct from the initial join gate.
* JEI config options `showTagRecipesEnabled` and `showHiddenIngredients` install runtime listeners which call `Internal.restartJei()` after config changes.
* Resource reload wiring deserves runtime verification. `StartEventObserver` implements `ResourceManagerReloadListener` and its `onResourceManagerReload()` calls `restart()`. However, in the reviewed 19.57.0.449 NeoForge source, `JustEnoughItemsClient` registers a separate reload-listener lambda which refreshes GUI colors and the active `NeoForgeGuiPlugin` resource handler; the constructor's visible observer registration is only through `PermanentEventSubscriptions` (event-bus listeners). No call registering that observer instance with the resource manager is visible in these source paths. Do not assume every client resource reload restarts the complete JEI runtime without confirming the runtime behavior or another integration path.
* A datapack reload that synchronizes recipes and emits `RecipesUpdatedEvent` can take the recipe-update restart path above. That does not make it safe to carry runtime state across reload.

## Candidate and dangerous cache boundaries

| Boundary | Potential value | Risk | Current recommendation |
|---|---|---|---|
| Keep `IJeiRuntime` or live managers across disconnect | Could avoid all runtime construction | **Critical:** JEI explicitly clears runtime, registry access, subscriptions/listeners and connection-dependent state; plugin objects and APIs may refer to the old level/connection. | Do not do this. |
| Keep `IngredientFilter` / `ElementSearch` | Potentially avoids sorting/index construction | **High:** references ingredient manager/helpers, visibility/config, and contains runtime listeners and mutable state; ingredients/aliases/plugins/config can change. | No cache until dependency fingerprint and invalidation are demonstrated. |
| Reuse normalized ingredient/list entries | Could reduce per-ingredient conversion and list building | **High:** typed values are backed by mod-provided objects and ingredient helpers; runtime ingredient additions, resource/registry reloads, subtype interpreters and plugin registrations can change values. | First measure conversion and memory; explore only an immutable, versioned representation later. |
| Reuse recipe maps/categories | Could reduce recipe processing | **Critical:** plugin recipes/categories and indexes connect to current ingredient manager, visibility and current synced recipe/plugin state. Plugins are not guaranteed pure. | Do not reuse without a complete plugin-level validity contract. |
| Aggregate profiler timings (no cached game state) | Better attribution with low memory/correctness risk | **Low to medium:** hooks need to target JEI internals and may break across JEI updates; recording callback durations has small overhead. | Phase 1 target. Use exact JEI version gate, opt-in config and fail-open behavior. |
| Fingerprint recipes or server address | May detect some server changes | **High if treated as proof:** JEI's current connection string is not a content fingerprint; equal address does not validate recipes, registries, datapacks, plugin state or client config. | Not sufficient for cache validity. |

## Phase 1 instrumentation recommendation

The current profiler implements the source-confirmed event/timer boundaries and narrow recipe/search sub-stages listed above. The next ATM profile should evaluate whether those non-overlapping parent/child intervals explain at least 90% of startup. Any further hook should be justified by the remaining residual and should avoid per-recipe/per-ingredient instrumentation.

### Development profiler smoke test

On 2026-10-05, `runClient` was exercised with Minecraft 1.21.1, NeoForge 21.1.255, JEI 19.57.0.449, a vanilla integrated world, and both profiling options enabled. All configured JEI profiler hooks were observed, including the nested search-index construction hook. One smoke-test run reported:

* JEI `Starting JEI`: 0.710 s; recipe-handler entry to `RecipesUpdatedEvent`: 0.013 s; event to JEI timer start: 0.001 s.
* 1,290 client recipes, 1,691 ingredients, 16 recipe categories, and 4 plugin UIDs.
* Ingredient search-index construction: 0.087 s; full ingredient-filter constructor: 0.109 s; JEI GUI runtime construction: 0.215 s.
* Aggregated plugin callbacks: `jei:minecraft` 0.326 s, `jei:neoforge_gui` 0.224 s, `jei:internal` 0.053 s, and `jei:gui` 0.003 s.

This is a hook/format smoke test in a tiny vanilla development environment, not a representative benchmark and not evidence of a performance improvement. The recipe-handler timer begins after packet decode and main-thread scheduling, so it does not measure network/queue time.

These are profiling hooks, not optimization proposals. No source-backed lifecycle finding currently justifies reconnect caching.

## Benchmark interpretation

For baseline A (first join), unchanged reconnect B, server restart C, changed recipes/datapack D/E, server switch F, KubeJS change G, `/reload` H, resource reload I, and singleplayer J, record:

* timestamp/duration from `ClientPacketListener.handleUpdateRecipes` entry through `RecipesUpdatedEvent` (not network receive/decoding/queue time);
* JEI `Starting JEI`, plus `Building runtime`, `Building recipe registry`, `Building ingredient list`, and `Building ingredient filter`;
* `PluginCaller` callback phase and per-plugin lines (especially `Sending Runtime` / KubeJS);
* registered ingredient count, client recipe count, category count where available;
* connection identity only as diagnostic context, never as proof of cache validity;
* JETOptimizer profiler enabled state and hook compatibility status.

Compare repeated runs and use medians/spread; sub-second deltas alone are not evidence of improvement. Keep recipe synchronization and JEI construction as distinct totals.

## Upstream classification of current observations

* JEI's per-plugin callbacks and current stage timers are useful; no architectural performance defect is proven by this source inspection.
* The known Create Thrusters global recipe scan is an individual plugin issue and should be fixed upstream/in that plugin, as already identified in the project brief.
* KubeJS's known `onRuntimeAvailable` cost belongs first in the per-callback profile; the tested ATM remove rules are not supported as the remaining cause by the existing A/B results.
* Potential search-index/recipe-manager reuse is currently an unverified architectural optimization question, not a demonstrated bug or an optimization JETOptimizer should implement yet.

## Source references

Exact source artifact listings:

* [JEI NeoForge 19.57.0.449 source artifact](https://maven.blamejared.com/mezz/jei/jei-1.21.1-neoforge/19.57.0.449/)
* [JEI library 19.57.0.449 source artifact](https://maven.blamejared.com/mezz/jei/jei-1.21.1-lib/19.57.0.449/)
* [JEI GUI 19.57.0.449 source artifact](https://maven.blamejared.com/mezz/jei/jei-1.21.1-gui/19.57.0.449/)
* [JEI upstream repository](https://github.com/mezz/JustEnoughItems)
* [NeoForge `RecipesUpdatedEvent` documentation for 1.21.1](https://lexxie.dev/neoforge/1.21.1/net/neoforged/neoforge/client/event/RecipesUpdatedEvent.html)

Important classes and methods reviewed:

* `mezz.jei.neoforge.JustEnoughItemsClient`: constructor, `register`, `onRecipesUpdatedEvent`, `onGameShuttingDown`, `onRegisterReloadListenerEvent`, `createReloadListener`.
* `mezz.jei.neoforge.startup.StartEventObserver`: `register`, `onLoggingIn`, `onRecipesUpdatedEvent`, `startIfReady`, `observeConnectionEvent`, `onResourceManagerReload`, `restart`, `transitionState`.
* `mezz.jei.library.startup.JeiStarter`: constructor, `start`, `verifyClientRecipes`, `stop`.
* `mezz.jei.library.load.PluginLoader`: `registerSubtypes`, `registerIngredients`, `createRecipeManager`, `createGuiScreenHelper`, `createSearchStorageFactory`.
* `mezz.jei.library.load.PluginCaller` and `PluginCallerTimer`: named phase timing and per-plugin callback timing.
* `mezz.jei.library.load.registration.IngredientManagerBuilder`: `registerInternal`, `addExtraIngredients`, `build`.
* `mezz.jei.library.ingredients.IngredientManager`, `RegisteredIngredients`, `IngredientInfo`: registered values/index and runtime mutation/listener ownership.
* `mezz.jei.library.recipes.RecipeManagerInternal`: recipe/category maps, recipe insertion and `onRuntimeStopped`.
* `mezz.jei.gui.startup.JeiGuiStarter`: `start`, including `Building ingredient list` and `Building ingredient filter` timers.
* `mezz.jei.gui.ingredients.IngredientFilter`: constructor, `createElementSearch`, `rebuildItemFilter`, `getElements`, runtime ingredient/visibility listeners.
* `mezz.jei.common.Internal`: `setClientSyncedRecipes`, `clearClientRecipes`, `onRuntimeStopped`, `onClientStopping`.
