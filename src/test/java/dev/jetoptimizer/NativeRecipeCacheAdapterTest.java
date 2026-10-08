package dev.jetoptimizer;

import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.library.gui.helpers.CraftingGridHelper;
import mezz.jei.library.plugins.vanilla.crafting.CraftingRecipeCategory;
import mezz.jei.library.plugins.vanilla.crafting.CraftingCategoryExtension;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.*;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.common.ingredients.TypedIngredient;
import mezz.jei.library.ingredients.RecipeIngredientSupplier;
import mezz.jei.library.plugins.vanilla.ingredients.ItemStackHelper;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class NativeRecipeCacheAdapterTest {
    @TempDir Path directory;

    static IIngredientManager manager(ItemStackHelper helper) {
        return (IIngredientManager) Proxy.newProxyInstance(IIngredientManager.class.getClassLoader(), new Class<?>[]{IIngredientManager.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getIngredientHelper")) return helper;
                    throw new UnsupportedOperationException(method.getName());
                });
    }
    static NativeRecipeCacheAdapter.Encoder encoder() {
        return new NativeRecipeCacheAdapter.Encoder(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY), manager(new ItemStackHelper(null, null)));
    }
    private static NativeRecipeCacheAdapter.Input input(Map<Integer, List<String>> roles) {
        return new NativeRecipeCacheAdapter.Input(new PreparedRecipeStore.Id("minecraft:crafting", "example:test"), "{}",
                Map.of("jei", "pinned"), roles, List.of("payload"));
    }

    static CraftingRecipeCategory crafting() {
        var helper = (IGuiHelper) Proxy.newProxyInstance(IGuiHelper.class.getClassLoader(), new Class<?>[]{IGuiHelper.class},
                (proxy, method, arguments) -> method.getName().equals("createCraftingGridHelper") ? CraftingGridHelper.INSTANCE : null);
        var category = new CraftingRecipeCategory(helper);
        category.addExtension(CraftingRecipe.class, new CraftingCategoryExtension());
        return category;
    }
    static RecipeHolder<ShapelessRecipe> recipe(ItemStack output, Ingredient ingredient) {
        return new RecipeHolder<>(ResourceLocation.fromNamespaceAndPath("example", "test"),
                new ShapelessRecipe("group", CraftingBookCategory.MISC, output, NonNullList.of(Ingredient.EMPTY, ingredient)));
    }
    static Map<String, String> manifest() {
        return Map.of("minecraft", "mc", "neoforge", "neo", "jei", "pinned", "jetoptimizer", "optimizer", "example", "jar-v1");
    }

    @Test void actualRecipePayloadAndResolvedIngredientChangesInvalidateNativeKeys() throws Exception {
        var context = encoder(); var category = crafting();
        var first = NativeRecipeCacheAdapter.prepare(recipe(new ItemStack(Items.DIAMOND), Ingredient.of(Items.COAL)), category, context, manifest());
        var unchanged = NativeRecipeCacheAdapter.prepare(recipe(new ItemStack(Items.DIAMOND), Ingredient.of(Items.COAL)), category, context, manifest());
        var changedResult = NativeRecipeCacheAdapter.prepare(recipe(new ItemStack(Items.DIAMOND, 3), Ingredient.of(Items.COAL)), category, context, manifest());
        var changedInput = NativeRecipeCacheAdapter.prepare(recipe(new ItemStack(Items.DIAMOND), Ingredient.of(Items.CHARCOAL)), category, context, manifest());
        assertNotNull(first); assertEquals(first.fingerprint(), unchanged.fingerprint());
        assertNotEquals(first.fingerprint(), changedResult.fingerprint());
        assertNotEquals(first.fingerprint(), changedInput.fingerprint());
        assertEquals(Set.of("minecraft", "neoforge", "jei", "jetoptimizer", "example"), first.dependencies().keySet());
        var unavailable = new HashMap<>(manifest()); unavailable.put("example", "version:unavailable");
        assertThrows(IllegalArgumentException.class, () -> NativeRecipeCacheAdapter.prepare(recipe(new ItemStack(Items.DIAMOND), Ingredient.of(Items.COAL)), category, context, unavailable));
    }

    @Test void customCraftingExtensionsCannotReuseNativeRecipeEntries() throws Exception {
        var helper = (IGuiHelper) Proxy.newProxyInstance(IGuiHelper.class.getClassLoader(), new Class<?>[]{IGuiHelper.class},
                (proxy, method, arguments) -> method.getName().equals("createCraftingGridHelper") ? CraftingGridHelper.INSTANCE : null);
        var category = new CraftingRecipeCategory(helper);
        category.addExtension(CraftingRecipe.class, new CraftingCategoryExtension() {});
        assertNull(NativeRecipeCacheAdapter.prepare(recipe(new ItemStack(Items.DIAMOND), Ingredient.of(Items.COAL)), category, encoder(), manifest()));
    }

    @Test void diskRoundTripRebindsComponentsCountsAndIngredientRoles() throws Exception {
        var old = encoder();
        ItemStack output = new ItemStack(Items.DIAMOND, 3);
        output.set(DataComponents.CUSTOM_NAME, Component.literal("Prepared result"));
        var inputStack = new ItemStack(Items.IRON_INGOT, 2);
        Map<RecipeIngredientRole, List<ITypedIngredient<?>>> roles = new EnumMap<>(RecipeIngredientRole.class);
        roles.put(RecipeIngredientRole.INPUT, List.of(TypedIngredient.createUnvalidated(VanillaTypes.ITEM_STACK, inputStack)));
        roles.put(RecipeIngredientRole.OUTPUT, List.of(TypedIngredient.createUnvalidated(VanillaTypes.ITEM_STACK, output)));
        var original = new RecipeIngredientSupplier(roles);
        var prepared = input(Map.of(RecipeIngredientRole.INPUT.ordinal(), List.of(old.encode(inputStack)),
                RecipeIngredientRole.OUTPUT.ordinal(), List.of(old.encode(output))));
        var snapshot = NativeRecipeCacheAdapter.capture(prepared, prepared.fingerprint(), original, old);
        assertNotNull(snapshot);
        PreparedRecipeStore store = new PreparedRecipeStore(); store.putAll(List.of(snapshot));
        Path file = directory.resolve("native.gz"); store.save(file);
        var fresh = encoder();
        var restored = NativeRecipeCacheAdapter.restore(PreparedRecipeStore.load(file).get(snapshot.id(), snapshot.fingerprint()), fresh);
        Arrays.stream(RecipeIngredientRole.values()).forEach(role -> {
            var expected = original.getIngredients(role); var actual = restored.getIngredients(role);
            assertEquals(expected.size(), actual.size());
            for (int index = 0; index < expected.size(); index++) {
                assertTrue(ItemStack.matches(expected.get(index).getItemStack().orElseThrow(), actual.get(index).getItemStack().orElseThrow()));
            }
        });
        assertTrue(restored.getFocusLinks().isEmpty());
    }

    @Test void sharedRoleBlocksAndTypedIngredientsAreReusedWithinOneRuntime() {
        var context = encoder(); String json = context.encode(new ItemStack(Items.COAL));
        var prepared = input(Map.of(RecipeIngredientRole.RENDER_ONLY.ordinal(), List.of(json)));
        var entry = new PreparedRecipeStore.Entry(prepared.id(), prepared.fingerprint(), "{}", prepared.dependencies(), prepared.expectedRoles());
        var first = NativeRecipeCacheAdapter.restore(entry, context);
        var second = NativeRecipeCacheAdapter.restore(entry, context);
        assertSame(first.getIngredients(RecipeIngredientRole.RENDER_ONLY), second.getIngredients(RecipeIngredientRole.RENDER_ONLY));
        assertTrue(context.shared(first.getIngredients(RecipeIngredientRole.RENDER_ONLY).getFirst()));
        var fresh = NativeRecipeCacheAdapter.restore(entry, encoder());
        assertNotSame(first.getIngredients(RecipeIngredientRole.RENDER_ONLY).getFirst(), fresh.getIngredients(RecipeIngredientRole.RENDER_ONLY).getFirst());
    }

    @Test void partialLayoutsAndFocusLinkedLayoutsAreNeverCaptured() {
        var context = encoder(); String json = context.encode(new ItemStack(Items.COAL));
        var prepared = input(Map.of(RecipeIngredientRole.INPUT.ordinal(), List.of(json)));
        assertNull(NativeRecipeCacheAdapter.capture(prepared, prepared.fingerprint(), new RecipeIngredientSupplier(Map.of()), context));
        var link = new RecipeIngredientSupplier.FocusLink(List.of());
        assertNull(NativeRecipeCacheAdapter.capture(prepared, prepared.fingerprint(), new RecipeIngredientSupplier(Map.of(), List.of(link)), context));
    }

    @Test void customItemHelpersRequireTheirOriginalCallbacks() {
        ItemStackHelper custom = new ItemStackHelper(null, null) {};
        assertThrows(IllegalArgumentException.class, () -> new NativeRecipeCacheAdapter.Encoder(
                RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY), manager(custom)));
    }
}
