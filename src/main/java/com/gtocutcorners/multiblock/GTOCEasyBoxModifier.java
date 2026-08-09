package com.gtocutcorners.multiblock;

import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.handler.IRecipeHandlerHolder;
import com.gregtechceu.gtceu.api.recipe.handler.RecipeHandlerUnit;
import com.gregtechceu.gtceu.api.recipe.modifier.ParallelLogic;

import java.util.List;

/**
 * Easy Box recipe modifier, ported from gtecore's steam-op behaviour:
 * the machine runs at massive parallel (up to 1e9) based on how many times the
 * inputs can be supplied, multiplying inputs/outputs accordingly, with the
 * runtime duration forced to 1 tick. Steam powers the machine via the STEAM
 * ability in the structure.
 */
public final class GTOCEasyBoxModifier {

    private static final long MAX_PARALLEL = 1_000_000_000L;

    private GTOCEasyBoxModifier() {
    }

    public static GTRecipe applyModifier(IRecipeHandlerHolder holder, RecipeHandlerUnit unit, GTRecipe recipe) {
        long parallel = Math.max(1, ParallelLogic.getMaxParallelAmount(holder, unit, recipe, MAX_PARALLEL));
        if (parallel > 1) {
            scale(recipe.itemInputs, parallel);
            scale(recipe.itemOutputs, parallel);
            scale(recipe.fluidInputs, parallel);
            scale(recipe.fluidOutputs, parallel);
        }
        recipe.duration = 1;
        return recipe;
    }

    private static void scale(List<? extends Content<?>> contents, long factor) {
        for (Content<?> content : contents) {
            content.amount *= factor;
        }
    }
}
