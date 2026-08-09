package com.gtocutcorners.mixin;

import com.gtocutcorners.registry.GTOCBotaniaRecipes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import vazkii.botania.common.crafting.BotaniaRecipeTypes;
import vazkii.botania.common.crafting.ManaInfusionRecipe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Real recipe injection into the RecipeManager view used by Botania, JEI and
 * EMI. GTO replaced RecipeManager's backing maps with its own database, so the
 * vanilla {@code byType}/{@code getAllRecipesFor} results are augmented at
 * return time with GTOCutCorners' mana-infusion recipes.
 *
 * <p>SRG method names are hardcoded ({@code m_44054_}, {@code m_44013_}) and
 * remap is disabled; {@code require = 0} keeps the game loadable if the names
 * ever change, with the behavior mixin remaining as a runtime fallback.</p>
 */
@Mixin(RecipeManager.class)
public abstract class RecipeManagerMixin {

    @Inject(method = "m_44054_", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
    private void gtocutcorners$appendManaByType(RecipeType<?> type,
                                                CallbackInfoReturnable<Map<ResourceLocation, ?>> cir) {
        if (type != BotaniaRecipeTypes.MANA_INFUSION_TYPE) {
            return;
        }
        Map<ResourceLocation, ManaInfusionRecipe> extra = GTOCBotaniaRecipes.recipeMap();
        if (extra.isEmpty()) {
            return;
        }
        Map<ResourceLocation, Object> merged = new HashMap<>(cir.getReturnValue());
        merged.putAll(extra);
        cir.setReturnValue(merged);
    }

    @Inject(method = "m_44013_", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
    private void gtocutcorners$appendManaAllRecipes(RecipeType<?> type,
                                                    CallbackInfoReturnable<List<?>> cir) {
        if (type != BotaniaRecipeTypes.MANA_INFUSION_TYPE) {
            return;
        }
        Map<ResourceLocation, ManaInfusionRecipe> extra = GTOCBotaniaRecipes.recipeMap();
        if (extra.isEmpty()) {
            return;
        }
        List<Object> merged = new ArrayList<>(cir.getReturnValue());
        merged.addAll(extra.values());
        cir.setReturnValue(merged);
    }
}
