package dev.jetoptimizer.mixin;

import dev.jetoptimizer.JETOptimizerProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "mezz.jei.library.load.PluginLoader", remap = false)
@SuppressWarnings("unused")
abstract class PluginLoaderMixin {
    @Inject(method = "registerSubtypes", at = @At("HEAD"), remap = false)
    private static void jetoptimizer$beginSubtypeRegistration(CallbackInfoReturnable<?> callbackInfo) {
        JETOptimizerProfiler.beginStage("Subtype registration");
    }

    @Inject(method = "registerSubtypes", at = @At("RETURN"), remap = false)
    private static void jetoptimizer$finishSubtypeRegistration(CallbackInfoReturnable<?> callbackInfo) {
        JETOptimizerProfiler.finishStage("Subtype registration");
    }

    @Inject(method = "registerIngredients", at = @At("HEAD"), remap = false)
    private static void jetoptimizer$beginIngredientRegistration(CallbackInfoReturnable<?> callbackInfo) {
        JETOptimizerProfiler.beginStage("Ingredient registration");
    }

    @Inject(method = "registerIngredients", at = @At("RETURN"), remap = false)
    private static void jetoptimizer$finishIngredientRegistration(CallbackInfoReturnable<?> callbackInfo) {
        JETOptimizerProfiler.finishStage("Ingredient registration");
    }

    @Inject(method = "registerModAliases", at = @At("HEAD"), remap = false)
    private static void jetoptimizer$beginModAliasRegistration(CallbackInfoReturnable<?> callbackInfo) {
        JETOptimizerProfiler.beginStage("Mod alias registration");
    }

    @Inject(method = "registerModAliases", at = @At("RETURN"), remap = false)
    private static void jetoptimizer$finishModAliasRegistration(CallbackInfoReturnable<?> callbackInfo) {
        JETOptimizerProfiler.finishStage("Mod alias registration");
    }

    @Inject(method = "createSearchStorageFactory", at = @At("HEAD"), remap = false)
    private static void jetoptimizer$beginSearchStorageFactory(CallbackInfoReturnable<?> callbackInfo) {
        JETOptimizerProfiler.beginStage("Search storage factory registration");
    }

    @Inject(method = "createSearchStorageFactory", at = @At("RETURN"), remap = false)
    private static void jetoptimizer$finishSearchStorageFactory(CallbackInfoReturnable<?> callbackInfo) {
        JETOptimizerProfiler.finishStage("Search storage factory registration");
    }

    @Inject(method = "createRecipeManager", at = @At("HEAD"), remap = false)
    private static void jetoptimizer$beginRecipeManagerRegistration(CallbackInfoReturnable<?> callbackInfo) {
        JETOptimizerProfiler.beginStage("Recipe and category registration");
    }

    @Inject(method = "createRecipeManager", at = @At("RETURN"), remap = false)
    private static void jetoptimizer$finishRecipeManagerRegistration(CallbackInfoReturnable<?> callbackInfo) {
        JETOptimizerProfiler.finishStage("Recipe and category registration");
    }

    @Inject(method = "createRecipeTransferManager", at = @At("HEAD"), remap = false)
    private static void jetoptimizer$beginTransferRegistration(CallbackInfoReturnable<?> callbackInfo) {
        JETOptimizerProfiler.beginStage("Recipe transfer registration");
    }

    @Inject(method = "createRecipeTransferManager", at = @At("RETURN"), remap = false)
    private static void jetoptimizer$finishTransferRegistration(CallbackInfoReturnable<?> callbackInfo) {
        JETOptimizerProfiler.finishStage("Recipe transfer registration");
    }

    @Inject(method = "createGuiScreenHelper", at = @At("HEAD"), remap = false)
    private static void jetoptimizer$beginGuiHandlerRegistration(CallbackInfoReturnable<?> callbackInfo) {
        JETOptimizerProfiler.beginStage("GUI handler registration");
    }

    @Inject(method = "createGuiScreenHelper", at = @At("RETURN"), remap = false)
    private static void jetoptimizer$finishGuiHandlerRegistration(CallbackInfoReturnable<?> callbackInfo) {
        JETOptimizerProfiler.finishStage("GUI handler registration");
    }
}
