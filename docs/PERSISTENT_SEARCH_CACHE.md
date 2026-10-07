# Persistent JEI search cache

The cache removes repeated construction of JEI's baked substring gram lookup tables. It is enabled by `enabled = true` and `reconnectCache = true`, independently of `experimentalOptimizations`, for the exact supported JEI version 19.57.0.449.

## Launch and join lifecycle

1. At NeoForge client setup, a daemon worker loads `cache/jetoptimizer/search-index-v1.bin` relative to the game instance directory. This starts before the main menu and before server/singleplayer entry. The main thread does not wait for disk I/O.
2. JEI still collects the current runtime's ingredient search strings. Each table's ordered strings are fingerprinted with SHA-256, including their count, boundaries and exact UTF-16 code units.
3. A matching fingerprint restores the baked table using the current strings and ingredient objects. Value identity deduplication is recomputed for that runtime. Server addresses are not needed: reuse is safe wherever the exact search strings match.
4. On a miss, JEI builds its original table. The completed integer postings are retained and saved on the worker, using coalesced writes and temporary-file replacement. Reconnects can reuse these tables from memory.
5. After closing and relaunching Minecraft, the next client setup preloads the saved postings again. Rebinding and fingerprint verification happen when the current JEI runtime starts, after entering the world.

The first join with no file is a cold build. Preloading begins at startup but is not guaranteed to finish before an immediate join; if it is still running, that build uses the original path. Enabling the option after client setup requires restarting Minecraft.

## Scope and bounds

Only long gram keys and integer posting arrays are persisted. Ingredient objects, recipe objects, world/player references, plugin callbacks, search strings and tooltip results are not stored. Changed strings, language, resources, datapacks or scripts naturally miss whenever they change the table's ordered input. If the strings remain identical, the table is still valid even when ingredient identities change.

The store retains at most 16 tables, with a 64 MiB encoded-size budget and least-recently-used eviction. Individual tables are limited to 2,000,000 keys and 262,144 grams. Java heap usage can exceed the encoded file size because maps and arrays have object overhead. Tables exceeding these bounds use the original build.

The format has an algorithm/version guard and CRC32 checksum. Loading validates counts, unique grams and strictly increasing, in-range postings before reuse. Missing, truncated, damaged or incompatible files fall back to an empty cache. Reflection failure falls back to JEI. Saves replace the previous file atomically where supported; interrupted writes retain the previous complete file on those filesystems. An abrupt exit before a background write finishes can lose the newest cache additions, requiring a cold build next time.

Deleting the file while Minecraft is closed resets the disk cache. `reconnectCache = false` disables preload and lookup; existing files remain available if re-enabled at the next launch. Existing client configuration values are respected.

## Validation and measurement

Automated tests round-trip actual JEI-built tables through disk, rebind new values, and compare 20,000 randomized queries against JEI's native builder. They cover identity deduplication changes, Unicode/unpaired-surrogate fingerprints, changed-input misses, empty indexes, LRU bounds, checksums, unsupported formats, invalid postings and file replacement. These validate the table format and query equivalence; a real client test is still required to validate the launch/join mixin lifecycle in a modpack.

For an in-game A/B check:

1. Enable profiling. Delete the file with Minecraft closed, then launch and join. Record `Starting JEI` and baked substring build timings. Expect cache misses and a new file.
2. Close normally, relaunch and join the same world/server. Look for `Disk search cache preloaded` before the join and the hit/miss summary when JEI completes. `debugCache` logs each hit; `debugCacheInvalidation` logs each miss.
3. Repeat with changed language/resources/recipe inputs and a different server. Inputs that change should miss; unchanged tables may still hit safely. Verify ingredient searches, recipe views and displayed tooltips.
4. Close, damage the cache file, then relaunch. Expect a warning, a normal join, and a rebuilt cache. Compare repeated medians with caching disabled as the baseline.

The earlier profile attributed about 0.2 seconds to baked substring construction. This cache targets that work; it does not remove recipe registration, GUI layout construction or tooltip generation, so it cannot establish a below-15-second join by itself. The separately configured fast-join option skips tooltip search-word generation; its existing default is preserved by this change.
