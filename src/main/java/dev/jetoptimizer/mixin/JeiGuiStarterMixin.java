package dev.jetoptimizer.mixin;

import dev.jetoptimizer.JETOptimizerProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Times the whole of {@code JeiGuiStarter.start} and, inside it, an ordered set of gates.
 *
 * <p>JEI's own {@code LoggedTimer} only wraps the ingredient list and the ingredient filter. On this
 * pack the GUI runtime construction stage is roughly 3.3 to 3.6 s longer than those two timers, so
 * about a third of that stage was unattributed.
 *
 * <p>A gate marks where one named block starts, so its duration is measured up to the next gate. The
 * gates are recorded in an ordered list rather than as begin/end pairs: a gate whose target does not
 * match this JEI version simply drops out of the waterfall instead of unbalancing the rest of it.
 * Static state is reset at {@code HEAD} because {@code start} runs once per runtime on one thread.
 */
@Pseudo
@Mixin(targets = "mezz.jei.gui.startup.JeiGuiStarter", remap = false)
@SuppressWarnings("unused")
abstract class JeiGuiStarterMixin {
    @Unique
    private static String jetoptimizer$openGate;

    @Unique
    private static long jetoptimizer$openGateStartedAt = -1L;

    @Unique
    private static void jetoptimizer$gate(String label) {
        long now = System.nanoTime();
        if (jetoptimizer$openGate != null && jetoptimizer$openGateStartedAt >= 0L) {
            JETOptimizerProfiler.recordGuiRuntimeGate(jetoptimizer$openGate, now - jetoptimizer$openGateStartedAt);
        }
        jetoptimizer$openGate = label;
        jetoptimizer$openGateStartedAt = now;
    }

    @Inject(method = "start", at = @At("HEAD"), remap = false)
    private static void jetoptimizer$beginGuiRuntime(CallbackInfoReturnable<?> callbackInfo) {
        JETOptimizerProfiler.beginStage("JEI GUI runtime construction");
        jetoptimizer$openGate = null;
        jetoptimizer$openGateStartedAt = -1L;
        jetoptimizer$gate("gui setup and color reload");
    }

    @Inject(method = "start", at = @At(value = "INVOKE", ordinal = 0, shift = At.Shift.BEFORE, target =
        "Lmezz/jei/gui/ingredients/IngredientListElementFactory;createBaseList(Lmezz/jei/api/runtime/IIngredientManager;Lmezz/jei/common/config/IIngredientFilterConfig;Lmezz/jei/api/helpers/IModIdHelper;)Ljava/util/List;"), remap = false)
    private static void jetoptimizer$gateIngredientList(CallbackInfoReturnable<?> callbackInfo) {
        jetoptimizer$gate("ingredient list construction");
    }

    @Inject(method = "start", at = @At(value = "INVOKE", ordinal = 0, shift = At.Shift.BEFORE, target =
        "Lmezz/jei/gui/ingredients/IngredientFilter;.<init>(Lmezz/jei/gui/filter/IFilterTextSource;Lmezz/jei/common/config/IClientConfig;Lmezz/jei/common/config/IIngredientFilterConfig;Lmezz/jei/api/runtime/IIngredientManager;Ljava/util/function/Function;Ljava/util/List;Lmezz/jei/api/helpers/IModIdHelper;Lmezz/jei/api/runtime/IIngredientVisibility;Lmezz/jei/gui/config/IngredientTypeSortingConfig;Lmezz/jei/api/helpers/IColorHelper;Lmezz/jei/api/search/ISearchStorageBuilderFactory;Lmezz/jei/common/config/IClientToggleState;)V"), remap = false)
    private static void jetoptimizer$gateIngredientFilter(CallbackInfoReturnable<?> callbackInfo) {
        jetoptimizer$gate("ingredient filter construction");
    }

