package com.gtocutcorners.registry;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.registries.ForgeRegistries;
import vazkii.botania.common.crafting.ManaInfusionRecipe;

/**
 * Botania mana-pool transformations for GTOCutCorners machines.
 *
 * <p>GTO replaces RecipeManager's vanilla recipe maps with its own database, so
 * neither datapack recipes nor RecipeManager injection can reach Botania. A
 * mixin into {@code ManaPoolBlockEntity#getMatchingRecipe} calls
 * {@link #recipeFor(ItemStack)} and returns one of these recipes directly;
 * Botania's own pool logic then performs the mana check and item conversion.</p>
 */
public final class GTOCBotaniaRecipes {

    private static ManaInfusionRecipe furnaceToStone;
    private static ManaInfusionRecipe chestToSuperBuffer;

    private GTOCBotaniaRecipes() {
    }

    public static vazkii.botania.api.recipe.ManaInfusionRecipe recipeFor(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        if (stack.is(Items.FURNACE)) {
            return furnaceToStoneRecipe();
        }
        if (stack.is(Items.CHEST)) {
            return chestToSuperBufferRecipe();
        }
        return null;
    }

    public static java.util.Map<net.minecraft.resources.ResourceLocation, ManaInfusionRecipe> recipeMap() {
        java.util.Map<net.minecraft.resources.ResourceLocation, ManaInfusionRecipe> map = new java.util.HashMap<>();
        ManaInfusionRecipe stone = furnaceToStoneRecipe();
        if (stone != null) {
            map.put(stone.getId(), stone);
        }
        ManaInfusionRecipe superBuffer = chestToSuperBufferRecipe();
        if (superBuffer != null) {
            map.put(superBuffer.getId(), superBuffer);
        }
        return map;
    }

    private static synchronized ManaInfusionRecipe furnaceToStoneRecipe() {
        if (furnaceToStone == null) {
            furnaceToStone = build(
                "primitive_stone_furnace",
                "gtocore", "primitive_stone_furnace",
                Items.FURNACE, 1);
        }
        return furnaceToStone;
    }

    private static synchronized ManaInfusionRecipe chestToSuperBufferRecipe() {
        if (chestToSuperBuffer == null) {
            chestToSuperBuffer = build(
                "me_super_pattern_buffer",
                "gtocore", "me_super_pattern_buffer",
                Items.CHEST, 200);
        }
        return chestToSuperBuffer;
    }

    private static ManaInfusionRecipe build(String recipeName, String outputNs, String outputPath,
                                            net.minecraft.world.item.Item input, int mana) {
        net.minecraft.world.item.Item output = ForgeRegistries.ITEMS.getValue(
            new ResourceLocation(outputNs, outputPath));
        if (output == null) {
            return null;
        }
        return new ManaInfusionRecipe(
            new ResourceLocation("gtocutcorners", recipeName),
            new ItemStack(output),
            Ingredient.of(input),
            mana,
            "",
            null);
    }
}
