package com.gtocutcorners.data;

import com.gregtechceu.gtceu.api.recipe.handler.IO;
import com.gtocutcorners.GTOCutCorners;
import com.gtolib.api.recipe.RecipeType;
import com.gtolib.utils.register.RecipeTypeRegisterUtils;

/**
 * GTOCutCorners' own recipe types.
 *
 * <p>{@link #register()} is invoked by the generated coremod at the head of
 * GTCEu's {@code GTRecipeTypes.init()}, i.e. before the recipe-type registry is
 * frozen, mirroring how GTCEu addons register recipe types.</p>
 */
public final class GTOCRecipeTypes {

    /** Easy Box: 1 item input, up to 80 item outputs, energy input only. */
    public static RecipeType EASY_BOX;

    private static boolean registered = false;

    private GTOCRecipeTypes() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        EASY_BOX = RecipeTypeRegisterUtils.register("easy_box", "简单之盒", "multiblock")
            .setMaxIOSize(1, 80, 0, 0)
            .setEUIO(IO.IN);
        GTOCutCorners.jlog("[GTOCRecipeTypes] registered easy_box recipe type: " + EASY_BOX.registryName);
    }
}
