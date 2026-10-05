# Profiler mixin compatibility notes

These hooks are diagnostic only. They are configured as optional, version-specific hooks with a default injection requirement of zero. A missing method should leave JEI running normally; the profile reports which hooks were observed so incomplete instrumentation is visible. There are no runtime/cache modifications.

The supported source baseline is JEI 19.57.0.449. All JEI targets below were checked against its Maven source artifacts.

| Mixin | Target | Hook and purpose | Failure behavior |
|---|---|---|---|
| `ClientPacketListenerMixin` | Minecraft `ClientPacketListener.handleUpdateRecipes` | At method entry, mark the start of the client recipe packet handler so it can be compared with `RecipesUpdatedEvent` and JEI startup. | Vanilla method mapping/injection mismatch is optional (`defaultRequire: 0`); packet handling continues without this timing. |
| `JeiStarterMixin` | JEI `mezz.jei.library.startup.JeiStarter.start` | Starts immediately after `LoggedTimer.start("Starting JEI")` and finishes immediately after its matching `LoggedTimer.stop()` (the second `stop()` call in this method). This aligns the summary with JEI's own total and excludes post-timer recipe verification. | The timer call/ordinal is source-version-specific; if it changes, the optional hook is skipped and no JETOptimizer summary is produced. JEI itself is unchanged. |
| `PluginLoaderMixin` | JEI `mezz.jei.library.load.PluginLoader` | Times exact source methods for subtype, ingredient, mod-alias, search-factory, recipe/category, transfer, and GUI-handler registration. | Missing method hooks are skipped. Existing JEI registration proceeds untouched. |
| `JeiGuiStarterMixin` | JEI `mezz.jei.gui.startup.JeiGuiStarter.start` | Times JEI's GUI-runtime construction boundary. | Missing hook omits this row; it does not bypass GUI construction. |
| `IngredientListElementFactoryMixin` | JEI `mezz.jei.gui.ingredients.IngredientListElementFactory.createBaseList` | Times the ingredient-list factory call used by JEI GUI initialization. | Missing hook omits the stage timing; no list behavior is modified. |
| `IngredientFilterMixin` | JEI `mezz.jei.gui.ingredients.IngredientFilter.<init>` and `createElementSearch` | Separately bounds the full filter constructor (sorting, search setup and initial visibility work) and JEI's actual element-search/index factory. The index timer is nested inside the filter timer. | Missing hook omits the corresponding breakdown; JEI filter and search construction remain intact. |
| `PluginCallerMixin` | JEI `mezz.jei.library.load.PluginCaller.callOnPlugins` | Redirects only the existing callback invocation when opt-in plugin profiling is enabled; aggregates `System.nanoTime()` by plugin UID. It does not log recipes or ingredients. | When disabled, immediately delegates to the original `Consumer.accept`. The mixin is optional and fail-open on target changes. |
| `RecipeManagerInternalMixin` | JEI `mezz.jei.library.recipes.RecipeManagerInternal.<init>` | Reads the actual category collection size passed into JEI's recipe manager constructor. | Missing hook reports category count unavailable; it does not alter recipe manager construction. |

The plugin callback redirect adds a single config check per callback when inactive. When active, it samples one monotonic start/end pair and updates an in-memory counter. Plugin totals are reported only after JEI initialization completes. Since these totals overlap the named JEI registration stages, do not add them together when interpreting the report.
