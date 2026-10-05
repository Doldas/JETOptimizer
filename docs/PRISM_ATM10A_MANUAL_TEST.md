# Prism Launcher: ATM10 Aeronautics hand test

This is the repeatable client-side procedure for installing the current JETOptimizer build into the local All the Mods 10 Aeronautics Prism instance and capturing a JEI profile. JETOptimizer is client-only; do not install it on the server.

## Confirm the instance before touching it

In Prism Launcher, select the **All the Mods 10 Aeronautics ATM10A** instance and open **Edit → Version**. Confirm Minecraft 1.21.1 and NeoForge 21.1.x. The instance used for the current hand test is ATM10A pack version 0.7.1, NeoForge 21.1.250, and Java 21. The repository dev profile uses NeoForge 21.1.255; both are in the targeted 21.1 line, but re-check for compatibility warnings on the actual instance.

The usual Linux Prism data root is `~/.local/share/PrismLauncher`. Find the instance by its `instance.cfg` name/ID rather than assuming that every Prism installation uses this root. The detected local instance path was:

```text
~/.local/share/PrismLauncher/instances/All the Mods 10 Aeronautics ATM10A/
```

Before replacing a JETOptimizer jar, close that Minecraft instance. Keep at most one `jetoptimizer-*.jar` in its `minecraft/mods` directory; leaving an old JAR alongside a new one can cause a duplicate-mod failure.

## Build and install

From the JETOptimizer checkout:

```sh
./gradlew build
```

The built client mod is `build/libs/jetoptimizer-0.1.0.jar`. Set these paths for the target instance (quote the instance path because it contains spaces):

```sh
INSTANCE="$HOME/.local/share/PrismLauncher/instances/All the Mods 10 Aeronautics ATM10A"
MODS="$INSTANCE/minecraft/mods"
JETOPTIMIZER_JAR="build/libs/jetoptimizer-0.1.0.jar"
```

If that mods directory already contains a different `jetoptimizer-0.1.0.jar`, first copy that old file somewhere **outside** `minecraft/mods` as a rollback backup. Then replace it with the built JAR. Compare SHA-256 hashes to ensure the installed file matches the build:

```sh
cp "$JETOPTIMIZER_JAR" "$MODS/jetoptimizer-0.1.0.jar"
sha256sum "$JETOPTIMIZER_JAR" "$MODS/jetoptimizer-0.1.0.jar"
```

Always run the copy step. Do not assume the instance already has the current build: the jar is rebuilt on every source change, so an instance that matched the build once will drift. Verify by hash instead of by memory. Backups of previously installed jars are outside the instance at `/tmp/opencode/jetoptimizer-0.1.0-atm10a-backup.jar`; `~/.local/share/PrismLauncher/instances/All the Mods 10 Aeronautics ATM10A/minecraft/logs/` is the natural place for a rolled-back jar if `/tmp` has been cleared.

## Archive each run's log before relaunching

`minecraft/logs/latest.log` is overwritten by the next launch, and only then rotated into a timestamped `.log.gz`. Copy it out as soon as a run finishes, or grep it in place before quitting:

```sh
LOG="$HOME/.local/share/PrismLauncher/instances/All the Mods 10 Aeronautics ATM10A/minecraft/logs/latest.log"
grep -n -E 'JETOptimizer\] JEI initialization profile|Starting JEI took|Ingredient manager (at|after)|Runtime ingredient (add|remove)|Mixin apply .* jetoptimizer .* failed' "$LOG"
```

For a full profile block, print from the header to the plugin timings:

```sh
sed -n '/JEI initialization profile/,/JEI plugin timings/p' "$LOG"
```

## Configure profiling

In the instance's `minecraft/config/jetoptimizer-client.toml`, use:

```toml
[general]
enabled = true
profiling = true
pluginProfiling = true
reconnectCache = false
debugCache = false
debugCacheInvalidation = false
experimentalOptimizations = false
```

The two profiling options are independent. Reconnect caching and experimental optimizations remain disabled.

To measure what the instrumentation itself costs, keep the mod enabled but stand the timing hooks down:

```toml
[general]
enabled = true
profiling = false
pluginProfiling = false
```

JEI then starts normally and still prints its own `Starting JEI took ...` line, with no `[JETOptimizer]` profile block. Comparing several of those totals against the profiling runs gives a same-build overhead figure without swapping JARs. Restore `profiling = true` and `pluginProfiling = true` afterwards.

## Launch Prism and perform the hand test

Launch the instance from Prism's UI, or from a terminal with:

