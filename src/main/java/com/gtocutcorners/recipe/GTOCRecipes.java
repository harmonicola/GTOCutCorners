package com.gtocutcorners.recipe;

import com.gtocutcorners.GTOCutCorners;

import static com.gtocutcorners.recipe.RecipeHelper.*;

/**
 * Recipe definitions — pure reflection path (Version A).
 */
public class GTOCRecipes {

    public static void register() {
        GTOCutCorners.jlog("[GTOCRecipes] ===== register() called =====");
        GTOCutCorners.jlog("[GTOCRecipes] classloader: " + GTOCRecipes.class.getClassLoader());
        registerKirin();
        GTOCutCorners.jlog("[GTOCRecipes] ===== register() done =====");
    }

    private static void registerKirin() {
        GTOCutCorners.jlog("[GTOCRecipes] registerKirin START");

        assembler("ASSEMBLER_RECIPES", "kirin_rocket_t1",
            si("ad_astra:rocket_nose_cone", "ad_astra:rocket_fin", "ad_astra:steel_plate"),
            ci(1, 4, 16),
            "ad_astra:tier_1_rocket", 1,
            480, 600);

        GTOCutCorners.jlog("[GTOCRecipes] registerKirin DONE");
    }
}