    @Inject(method = "start", at = @At(value = "INVOKE", ordinal = 0, shift = At.Shift.BEFORE, target =
        "Lmezz/jei/gui/bookmarks/BookmarkCodec;create(Lmezz/jei/api/helpers/ICodecHelper;Lmezz/jei/api/runtime/IIngredientManager;Lmezz/jei/api/recipe/IRecipeManager;Lmezz/jei/common/transfer/RecipeTransferService;Lmezz/jei/gui/bookmarks/BookmarkFactory;)Lcom/mojang/serialization/MapCodec;"), remap = false)
    private static void jetoptimizer$gateBookmarkCodec(CallbackInfoReturnable<?> callbackInfo) {
        jetoptimizer$gate("bookmark factory and codec");
    }

    @Inject(method = "start", at = @At(value = "INVOKE", ordinal = 0, shift = At.Shift.BEFORE, target =
        "Lmezz/jei/gui/overlay/bookmarks/history/LookupHistory;.<init>(Lmezz/jei/api/recipe/IRecipeManager;Lmezz/jei/api/runtime/IIngredientManager;Lnet/minecraft/core/RegistryAccess;Lmezz/jei/api/helpers/ICodecHelper;Lnet/mezzdev/config/api/value/IConfigValue;Lmezz/jei/gui/config/ILookupHistoryConfig;Lcom/mojang/serialization/Codec;)V"), remap = false)
    private static void jetoptimizer$gateLookupHistory(CallbackInfoReturnable<?> callbackInfo) {
        jetoptimizer$gate("lookup history");
    }

    @Inject(method = "start", at = @At(value = "INVOKE", ordinal = 0, shift = At.Shift.BEFORE, target =
        "Lmezz/jei/gui/startup/OverlayHelper;createIngredientListOverlay(Lmezz/jei/api/runtime/IIngredientManager;Lmezz/jei/api/runtime/IScreenHelper;Lmezz/jei/gui/overlay/ingredients/IIngredientGridSource;Lmezz/jei/gui/overlay/ingredients/IIngredientGridSource;Lmezz/jei/gui/filter/IFilterTextSource;Lmezz/jei/common/input/IInternalKeyMappings;Lmezz/jei/common/config/IIngredientGridConfig;Lmezz/jei/common/config/IClientConfig;Lmezz/jei/common/config/IClientToggleState;Lmezz/jei/common/network/IConnectionToServer;Lmezz/jei/common/config/IIngredientFilterConfig;Lmezz/jei/common/gui/textures/Textures;Lmezz/jei/api/helpers/IColorHelper;)Lmezz/jei/gui/overlay/IngredientListOverlay;"), remap = false)
    private static void jetoptimizer$gateIngredientOverlay(CallbackInfoReturnable<?> callbackInfo) {
        jetoptimizer$gate("ingredient list overlay");
    }

    @Inject(method = "start", at = @At(value = "INVOKE", ordinal = 0, shift = At.Shift.BEFORE, target =
        "Lmezz/jei/gui/bookmarks/BookmarkList;.<init>(Lmezz/jei/api/recipe/IRecipeManager;Lmezz/jei/api/recipe/IFocusFactory;Lmezz/jei/api/runtime/IIngredientManager;Lnet/minecraft/core/RegistryAccess;Lmezz/jei/gui/config/IBookmarkConfig;Lmezz/jei/common/config/IClientConfig;Lmezz/jei/api/helpers/IGuiHelper;Lmezz/jei/api/helpers/ICodecHelper;Lmezz/jei/gui/bookmarks/BookmarkFactory;Lcom/mojang/serialization/Codec;)V"), remap = false)
    private static void jetoptimizer$gateBookmarkList(CallbackInfoReturnable<?> callbackInfo) {
        jetoptimizer$gate("bookmark list");
    }

    @Inject(method = "start", at = @At(value = "INVOKE", ordinal = 0, shift = At.Shift.BEFORE, target =
        "Lmezz/jei/gui/config/IBookmarkConfig;.loadBookmarks(Lmezz/jei/api/recipe/IRecipeManager;Lmezz/jei/api/recipe/IFocusFactory;Lmezz/jei/api/helpers/IGuiHelper;Lmezz/jei/api/runtime/IIngredientManager;Lnet/minecraft/core/RegistryAccess;Lmezz/jei/gui/bookmarks/BookmarkList;Lmezz/jei/api/helpers/ICodecHelper;Lcom/mojang/serialization/Codec;Lmezz/jei/gui/bookmarks/BookmarkFactory;Lmezz/jei/common/transfer/RecipeTransferService;)V"), remap = false)
    private static void jetoptimizer$gateBookmarkConfigLoad(CallbackInfoReturnable<?> callbackInfo) {
        jetoptimizer$gate("bookmark config load");
    }

