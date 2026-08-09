package com.gtocutcorners.mixin;

import com.gtocutcorners.registry.GTOCBotaniaRecipes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import vazkii.botania.api.recipe.ManaInfusionRecipe;
import vazkii.botania.common.block.block_entity.mana.ManaPoolBlockEntity;

/**
 * Lets the mana pool convert GTOCutCorners machines without touching the
 * GTO-replaced RecipeManager: short-circuits getMatchingRecipe for furnace and
 * chest, returning GTOCutCorners' own mana-infusion recipes.
 */
@Mixin(value = ManaPoolBlockEntity.class, remap = false)
public abstract class ManaPoolBlockEntityMixin {

    @Inject(method = "getMatchingRecipe", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtocutcorners$manaRecipes(ItemStack stack, BlockState state,
                                           CallbackInfoReturnable<ManaInfusionRecipe> cir) {
        ManaInfusionRecipe recipe = GTOCBotaniaRecipes.recipeFor(stack);
        if (recipe != null) {
            cir.setReturnValue(recipe);
        }
    }
}
