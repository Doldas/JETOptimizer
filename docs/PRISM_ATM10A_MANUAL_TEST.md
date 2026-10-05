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
grep -n -E 'JETOptimizer\] JEI initialization profile|Starting JEI took|main menu to in-game|Ingredient manager (at|after)|Runtime ingredient (add|remove)|Mixin apply .* jetoptimizer .* failed' "$LOG"
```

`Starting JEI took` is JEI's own timer and is what most of the login delay consists of. `Time from main menu to in-game` is ModernFix's end-to-end number for the same wait, so capture both.

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

**Every launch in this table was a new Minecraft process that performed exactly one join.** Each run was started from Prism, the server entry was selected manually, and the client stayed in that one world until the run was ended. No client process in this table survived a disconnect and rejoined, so none of these rows is a reconnect measurement: they are cold-process, first-connection numbers.

| Run | `profiling` | `Starting JEI` | menu → in-game | Notes |
|---|---|---:|---:|---|
| A | on | 27.447 s | 36.873 s | Earlier lighter hook set; not comparable hook-for-hook; fastest session |
| B | on | 31.413 s | 41.185 s | First full expanded profile |
| C | on | 30.347 s | 39.850 s | Clean quit after one join |
| D1 | off | 30.500 s | 39.690 s | Overhead control |
| D2 | off | 29.500 s | 38.554 s | Overhead control |
| D3 | off | 29.110 s | 37.740 s | Overhead control, fastest run |
| E1 | on | 31.244 s | 39.965 s | Profiling re-enabled after the controls |
| D4 | off | 30.800 s | 40.069 s | Overhead control |

ModernFix's `Time from main menu to in-game` is the number a player actually feels, and JEI is 76-78% of it in every run. Read both lines together when judging a change. Instrumented and uninstrumented runs differ by 0.135 s on a 4-versus-4 split, against a 1.1-1.7 s run-to-run spread, so profiler overhead is below the noise floor. Every structural counter was identical across all instrumented runs.

### Where the logs are

Prism rotates `latest.log` into `2026-10-05-N.log.gz` on each launch, **renumbering from the highest index down** and discarding the oldest. So the filenames in this table shift after every new launch; `-1` is the second-newest archive, not a stable identity. Verified mapping at the time of writing:

| Run | Rotated archive |
|---|---|
| A | `logs/2026-10-05-1.log.gz` |
| B | `logs/2026-10-05-2.log.gz` |
| C | `logs/2026-10-05-3.log.gz` |
| D1 | `logs/2026-10-05-4.log.gz` |
| D2 | `logs/2026-10-05-5.log.gz` |
| D3 | `logs/2026-10-05-6.log.gz` |
| E1 | `logs/2026-10-05-7.log.gz` |
| D4 | `logs/latest.log` |

For that reason, and because each launch overwrites `latest.log` and renumbers the rest, copy any log you care about to a durable path immediately. The eight runs above are preserved at `/tmp/opencode/atm10a-*.log`, which does not survive a reboot:

```sh
cp "$INSTANCE/minecraft/logs/latest.log" ~/jetoptimizer-runs/atm10a-$(date +%Y%m%d-%H%M%S).log
```

Identify a run by its timestamps and `Starting JEI` value, never by filename alone. Because the eight recorded launches were separate processes with a single join each, none of them measures an in-game disconnect/rejoin, and the reconnect profile is still unmeasured in the recorded data. A disconnect/rejoin was exercised interactively while developing the profiler, but no profile block from that sequence was preserved in the table above, so it cannot be used as a benchmark row.

From now on, `JEI initialization profile` carries a `Connection generation: N (join kind)` line. `Connection generation: 1` with `first join in this process` confirms a cold launch. A generation of 2 or higher means the same game process logged in again, and the parenthetical then says what it actually joined: `in-game reconnect to a remote server`, `in-game join to a local single player world`, or `in-game join, target could not be identified`. The profile also prints a `Structural comparison vs previous connection` block from the second join onward.

Use these lines, not the log filename, to classify a run. Two rules follow from the recorded three-generation run. A local single player world produces its own full JEI startup, so a local join is not a remote-server reconnect and must not be averaged with one. And a disconnect to the main menu is not a reconnect on its own: the second profile block appears only after the next join actually starts JEI, so if the tail shows `Stopping JEI` → `Sending Runtime Unavailable` → `Stopping!` the run ended rather than reconnected.

Run A's 27.447 s remains unexplained and is not a usable baseline. The current instance config is left with `profiling = true` and `pluginProfiling = true`.

## What to send with a test report

Include repeated JEI total times and run-to-run spread, the JETOptimizer profile block, relevant JEI `PluginCaller` lines, the scenario (first join/reconnect/reload/server switch), and the client/server mod versions. The profile is diagnostic: it does not change recipes, skip callbacks, cache the runtime, or require JETOptimizer on the server.
