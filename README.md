# JETOptimizer

JETOptimizer is an experimental client-side performance research mod for Minecraft 1.21.1 on NeoForge. It focuses initially on measuring JEI initialization during joins and reconnects in large modpacks.

**Reconnect caching is not implemented.** JETOptimizer includes the verified JEI search-text fast path and opt-in experimental KubeJS/runtime-removal/reusable-recipe-builder optimizations. The fast-join experiment can skip tooltip-word generation in JEI's search index; regular hover tooltips are unaffected, but searching by tooltip text is disabled for that runtime. Measurements and lifecycle/source evidence are in the [performance analysis](docs/JEI_PERFORMANCE_ANALYSIS.md).

## Compatibility

- Minecraft 1.21.1
- NeoForge 21.1.x
- Java 21
- JEI 19.57.x (development runtime pins 19.57.0.449)
- Client-only; the server does not need JETOptimizer
- JEI is optional at mod-loading time

## Current status

The repository contains the client mod/config, the [JEI 19.57.0.449 lifecycle report](docs/JEI_LIFECYCLE_ANALYSIS.md), and the [ATM10 Aeronautics performance analysis](docs/JEI_PERFORMANCE_ANALYSIS.md). Profiling hooks are added only at source-confirmed lifecycle and timing boundaries. Experimental optimizations require `enabled` and `experimentalOptimizations`, plus their individual option; uncertain KubeJS probes or third-party JEI visibility listeners retain the original path. Reconnect caching remains disabled and unimplemented.

For hands-on testing with the local Prism Launcher ATM10 Aeronautics instance, follow [the Prism/ATM10A test guide](docs/PRISM_ATM10A_MANUAL_TEST.md).

The report documents which JEI objects are reconstructed, which owners/listeners are stopped on disconnect, existing JEI timers, and what still needs runtime measurement. In JEI 19.57.0.449, `Starting JEI` includes the runtime/plugin callbacks, while recipe synchronization precedes it; JEI's `Building ingredient filter` timer combines sorting, search construction, and visibility work. JETOptimizer profiles the actual `IngredientFilter.createElementSearch` boundary separately from the full filter constructor.

## Configuration

NeoForge creates `config/jetoptimizer-client.toml` on the client. Current options:

| Option | Default | Purpose |
|---|---:|---|
| `enabled` | `true` | Master switch for JETOptimizer features |
| `profiling` | `false` | Enable lifecycle/initialization profiling |
| `pluginProfiling` | `false` | Enable additional per-plugin timing aggregation |
| `reconnectCache` | `false` | Reserved; no cache implementation is active |
| `debugCache` | `false` | Reserved cache diagnostics |
| `debugCacheInvalidation` | `false` | Reserved invalidation diagnostics |
| `experimentalOptimizations` | `false` | Master switch for experimental optimizations |
| `fastSearchText` | `true` | Replace JEI's per-tooltip-line search-word regexes with equivalent non-regex scans; pure text work, no cached state |
| `kubeJsItemRemovalIndex` | `true` | Dense-ID candidate index for simple KubeJS remote item-removal ingredients; custom predicates keep the original scan |
| `skipUnusedKubeJsCategoryMap` | `true` | Skip category-map construction only when no removal listeners exist and KubeJS `remote` is null |
| `bulkRuntimeRemovalVisibility` | `true` | Batch JEI visibility callbacks for runtime removals when only JEI's internal listeners are present |
| `reuseRecipeLayoutBuilders` | `true` | Reuse temporary JEI recipe-layout builders during recipe registration; categories must not retain builders after `setRecipe` |
| `fastJoinSkipTooltipSearch` | `false` | Skip tooltip-word generation during JEI search-index construction to test a faster join; does not affect displayed hover tooltips |

Profiling and plugin profiling are independent. The reserved reconnect-cache options (`reconnectCache`, `debugCache`, `debugCacheInvalidation`) remain inert. `fastSearchText` is independently switchable; the other experimental optimizations require both master switches and their corresponding option. KubeJS reflection failures and any extra `IIngredientVisibility` listener fall back to the original behavior. Recipe-layout builders are cleared at the end of registration, and on JEI runtime stop, so the pool does not retain an ingredient manager across joins.

## Build and run

The Gradle build uses Kotlin DSL, and the mod itself uses Java 21. Gradle's Foojay toolchain resolver provisions a Java 21 toolchain if needed.

```sh
./gradlew build
./gradlew runClient
```

The development client includes JEI 19.57.0.449 for lifecycle and profiler validation.

## Benchmark methodology

Measure first connection and repeated unchanged reconnects separately. Also record server restart, changed recipe/datapack, server switch, KubeJS recipe change, `/reload`, resource reload, and singleplayer entry/exit scenarios. Record the client recipe-handler interval (from `ClientPacketListener.handleUpdateRecipes` entry to `RecipesUpdatedEvent`) separately from JEI's own `Starting JEI` timer; this excludes network transport, packet decode, and time queued before the client handler. Also record JEI's existing stage and plugin callback timing logs. Compare repeated runs using medians and run-to-run spread; small sub-second changes are not proof of an optimization.

## Reporting issues

Include `latest.log`, JETOptimizer/JEI/NeoForge versions, the reproduction steps, the scenario being tested, and whether plugin/profiler options were enabled. For performance reports, include repeated timings and avoid sharing private modpack/server data. Do not attach copyrighted modpack scripts unless you have permission to redistribute them.
