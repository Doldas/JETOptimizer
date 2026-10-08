# Prepared native recipe cache and batch processing

This cache targets repeated JEI ingredient-supplier preparation, separately from the substring cache. Cached recipe lists alone would leave the costly `setRecipe` and ingredient relationship work in place. The implementation persists native recipe payloads and their prepared ingredient-role blocks, then reuses those blocks with the current runtime's ingredients.

## Supported boundary

The exact supported JEI version is 19.57.0.449. The recipe must be a `RecipeHolder` of the exact vanilla shaped, shapeless, smelting, blasting, smoking, campfire cooking or stonecutter class, registered in its corresponding exact native JEI category. Recipes from any mod namespace can qualify when they use these vanilla classes. Crafting also requires JEI's exact default `CraftingCategoryExtension`; custom extensions and subclasses bypass.

The native item ingredient helper must be unchanged. Mixin-added/overwritten methods detected on the supported category, relevant JEI helpers, or recipe class disable reuse at that boundary. The guard reads Mixin's runtime `MixinMerged` annotation. It is conservative and may reduce coverage in modpacks. Unknown transformers that do not expose that marker cannot be identified by this guard; these adapters assume the pinned native method semantics. Arbitrary world/player-dependent plugin recipes need additional adapters and are not cached by this implementation.

The native adapters do not cache GUI drawing, tooltips, recipe transfer, focused layouts, plugin callbacks, or live recipe objects. GUI interactions continue to use the current category and recipe. Supported recipes with focus links, unsupported ingredient types, or a captured role list differing from the native expected layout are not persisted. This prevents caching failed/partial or modified layouts.

## Launch, validation and partial changes

Client setup starts background preload of `cache/jetoptimizer/prepared-recipes-v1.bin.gz`. Installed mod IDs, versions and paths are detached before hashing their JAR contents. Development class/resource directories are also hashed, with a bounded file count. A missing or unreadable required fingerprint prevents reuse of its dependent recipes.

Every entry contains the category/recipe ID, serialized recipe payload, SHA-256 fingerprint, dependency mod hashes and ingredient-role blocks. Dependencies include Minecraft, NeoForge, JEI and JETOptimizer, the recipe namespace when it names an installed mod, and the namespaces of referenced ingredient/result/fuel items. A changed dependency removes its affected entries during preload; unrelated entries survive. A JEI/adapter version change invalidates the supported cache globally. Recipe ownership alone is insufficient: an update to an ingredient mod can invalidate another mod's recipe entries.

At each authoritative JEI registration batch, the client snapshots the current recipe serializer payload, resolved ingredient alternatives in their original order, the current output including components/count, and category fuels. Hashes use exact UTF-16 with field boundaries. This checks server/datapack/script changes and changed tag contents even when every JAR/version is unchanged. Category fuel data is refreshed at each registration batch. Duplicate IDs with different fingerprints never share a cached result.

The disk snapshot is loaded into memory before world entry where preload completes in time, but it is not inserted into Minecraft's recipe manager. The synchronized recipe list always determines which entries are eligible for reuse. A deleted server recipe is never restored from disk. Changed payloads miss, build normally and replace only their entry; unused entries can remain bounded in storage for later use but never register themselves. The store retains one variant per category/recipe ID, so switching between different server variants can miss.

## Streams, IDs, bulk operations and workers

A bounded pool defaults to **three workers**, configurable from two to four. Large batches are partitioned into at most that many ordered chunks. The task queue is bounded, with caller participation as backpressure; there is no common-pool parallel stream. Small batches stay on the caller to avoid scheduling overhead. Submitted data contains only immutable strings and dependency hashes. All registry accesses, recipe serialization, category callbacks, JEI map mutations and current ingredient rebinding remain sequential on the client thread.

Cache lookups hold the store lock once per registration batch, and rebuilt entries are committed in bulk after the original `addRecipes` call. JEI's original batch boundary and recipe order are preserved. Identical ingredient-role blocks are interned, persisted once in a dictionary, and referenced by integer IDs. For example, thousands of cooking recipes can share one fuel block. Current typed ingredients and decoded role blocks are created once per distinct encoded value/block during registration. The exact IDs for those shared immutable typed ingredients are computed on demand once within that runtime and reused in `RecipeMap` insertion. IDs are not persisted or reused across worlds.

Dirty entries are saved after startup on one disk worker shared with the substring cache (three recipe workers plus one disk worker by default), using gzip checksums and temporary-file replacement, atomically where supported. Writes coalesce revisions; a fully unchanged warm runtime does not rewrite the file. The disk thread may also participate in a bounded hashing task under backpressure. Worker configuration changes require a restart.

Project-wide refactors use ordered streams for ingredient counts, focus-link construction, simple recursive predicates and profiler comparisons, bulk list copying for ingredient slots, and direct context-ID maps for visibility groups. Binary codecs, checked I/O, text scanning, stateful timing accumulation and the hot visibility early-exit loops retain explicit iteration where it keeps ordering, exception handling or allocation costs clear. Streams are not assumed to be faster. No callback is moved to a worker merely because it can be expressed as a stream.

## Bounds and fallback

The prepared store retains at most 250,000 entries and a 128 MiB estimated encoded-content budget with LRU eviction. Shared blocks count once against that budget. Single role blocks have at most 65,536 ingredients and text fields at most 2 MiB. The loader limits compressed/decompressed bytes and total ingredient references, checks format/version, validates role/dictionary references and consumes the gzip footer to validate its checksum. Actual Java heap usage exceeds encoded size because maps, arrays and strings have object overhead.

Cold runs have additional snapshot/serialization/hash work and should be benchmarked. Oversized batches, unsupported categories/extensions/helpers, reflection or codec failures, an unavailable preload, changed keys and damaged files retain the original JEI path. Abrupt exit before a daemon disk write completes may lose the newest additions; the next launch rebuilds them. Existing configs are respected. Activation requires `enabled`, `reconnectCache`, `experimentalOptimizations` and `optimizations.persistentRecipeCache`; changing from disabled after client setup requires a restart.

## Validation and measurement

Tests cover disk round trips, authoritative-list-only lookup, selective dependency invalidation, actual file changes with unchanged versions, changed payload/tag/fuel misses, shared block accounting, LRU eviction, preserved entries after an oversized replacement, no-op revisions, malformed counts/references, gzip checksum/truncation, atomic-replacement cleanup, exact Unicode fingerprints, worker ordering/concurrency/backpressure and worker failure propagation. NeoForge tests additionally exercise current item counts/components, role preservation, ingredient/block reuse within a runtime, fresh rebinding, custom-helper rejection and refusal to capture partial/focus-linked layouts. Existing randomized JEI supplier/query/visibility equivalence tests exercise the stream/bulk refactors.

A full modpack client launch is still required to validate injected runtime boundaries. Compare repeated cold runs, warm full restarts, same-server reconnects, server changes, changed datapacks/tags/scripts and a changed mod JAR. Verify searches, R/U lookups, ingredient ordering, displayed recipes and transfer behavior. Check preload logs and the startup summary's hits, misses, bypasses, captured entries, shared UID requests and snapshot/hash/rebind times. Set `persistentRecipeCache=false` for the baseline while keeping other options identical.

A cache hit count is not proof of a faster total join. Compare medians and include fingerprint/codec overhead. No below-15-second result is claimed without an in-game benchmark; custom plugin recipes remain a significant uncached workload.
