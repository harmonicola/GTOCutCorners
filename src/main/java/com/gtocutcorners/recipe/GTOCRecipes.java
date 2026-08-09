package com.gtocutcorners.recipe;

import com.gtocutcorners.GTOCutCorners;

import static com.gtocutcorners.recipe.RecipeHelper.*;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Recipe definitions.
 */
public class GTOCRecipes {

    private static final AtomicBoolean DONE = new AtomicBoolean(false);

    public static void register() {
        if (!DONE.compareAndSet(false, true)) {
            GTOCutCorners.jlog("[GTOCRecipes] SKIP (already registered)");
            return;
        }
        GTOCutCorners.jlog("[GTOCRecipes] registering...");
        registerKirin();
        registerPlatinum();
        registerRareEarth();
        GTOCutCorners.jlog("[GTOCRecipes] done");
    }

    private static void registerKirin() {
        assembler("ASSEMBLER_RECIPES", "kirin_rocket_t1",
            si("ad_astra:rocket_nose_cone", "ad_astra:rocket_fin", "ad_astra:steel_plate"),
            ci(1, 4, 16),
            "ad_astra:tier_1_rocket", 1,
            480, 600);
    }

    private static void registerPlatinum() {
        generic("CENTRIFUGE_RECIPES", "pgm_all_in_one",
            si("gtceu:cooperite_dust"), ci(6),
            si("gtceu:platinum_dust", "gtceu:palladium_dust", "gtceu:iridium_dust",
               "gtceu:rhodium_dust", "gtceu:ruthenium_dust", "gtceu:osmium_dust"),
            ci(12, 12, 12, 12, 12, 12),
            480, 1);
    }

    private static final String[][] RE = {
        {"re_lanthanum",    "gtceu:lanthanum_dust"},
        {"re_cerium",       "gtceu:cerium_dust"},
        {"re_neodymium",    "gtceu:neodymium_dust"},
        {"re_samarium",     "gtceu:samarium_dust"},
        {"re_europium",     "gtceu:europium_dust"},
        {"re_praseodymium", "gtceu:praseodymium_dust"},
        {"re_gadolinium",   "gtceu:gadolinium_dust"},
        {"re_terbium",      "gtceu:terbium_dust"},
        {"re_dysprosium",   "gtceu:dysprosium_dust"},
        {"re_holmium",      "gtceu:holmium_dust"},
        {"re_erbium",       "gtceu:erbium_dust"},
        {"re_thulium",      "gtceu:thulium_dust"},
        {"re_ytterbium",    "gtceu:ytterbium_dust"},
        {"re_scandium",     "gtceu:scandium_dust"},
        {"re_lutetium",     "gtceu:lutetium_dust"},
        {"re_yttrium",      "gtceu:yttrium_dust"},
        {"re_promethium",   "gtceu:promethium_dust"},
    };

    private static void registerRareEarth() {
        for (int i = 0; i < RE.length; i++) {
            genericWithCircuit("CENTRIFUGE_RECIPES", RE[i][0],
                si("gtceu:monazite_dust"), ci(1),
                si(RE[i][1]), ci(12),
                1920, 1, i + 1);
        }
        GTOCutCorners.jlog("[GTOCRecipes] " + RE.length + " rare earth recipes OK");
    }
}
