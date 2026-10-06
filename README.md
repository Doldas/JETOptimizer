# JETOptimizer

JETOptimizer is an experimental client-side performance research mod for Minecraft 1.21.1 on NeoForge. It focuses initially on measuring JEI initialization during joins and reconnects in large modpacks.

**Reconnect caching is not implemented, but experimental optimizations are now active** and are only claimed where measured in repeatable in-game benchmarks. Current optimizations: fail-open replacement of JEI's per-tooltip-line search-word regular expressions, and removal of provably-dead KubeJS `onRuntimeAvailable` work (its recipe-category map and, where a remote removal scan runs, an ID-index on simple removal ingredients). Measured results and the fail-open reasoning are in the [performance analysis](docs/JEI_PERFORMANCE_ANALYSIS.md).

## Compatibility

- Minecraft 1.21.1
- NeoForge 21.1.x
- Java 21
- JEI 19.57.x (development runtime pins 19.57.0.449)
- Client-only; the server does not need JETOptimizer
- JEI is optional at mod-loading time

## Current status

The repository contains the client mod/config, the [JEI 19.57.0.449 lifecycle report](docs/JEI_LIFECYCLE_ANALYSIS.md), and the [ATM10 Aeronautics performance analysis](docs/JEI_PERFORMANCE_ANALYSIS.md). Profiling hooks are added only at source-confirmed lifecycle and timing boundaries. Active optimizations are individually gated (or always-on but fail-open by construction — a missed probe or unknown ingredient restores the original code path); see the configuration table below. Reconnect caching remains disabled and unimplemented.

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
| `experimentalOptimizations` | `false` | Reserved; currently not consumed by any feature |
| `fastSearchText` | `true` | Replace JEI's per-tooltip-line search-word regexes with equivalent non-regex scans; pure text work, no cached state |
| `kubeJsItemRemovalIndex` | `true` | Dense-ID candidate index for simple KubeJS remote item-removal ingredients; custom predicates keep the original scan |

Profiling and plugin profiling are independent. The reserved reconnect-cache options (`reconnectCache`, `debugCache`, `debugCacheInvalidation`) and `experimentalOptimizations` are currently inert; their presence does not imply that a cache exists. Active optimizations are individually gated (`fastSearchText` by itself, `kubeJsItemRemovalIndex` by `enabled` + the option). The KubeJS `onRuntimeAvailable` category-map skip has no config switch: it is always on, and fail-open by construction — a missed reflection probe or a present `REMOVE_*` listener restores the full original map build.

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
