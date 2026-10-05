# JETOptimizer

JETOptimizer is an experimental client-side performance research mod for Minecraft 1.21.1 on NeoForge. It focuses initially on measuring JEI initialization during joins and reconnects in large modpacks.

**No reconnect cache or performance optimization is enabled.** The first development milestone is source-backed lifecycle analysis and accurate profiling. No speedup is claimed until it is measured in repeatable in-game benchmarks.

## Compatibility

- Minecraft 1.21.1
- NeoForge 21.1.x
- Java 21
- JEI 19.57.x (development runtime pins 19.57.0.449)
- Client-only; the server does not need JETOptimizer
- JEI is optional at mod-loading time

## Current status

The repository contains the initial client mod/config skeleton and [JEI 19.57.0.449 lifecycle report](docs/JEI_LIFECYCLE_ANALYSIS.md). Profiling hooks are being added only at source-confirmed lifecycle and timing boundaries. Reconnect caching remains disabled and unimplemented.

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
| `experimentalOptimizations` | `false` | Keep experimental optimizations disabled by default |

Profiling and plugin profiling are independent. Options marked reserved are currently inert; their presence does not imply that a cache or optimization exists.

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
