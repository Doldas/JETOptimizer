package dev.jetoptimizer;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.DynamicOps;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.library.gui.helpers.CraftingGridHelper;
import mezz.jei.library.plugins.vanilla.crafting.CraftingCategoryExtension;
import mezz.jei.library.plugins.vanilla.ingredients.ItemStackHelper;
import mezz.jei.library.util.RecipeUtil;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.common.ingredients.TypedIngredient;
import mezz.jei.library.ingredients.RecipeIngredientSupplier;
import mezz.jei.library.plugins.vanilla.cooking.AbstractCookingCategory;
import mezz.jei.library.plugins.vanilla.crafting.CraftingRecipeCategory;
import mezz.jei.library.recipes.CraftingExtensionHelper;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;

import java.lang.reflect.Field;
import java.util.*;

/** Source-specific adapters. Arbitrary categories, recipe subclasses and crafting extensions bypass. */
final class NativeRecipeCacheAdapter {
    private static final Map<String, String> CATEGORIES = Map.of(
            "mezz.jei.library.plugins.vanilla.cooking.FurnaceSmeltingCategory", "net.minecraft.world.item.crafting.SmeltingRecipe",
            "mezz.jei.library.plugins.vanilla.cooking.BlastingCategory", "net.minecraft.world.item.crafting.BlastingRecipe",
            "mezz.jei.library.plugins.vanilla.cooking.SmokingCategory", "net.minecraft.world.item.crafting.SmokingRecipe",
            "mezz.jei.library.plugins.vanilla.cooking.CampfireCookingCategory", "net.minecraft.world.item.crafting.CampfireCookingRecipe",
            "mezz.jei.library.plugins.vanilla.stonecutting.StoneCuttingRecipeCategory", "net.minecraft.world.item.crafting.StonecutterRecipe");
    private static final ClassValue<Boolean> UNMODIFIED = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            return Arrays.stream(type.getDeclaredMethods()).flatMap(method -> Arrays.stream(method.getDeclaredAnnotations()))
                    .noneMatch(annotation -> annotation.annotationType().getName().equals("org.spongepowered.asm.mixin.transformer.meta.MixinMerged"));
        }
    };
    private static final class Fields {
        static final Field EXTENSIONS = field(CraftingRecipeCategory.class, "extendableHelper");
        static final Field FUELS = field(AbstractCookingCategory.class, "furnaceFuels");
    }

    /** Only detached strings are submitted to worker threads. */
    record Input(PreparedRecipeStore.Id id, String recipeJson, Map<String, String> dependencies,
                 Map<Integer, List<String>> expectedRoles, List<String> fields) {
        String fingerprint() { return RecipeCacheHash.strings(fields); }
    }

    private NativeRecipeCacheAdapter() {}

    static boolean supportedCategory(IRecipeCategory<?> category) {
        return (category.getClass() == CraftingRecipeCategory.class || CATEGORIES.containsKey(category.getClass().getName()))
                && java.util.stream.Stream.of(category.getClass(), AbstractCookingCategory.class, CraftingGridHelper.class,
                        CraftingCategoryExtension.class, ItemStackHelper.class, TypedIngredient.class, RecipeUtil.class)
                .allMatch(UNMODIFIED::get);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static Input prepare(Object value, IRecipeCategory<?> category, Encoder encoder, Map<String, String> manifest)
            throws ReflectiveOperationException {
        if (!(value instanceof RecipeHolder<?> holder) || !supported(holder, category)) return null;
        Recipe recipe = holder.value();
        String payload = ((JsonElement) recipe.getSerializer().codec().codec().encodeStart(encoder.ops, recipe).getOrThrow()).toString();
        List<String> fields = new ArrayList<>();
        fields.add("native-supplier-v1"); fields.add(category.getClass().getName()); fields.add(payload);
        Set<String> dependencies = new TreeSet<>(List.of("minecraft", "neoforge", "jei", "jetoptimizer", holder.id().getNamespace()));
        List<String> inputs = new ArrayList<>();
        // Include resolved alternatives, not just tag names. Their order affects JEI's ingredient ordering.
        for (Ingredient ingredient : holder.value().getIngredients()) {
            List<String> alternatives = Arrays.stream(ingredient.getItems()).map(stack -> {
                dependencies.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace());
                return encoder.encode(stack);
            }).toList();
            fields.add(Integer.toString(alternatives.size()));
            fields.addAll(alternatives);
            alternatives.stream().filter(valueJson -> !valueJson.isEmpty()).forEach(inputs::add);
        }
        ItemStack output = holder.value().getResultItem(encoder.registryAccess);
        dependencies.add(BuiltInRegistries.ITEM.getKey(output.getItem()).getNamespace());
        String outputJson = encoder.encode(output);
        fields.add(outputJson);
        // Fuel lists are category-owned and immutable for this registration phase; snapshot once.
        dependencies.addAll(encoder.fuelNamespaces(category));
        fields.add(encoder.fuelSignature(category));
        Map<String, String> hashes = new TreeMap<>();
        dependencies.forEach(id -> {
            String hash = manifest.get(id);
            if (hash != null) {
                if (hash.endsWith(":unavailable")) throw new IllegalArgumentException("Mod fingerprint unavailable: " + id);
                hashes.put(id, hash);
            }
            else if (Set.of("minecraft", "neoforge", "jei", "jetoptimizer").contains(id)) {
                throw new IllegalArgumentException("Required mod fingerprint unavailable: " + id);
            }
        });
        hashes.forEach((id, hash) -> { fields.add(id); fields.add(hash); });
        Map<Integer, List<String>> expected = new TreeMap<>();
        if (!inputs.isEmpty()) expected.put(RecipeIngredientRole.INPUT.ordinal(), encoder.intern(inputs));
        if (!outputJson.isEmpty()) expected.put(RecipeIngredientRole.OUTPUT.ordinal(), List.of(outputJson));
        List<String> fuelBlock = encoder.fuelBlock(category);
        if (!fuelBlock.isEmpty()) expected.put(RecipeIngredientRole.RENDER_ONLY.ordinal(), fuelBlock);
        return new Input(new PreparedRecipeStore.Id(category.getRecipeType().getUid().toString(), holder.id().toString()),
                payload, Map.copyOf(hashes), Map.copyOf(expected), List.copyOf(fields));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static boolean supported(RecipeHolder<?> holder, IRecipeCategory<?> category) throws IllegalAccessException {
        if (!UNMODIFIED.get(holder.value().getClass())) return false;
        String name = category.getClass().getName();
        if (name.equals(CraftingRecipeCategory.class.getName())) {
            Class<?> recipeClass = holder.value().getClass();
            if (recipeClass != ShapedRecipe.class && recipeClass != ShapelessRecipe.class) return false;
            CraftingExtensionHelper helper = (CraftingExtensionHelper) Fields.EXTENSIONS.get(category);
            return helper.getOptionalRecipeExtension((RecipeHolder) holder)
                    .filter(extension -> extension.getClass().getName().equals("mezz.jei.library.plugins.vanilla.crafting.CraftingCategoryExtension"))
                    .isPresent();
        }
        return Objects.equals(CATEGORIES.get(name), holder.value().getClass().getName()) && !holder.value().isSpecial();
    }

    static PreparedRecipeStore.Entry capture(Input input, String fingerprint, RecipeIngredientSupplier supplier, Encoder encoder) {
        // Supported native layouts have no focus links. Preserve the original path if an extension adds any.
        if (!supplier.getFocusLinks().isEmpty()) return null;
        Map<Integer, List<String>> roles = new TreeMap<>();
        Arrays.stream(RecipeIngredientRole.values()).forEach(role -> {
            List<String> ingredients = supplier.getIngredients(role).stream().map(typed -> {
                ItemStack stack = typed.getIngredient(VanillaTypes.ITEM_STACK)
                        .orElseThrow(() -> new IllegalArgumentException("Unsupported ingredient type"));
                return encoder.encode(stack);
            }).toList();
            if (!ingredients.isEmpty()) roles.put(role.ordinal(), encoder.intern(ingredients));
        });
        // Do not persist failed/partial callbacks or a layout changed by a third-party modification.
        if (!roles.equals(input.expectedRoles())) return null;
        return new PreparedRecipeStore.Entry(input.id(), fingerprint, input.recipeJson(), input.dependencies(), input.expectedRoles());
    }

    static RecipeIngredientSupplier restore(PreparedRecipeStore.Entry entry, Encoder encoder) {
        Map<RecipeIngredientRole, List<ITypedIngredient<?>>> roles = new EnumMap<>(RecipeIngredientRole.class);
        entry.roles().forEach((role, block) -> roles.put(RecipeIngredientRole.values()[role], encoder.decodeBlock(block)));
        return new RecipeIngredientSupplier(roles);
    }

    private static Field field(Class<?> type, String name) {
        try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
        catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }

    /** Client-thread, runtime-scoped codecs and shared blocks. Cleared before the runtime is released. */
    static final class Encoder {
        private record StackKey(Item item, int count, DataComponentPatch patch) {}
        final RegistryAccess registryAccess;
        final DynamicOps<JsonElement> ops;
        private final IIngredientManager manager;
        private long encodedBytes, blockReferences;
        private final Map<StackKey, String> encoded = new HashMap<>();
        private final Map<String, ITypedIngredient<?>> decoded = new HashMap<>();
        private final Set<ITypedIngredient<?>> sharedIngredients = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Map<ITypedIngredient<?>, Object> recipeUids = new IdentityHashMap<>();
        private final Map<List<String>, List<ITypedIngredient<?>>> decodedBlocks = new HashMap<>();
        private final Map<List<String>, List<String>> blocks = new HashMap<>();
        private final Map<IRecipeCategory<?>, List<ItemStack>> fuels = new IdentityHashMap<>();
        private final Map<IRecipeCategory<?>, String> fuelSignatures = new IdentityHashMap<>();
        private final Map<IRecipeCategory<?>, List<String>> fuelBlocks = new IdentityHashMap<>();
        private final Map<IRecipeCategory<?>, Set<String>> fuelNamespaces = new IdentityHashMap<>();

        Encoder(RegistryAccess access, IIngredientManager manager) {
            registryAccess = access;
            ops = access.createSerializationContext(JsonOps.INSTANCE);
            this.manager = manager;
            if (!manager.getIngredientHelper(VanillaTypes.ITEM_STACK).getClass().getName()
                    .equals("mezz.jei.library.plugins.vanilla.ingredients.ItemStackHelper")) {
                throw new IllegalArgumentException("Custom item ingredient helper requires its original callbacks");
            }
        }

        void snapshotCategory(IRecipeCategory<?> category) {
            // Refresh at each registration batch so another plugin's earlier changes are observed.
            fuels.remove(category); fuelBlocks.remove(category); fuelNamespaces.remove(category); fuelSignatures.remove(category);
        }
        String encode(ItemStack stack) {
            if (stack.isEmpty()) return "";
            return encoded.computeIfAbsent(new StackKey(stack.getItem(), stack.getCount(), stack.getComponentsPatch()),
                    key -> {
                        String json = ItemStack.CODEC.encodeStart(ops, stack).getOrThrow().toString();
                        if (encoded.size() >= 250_000 || json.length() > PreparedRecipeStore.MAX_TEXT_BYTES
                                || encodedBytes + 2L * json.length() > 64L * 1024 * 1024) {
                            throw new IllegalArgumentException("Runtime recipe ingredient encoding budget exceeded");
                        }
                        encodedBytes += 2L * json.length();
                        return json;
                    });
        }
        List<String> intern(List<String> block) {
            return blocks.computeIfAbsent(block, values -> {
                if (values.size() > PreparedRecipeStore.MAX_BLOCK_ITEMS || blocks.size() >= 250_000
                        || blockReferences + values.size() > 2_000_000) throw new IllegalArgumentException("Runtime recipe block budget exceeded");
                blockReferences += values.size();
                return List.copyOf(values);
            });
        }
        List<ITypedIngredient<?>> decodeBlock(List<String> block) {
            return decodedBlocks.computeIfAbsent(block, values -> values.stream().map(this::decode).toList());
        }
        private ITypedIngredient<?> decode(String json) {
            return decoded.computeIfAbsent(json, value -> {
                ItemStack stack = ItemStack.CODEC.parse(ops, JsonParser.parseString(value)).getOrThrow();
                ITypedIngredient<?> typed = Objects.requireNonNull(TypedIngredient.createAndFilterInvalid(manager, VanillaTypes.ITEM_STACK, stack, false),
                        "Cached ingredient is no longer valid");
                sharedIngredients.add(typed);
                return typed;
            });
        }
        boolean shared(ITypedIngredient<?> ingredient) { return sharedIngredients.contains(ingredient); }
        <T> Object recipeUid(IIngredientHelper<T> helper, ITypedIngredient<T> ingredient) {
            return recipeUids.computeIfAbsent(ingredient, key -> helper.getUid(ingredient, UidContext.Recipe));
        }
        @SuppressWarnings("unchecked")
        List<ItemStack> fuels(IRecipeCategory<?> category) {
            return fuels.computeIfAbsent(category, key -> {
                if (!(key instanceof AbstractCookingCategory<?>)) return List.of();
                try { return (List<ItemStack>) Fields.FUELS.get(key); }
                catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
            });
        }
        List<String> fuelBlock(IRecipeCategory<?> category) {
            return fuelBlocks.computeIfAbsent(category, key -> intern(fuels(key).stream().map(this::encode).filter(value -> !value.isEmpty()).toList()));
        }
        Set<String> fuelNamespaces(IRecipeCategory<?> category) {
            return fuelNamespaces.computeIfAbsent(category, key -> fuels(key).stream()
                    .map(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace()).collect(java.util.stream.Collectors.toUnmodifiableSet()));
        }
        String fuelSignature(IRecipeCategory<?> category) {
            return fuelSignatures.computeIfAbsent(category, key -> RecipeCacheHash.strings(fuelBlock(key)));
        }
    }
}
