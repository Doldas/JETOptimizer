# Behavior-focused tests

Run `bash gradlew test` for the full suite, or `bash gradlew test -PtestSuite=workflow`
for end-user service scenarios. CI runs both and uploads their HTML reports even on failure.
No coverage percentage is a gate. Assertions concern observable correctness, ownership,
resource limits, fallback behavior and authoritative server data.

| Player or integration risk | Automated evidence |
| --- | --- |
| Closing the game loses reusable search data | Search cache service saves on its real disk executor, a fresh preload reads the file, and results refer only to new ingredient objects |
| Joining while disk preload is slow hangs | Pending and exceptional preload futures return native fallback within a bounded deadline |
| Corrupt or unwritable disk cache prevents joining | Actual service initialization ignores corruption; rebuilding replaces it; failed writes retain memory hits |
| Another server changes search words | Changed, deleted and reordered words miss instead of serving stale results |
| Changed server recipes or duplicate IDs show old outputs | Recipe service batches use actual native categories/registry codecs; fresh recipe objects hit, changed payloads miss and replace; duplicate IDs never receive another payload |
| Deleted recipe reappears from disk | Empty authoritative batch cannot look up old recipes |
| Updated mods reuse stale recipe data | Dependency invalidation and service fallback; existing store tests also check selective invalidation and unchanged-version JAR changes |
| Nested plugin callbacks leak cache state after failure | Nested recipe batch masks the outer context, restores it on exception and clears it on close |
| Next recipe or next world inherits previous builder state | Actual JEI builders are emptied after immutable supplier extraction; manager changes, disconnect and another thread cannot reuse old builders |
| Optimizations alter tooltip search text | Differential tests against Minecraft formatting and Java whitespace semantics, including every UTF-16 formatting follower and seeded random tooltip lines |
| Item removals ignore components, ordering or earlier removals | Native Ingredient predicate comparisons over real ItemStacks and live source mutation; custom exclusion, empty-filter and size-change fallback |
| Second KubeJS iterator hides unrelated items | Regression scenario consumes the fast iterator, then verifies a full scan still applies the original predicate |
| Third-party visibility listener changes semantics | Actual visibility service retains individual dispatch for unknown listeners; isolated grouping tests check context masks, duplicates, order, flush-before-visible and idempotent finish |
| JEI changes internal mixin targets silently | Every configured non-KubeJS mixin is inspected against actual dependency bytecode: target method selectors, shadow field/method types and injection invocation targets/ordinals |
| Tooltip setting changes between runtimes are ignored | Runtime switch scenario verifies restart refresh and master-disable precedence |
| Worker failure, saturation or corrupt cache exhaust resources | Existing bounded-worker and disk format suites test ordered output, caller backpressure, failure completion, allocation limits and malformed input |

## Test boundaries

These are headless NeoForge unit and component workflow tests, not a launched Minecraft UI
or live network server. Service tests inject detached loader inputs through an isolated
reflection fixture because JEI's client loader and level are unavailable in the dedicated-server
JUnit environment. The service itself, native crafting grid helpers, registry codecs, cache stores and
search disk executor execute normally. Fixtures restore their inputs and drain disk tasks;
JUnit's default sequential execution must be retained for shared static service state.

Direct search mixin handler tests execute compiled HEAD callbacks with real cancellable Mixin
callback objects. They check enabled cancellation/results and disabled/null fall-through;
a separate class loader avoids applying those classes to client targets.

Mixin contract tests inspect compiled annotations and dependency classfiles. They do **not**
apply client mixins or prove cancellation/redirect behavior in a live client. KubeJS is optional
and absent from the test dependencies; its plugin bytecode is not covered by those contracts.
Its removal service behavior is exercised directly. Visibility grouping tests inject a compatible
listener decision to observe dispatch; a separate test uses the real compatibility gate for
third-party fallback. Recipe workflow saves/reloads use the real store with explicit manifests;
loader mod discovery and client launch preload remain client integration checks. The crafting
fixture supplies its registry directly to JEI's grid helper because the native category extension
obtains its registry from Minecraft.level; it does not run that client-only lookup.

## Whole-project review and remaining client checks

The review covered cache storage/preload and worker services, native recipe adaptation,
recipe extraction/visibility/indexing, builder reuse, GUI/search text/tooltip switches,
KubeJS removal and visibility batching, profiling/lifecycle hooks, mixin configuration,
Gradle and CI. Profiling output strings and private counters are not coverage targets.

Before claiming a loading-time improvement, run the built mod in the representative modpack:
cold launch/join, close/relaunch/join, reconnect, singleplayer, server datapack reload with
changed/deleted recipes, custom recipe categories/ingredients, and a JEI runtime restart.
Verify focused recipe links, hover tooltips and search results with optimizations enabled and
disabled. Add KubeJS and another ingredient visibility listener for optional integration checks.
Record actual timings; unit tests do not establish a 15-second or 20-second loading target.