```sh
prismlauncher --launch "All the Mods 10 Aeronautics ATM10A" --show-window
```

Then:

1. Wait for the title screen and confirm JETOptimizer appears in the loaded-mod list. Inspect `minecraft/logs/latest.log` for `[JETOptimizer] JETOptimizer 0.1.0 loaded` and ensure there are no `Mixin apply for mod jetoptimizer failed` or mod-loading errors.
2. Connect to the same ATM10A test server used for the baseline through Prism/Minecraft's saved multiplayer entry. Do not substitute a different server when comparing before/after runs.
3. Wait for the world to become usable. Record JEI's `Starting JEI took ...` and the JETOptimizer initialization profile. The recipe-handler interval starts at `ClientPacketListener.handleUpdateRecipes` entry; it excludes packet transport, decode and time queued before the client handler.
4. Disconnect to the main menu, then reconnect to the same unchanged server. Each join is expected to log its own `JEI initialization profile` block, because JETOptimizer drops its pending session on disconnect rather than reusing it. Record both runs without changing pack contents, configs, or server state.
5. Capture an additional profile only after server restart, recipe/datapack changes, `/reload`, KubeJS changes, resource reload, or a server switch when specifically testing those invalidation scenarios.
6. Review `minecraft/logs/latest.log` for the recipe/category child breakdown, `IngredientFilter`/per-prefix search breakdown, before/after ingredient counts, runtime mutation request counts, and aggregate plugin timings. Keep parent/child timings separate; do not sum plugin UID totals with the stages they overlap.

Do not use a single-player or vanilla development-client time as the ATM10A reconnect benchmark. Those are useful only as smoke tests of mod loading and profiler hook coverage.

### Recognising the end of a run

A clean quit looks like this in `latest.log`, in order:

```text
Stopping JEI
Sending Runtime Unavailable
Stopping!
```

`Stopping JEI` / `Sending Runtime Unavailable` mean the client left the world; `Stopping!` means the game process exited. No crash report and no `Mixin apply ... failed` line means the run is usable. A crash instead produces a timestamped file under `minecraft/crash-reports/`. Note that quitting after a disconnect is not a reconnect test: the reconnect scenario needs the client to stay alive and rejoin.

## Runs recorded so far

Eight launches against the same saved server, with no pack or server changes. The only config change was toggling `profiling` / `pluginProfiling` for the overhead comparison.

| Run | Log | `profiling` | `Starting JEI` | Notes |
|---|---|---|---:|---|
| A | `logs/2026-10-05-5.log.gz` | on | 27.447 s | Earlier lighter hook set; not comparable hook-for-hook |
| B | `logs/2026-10-05-6.log.gz` | on | 31.413 s | First full expanded profile |
| C | `logs/2026-10-05-7.log.gz` | on | 30.347 s | Clean quit after one join |
| D1 | `/tmp/opencode/atm10a-control-1-latest.log` | off | 30.500 s | Overhead control |
| D2 | `/tmp/opencode/atm10a-control-2-latest.log` | off | 29.500 s | Overhead control |
| D3 | `/tmp/opencode/atm10a-control-3-latest.log` | off | 29.110 s | Overhead control, fastest run |
| E1 | `/tmp/opencode/atm10a-on-1-latest.log` | on | 31.244 s | Profiling re-enabled after the controls |
| D4 | `/tmp/opencode/atm10a-control-4-latest.log` | off | 30.800 s | Overhead control |

Instrumented runs span 1.066 s and uninstrumented runs span 1.690 s, so the ranges overlap and sub-second differences are noise. Profiling appears to add about 1 s (~3.4%), but the arms are not cleanly separated at this sample size. Every structural counter was identical across all instrumented runs. See [the performance analysis](JEI_PERFORMANCE_ANALYSIS.md) for the full comparison.

The D-series logs live in `/tmp/opencode/` because `latest.log` is overwritten on each launch; copy logs there (or elsewhere durable) as you go, since `/tmp` does not survive a reboot. No run so far covered a disconnect/rejoin, so the reconnect profile is still unmeasured.

Run A's 27.447 s remains unexplained and is not a usable baseline. The current instance config is left with `profiling = true` and `pluginProfiling = true`.

## What to send with a test report

Include repeated JEI total times and run-to-run spread, the JETOptimizer profile block, relevant JEI `PluginCaller` lines, the scenario (first join/reconnect/reload/server switch), and the client/server mod versions. The profile is diagnostic: it does not change recipes, skip callbacks, cache the runtime, or require JETOptimizer on the server.
