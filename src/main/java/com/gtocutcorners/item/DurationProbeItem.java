package com.gtocutcorners.item;

import com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gtocutcorners.GTOCutCorners;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * GT tool: right-click a GT machine to inspect its recipe logic and current
 * duration. Uses reflection for the recipe logic part so it tolerates
 * GTCEu/GTOCore layout differences.
 */
public class DurationProbeItem extends Item {

    public DurationProbeItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) {
            return InteractionResult.PASS;
        }
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }

        BlockEntity blockEntity = level.getBlockEntity(context.getClickedPos());
        if (!(blockEntity instanceof MetaMachineBlockEntity metaBE)) {
            player.sendSystemMessage(
                Component.translatable("item.gtocutcorners.recipe_duration_probe.not_machine")
                    .withStyle(ChatFormatting.RED));
            return InteractionResult.CONSUME;
        }

        MetaMachine machine = metaBE.metaMachine;
        String defId = machine.getDefinition().getId().toString();
        int duration = probeDuration(machine);
        if (duration >= 0) {
            player.sendSystemMessage(Component.translatable(
                "item.gtocutcorners.recipe_duration_probe.result", defId, duration));
        } else {
            player.sendSystemMessage(Component.translatable(
                "item.gtocutcorners.recipe_duration_probe.no_recipe", defId));
        }
        return InteractionResult.CONSUME;
    }

    private static int probeDuration(MetaMachine machine) {
        try {
            Method getLogic = findMethod(machine.getClass(), "getRecipeLogic");
            if (getLogic == null) {
                return -1;
            }
            getLogic.setAccessible(true);
            Object logic = getLogic.invoke(machine);
            if (logic == null) {
                return -1;
            }
            for (Class<?> c = logic.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField("duration");
                    f.setAccessible(true);
                    if (f.getType() == int.class) {
                        return f.getInt(logic);
                    }
                } catch (NoSuchFieldException ignored) {
                    // keep walking the hierarchy
                }
            }
            Method getDuration = findMethod(logic.getClass(), "getDuration");
            if (getDuration != null) {
                getDuration.setAccessible(true);
                Object value = getDuration.invoke(logic);
                if (value instanceof Number number) {
                    return number.intValue();
                }
            }
        } catch (Throwable t) {
            GTOCutCorners.jlog("[DurationProbe] probe failed: " + t);
        }
        return -1;
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, paramTypes);
            } catch (NoSuchMethodException ignored) {
                // keep walking the hierarchy
            }
        }
        return null;
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.gtocutcorners.recipe_duration_probe.tooltip"));
    }
}