    @Inject(method = "start", at = @At(value = "INVOKE", ordinal = 0, shift = At.Shift.BEFORE, target =
        "Lmezz/jei/gui/startup/OverlayHelper;createBookmarkOverlay(Lmezz/jei/api/runtime/IIngredientManager;Lmezz/jei/api/runtime/IScreenHelper;Lmezz/jei/gui/bookmarks/BookmarkList;Lmezz/jei/common/transfer/RecipeTransferService;Lmezz/jei/gui/overlay/ingredients/IIngredientGridSource;Lmezz/jei/common/input/IInternalKeyMappings;Lmezz/jei/common/config/IIngredientGridConfig;Lmezz/jei/common/config/IIngredientFilterConfig;Lmezz/jei/common/config/IClientConfig;Lmezz/jei/common/config/IClientToggleState;Lmezz/jei/common/network/IConnectionToServer;Lmezz/jei/common/gui/textures/Textures;Lmezz/jei/api/helpers/IColorHelper;)Lmezz/jei/gui/overlay/bookmarks/BookmarkOverlay;"), remap = false)
    private static void jetoptimizer$gateBookmarkOverlay(CallbackInfoReturnable<?> callbackInfo) {
        jetoptimizer$gate("bookmark overlay");
    }

    @Inject(method = "start", at = @At(value = "INVOKE", ordinal = 0, shift = At.Shift.BEFORE, target =
        "Lmezz/jei/gui/recipes/RecipesGui;.<init>(Lmezz/jei/api/recipe/IRecipeManager;Lmezz/jei/api/runtime/IIngredientManager;Lmezz/jei/common/transfer/RecipeTransferService;Lmezz/jei/common/input/IInternalKeyMappings;Lmezz/jei/api/recipe/IFocusFactory;Lmezz/jei/gui/bookmarks/BookmarkList;Lmezz/jei/gui/overlay/bookmarks/history/LookupHistory;Lmezz/jei/api/helpers/IGuiHelper;Lmezz/jei/gui/bookmarks/BookmarkFactory;Lmezz/jei/gui/util/FocusUtil;)V"), remap = false)
    private static void jetoptimizer$gateRecipesGui(CallbackInfoReturnable<?> callbackInfo) {
        jetoptimizer$gate("recipes gui");
    }

    @Inject(method = "start", at = @At(value = "INVOKE", ordinal = 0, shift = At.Shift.BEFORE, target =
        "Lmezz/jei/gui/input/ClientInputHandler;.<init>(Ljava/util/List;Lmezz/jei/gui/input/handlers/ChatLinkInputHandler;Lmezz/jei/common/input/handlers/UserInputRouter;Lmezz/jei/gui/input/handlers/DragRouter;Lmezz/jei/common/input/IInternalKeyMappings;Lmezz/jei/api/runtime/IScreenHelper;)V"), remap = false)
    private static void jetoptimizer$gateInputHandlers(CallbackInfoReturnable<?> callbackInfo) {
        jetoptimizer$gate("input handlers and router");
    }

    @Inject(method = "start", at = @At("RETURN"), remap = false)
    private static void jetoptimizer$finishGuiRuntime(CallbackInfoReturnable<?> callbackInfo) {
        if (jetoptimizer$openGate != null && jetoptimizer$openGateStartedAt >= 0L) {
            JETOptimizerProfiler.recordGuiRuntimeGate(jetoptimizer$openGate, System.nanoTime() - jetoptimizer$openGateStartedAt);
            jetoptimizer$openGate = null;
            jetoptimizer$openGateStartedAt = -1L;
        }
        JETOptimizerProfiler.finishStage("JEI GUI runtime construction");
    }
}