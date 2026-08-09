package com.gtocutcorners.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.gregtechceu.gtceu.api.recipe.GTRecipeDefinition;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.api.recipe.ingredient.ItemIngredient;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import com.gtocutcorners.GTOCutCorners;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dumps GTO content into a reusable JSON database:
 * items (+tags/+NBT metadata), fluids (+tags), GT recipes and vanilla recipes.
 *
 * <p>Enable with {@code "dumpContent": true} in {@code config/gtocutcorners.json}
 * and start a world once. Output lands in
 * {@code config/gtocutcorners/content_dump/}.</p>
 */
public final class ContentDump {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private ContentDump() {
    }

    public static void dump(MinecraftServer server) {
        Path dir = FMLPaths.GAMEDIR.get().resolve("config/gtocutcorners/content_dump");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            GTOCutCorners.jlog("[ContentDump] cannot create output dir: " + e);
            return;
        }
        write(dir.resolve("items.json"), dumpItems());
        write(dir.resolve("fluids.json"), dumpFluids());
        write(dir.resolve("gt_recipes.json"), dumpGtRecipes());
        write(dir.resolve("vanilla_recipes.json"), dumpVanillaRecipes(server));
        GTOCutCorners.jlog("[ContentDump] done -> " + dir);
    }

    private static void write(Path file, Object data) {
        try {
            Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
            GTOCutCorners.jlog("[ContentDump] wrote " + file.getFileName());
        } catch (IOException e) {
            GTOCutCorners.jlog("[ContentDump] write failed " + file + ": " + e);
        }
    }

    private static List<Map<String, Object>> dumpItems() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Item item : ForgeRegistries.ITEMS) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            if (id == null) {
                continue;
            }
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", id.toString());
            e.put("registryId", BuiltInRegistries.ITEM.getId(item));
            e.put("class", item.getClass().getName());
            e.put("maxStack", item.getMaxStackSize());
            ItemStack stack = new ItemStack(item);
            e.put("damageable", item.isDamageable(stack));
            List<String> tags = new ArrayList<>();
            for (TagKey<Item> t : item.builtInRegistryHolder().tags().toList()) {
                tags.add(t.location().toString());
            }
            Collections.sort(tags);
            e.put("tags", tags);
            if (stack.hasTag()) {
                e.put("nbt", stack.getTag().toString());
            }
            list.add(e);
        }
        return list;
    }

    private static List<Map<String, Object>> dumpFluids() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Fluid fluid : ForgeRegistries.FLUIDS) {
            ResourceLocation id = ForgeRegistries.FLUIDS.getKey(fluid);
            if (id == null) {
                continue;
            }
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", id.toString());
            e.put("registryId", BuiltInRegistries.FLUID.getId(fluid));
            e.put("class", fluid.getClass().getName());
            List<String> tags = new ArrayList<>();
            for (TagKey<Fluid> t : fluid.builtInRegistryHolder().tags().toList()) {
                tags.add(t.location().toString());
            }
            Collections.sort(tags);
            e.put("tags", tags);
            list.add(e);
        }
        return list;
    }

    private static List<Map<String, Object>> dumpGtRecipes() {
        List<Map<String, Object>> recipes = new ArrayList<>();
        GTRegistries.RECIPE_TYPES.values().forEach(type -> {
            type.recipes.forEach((rid, def) -> {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("id", rid.toString());
                e.put("type", type.registryName.toString());
                e.put("eut", def.eut);
                e.put("duration", def.duration);
                e.put("tier", def.tier);
                e.put("inputs", dumpContents(def.itemInputs, def.fluidInputs));
                e.put("outputs", dumpContents(def.itemOutputs, def.fluidOutputs));
                List<String> conditions = new ArrayList<>();
                for (var c : def.conditions) {
                    conditions.add(c.getClass().getSimpleName());
                }
                e.put("conditions", conditions);
                e.put("data", def.data.toString());
                recipes.add(e);
            });
        });
        return recipes;
    }

    private static List<Map<String, Object>> dumpContents(
            List<Content<ItemIngredient>> items, List<Content<FluidIngredient>> fluids) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Content<ItemIngredient> c : items) {
            ItemStack s = c.inner.getInnerItemStack();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "item");
            m.put("id", s.getItem() == Items.AIR
                ? "minecraft:air"
                : ForgeRegistries.ITEMS.getKey(s.getItem()).toString());
            m.put("count", c.amount != 0 ? c.amount : s.getCount());
            if (s.hasTag()) {
                m.put("nbt", s.getTag().toString());
            }
            if (c.chance != Content.MAX_CHANCE) {
                m.put("chance", c.chance);
            }
            list.add(m);
        }
        for (Content<FluidIngredient> c : fluids) {
            FluidStack[] arr = c.inner.getStacks();
            if (arr.length == 0) {
                continue;
            }
            FluidStack s = arr[0];
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "fluid");
            m.put("id", ForgeRegistries.FLUIDS.getKey(s.getFluid()).toString());
            m.put("amount", c.amount != 0 ? c.amount : s.getAmount());
            if (c.chance != Content.MAX_CHANCE) {
                m.put("chance", c.chance);
            }
            list.add(m);
        }
        return list;
    }

    private static List<Map<String, Object>> dumpVanillaRecipes(MinecraftServer server) {
        RegistryAccess registryAccess = server.registryAccess();
        List<Map<String, Object>> recipes = new ArrayList<>();
        for (Recipe<?> recipe : server.overworld().getRecipeManager().getRecipes()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", recipe.getId().toString());
            e.put("type", recipe.getType().toString());
            List<List<String>> ingredients = new ArrayList<>();
            for (Ingredient ing : recipe.getIngredients()) {
                List<String> ids = new ArrayList<>();
                for (ItemStack s : ing.getItems()) {
                    ids.add(ForgeRegistries.ITEMS.getKey(s.getItem()).toString());
                }
                ingredients.add(ids);
            }
            e.put("ingredients", ingredients);
            ItemStack result = recipe.getResultItem(registryAccess);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", ForgeRegistries.ITEMS.getKey(result.getItem()).toString());
            r.put("count", result.getCount());
            if (result.hasTag()) {
                r.put("nbt", result.getTag().toString());
            }
            e.put("result", r);
            recipes.add(e);
        }
        return recipes;
    }
}
