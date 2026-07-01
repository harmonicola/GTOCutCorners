package com.gtocutcorners;

import com.gregtechceu.gtceu.api.recipe.GTRecipeDefinition;
import com.gregtechceu.gtceu.api.recipe.RecipeCondition;
import com.gregtechceu.gtceu.api.recipe.handler.IRecipeHandlerHolder;
import com.gregtechceu.gtceu.api.recipe.handler.RecipeHandlerUnit;
import net.minecraft.network.chat.Component;

public class AlwaysPassCondition extends RecipeCondition {

    public static final AlwaysPassCondition INSTANCE = new AlwaysPassCondition();

    @Override
    public boolean testCondition(IRecipeHandlerHolder holder, RecipeHandlerUnit unit, GTRecipeDefinition recipe) {
        return true;
    }

    @Override
    public Component getTooltips() {
        return Component.literal("[Field Active]");
    }
}
