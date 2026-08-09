package com.gtocutcorners.item;

import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * ME tool that scans the Applied Energistics 2 network at the clicked block and
 * reports a compact storage summary (item/fluid types and totals) to the player.
 */
public class MeNetworkScannerItem extends Item {

    public MeNetworkScannerItem(Properties properties) {
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

        IInWorldGridNodeHost host = GridHelper.getNodeHost(level, context.getClickedPos());
        if (host == null) {
            player.sendSystemMessage(
                Component.translatable("item.gtocutcorners.me_network_scanner.no_node")
                    .withStyle(ChatFormatting.RED));
            return InteractionResult.CONSUME;
        }

        IGridNode node = host.getGridNode(context.getClickedFace());
        if (node == null) {
            node = host.getGridNode((Direction) null);
        }
        if (node == null) {
            player.sendSystemMessage(
                Component.translatable("item.gtocutcorners.me_network_scanner.no_node")
                    .withStyle(ChatFormatting.RED));
            return InteractionResult.CONSUME;
        }

        IGrid grid = node.getGrid();
        if (grid == null || !node.isActive()) {
            player.sendSystemMessage(
                Component.translatable("item.gtocutcorners.me_network_scanner.inactive")
                    .withStyle(ChatFormatting.RED));
            return InteractionResult.CONSUME;
        }

        IStorageService storage = grid.getStorageService();
        KeyCounter counter = storage.getCachedInventory();
        long itemCount = 0, fluidCount = 0;
        int itemTypes = 0, fluidTypes = 0;
        for (var entry : counter) {
            AEKey key = entry.getKey();
            long amount = entry.getLongValue();
            if (AEItemKey.is(key)) {
                itemTypes++;
                itemCount += amount;
            } else if (AEFluidKey.is(key)) {
                fluidTypes++;
                fluidCount += amount;
            }
        }

        player.sendSystemMessage(Component.translatable(
            "item.gtocutcorners.me_network_scanner.scan",
            itemTypes, itemCount, fluidTypes, fluidCount));
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.gtocutcorners.me_network_scanner.tooltip"));
    }
}
