package com.gtocutcorners.bootstrap;

import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.common.data.GTMachines;
import com.gregtechceu.gtceu.common.data.machines.GTMachineUtils;
import com.gtolib.api.data.GTODimensions;
import com.gtolib.api.misc.PlanetManagement;
import com.gtolib.utils.RLUtils;
import com.gtocutcorners.GTOCutCorners;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.UUID;

/**
 * Fixes GTO's scanner planet unlock for 1-tick recipes.
 *
 * <p>GTO installs an {@code onWorking} handler on the GTCEu scanner that unlocks
 * the planet on the penultimate tick ({@code progress == maxProgress - 1}). When
 * our overclocking patch forces the runtime duration to 1 tick that condition
 * never fires. We replace the handler with an equivalent one that fires at recipe
 * start ({@code progress <= 1}), so the chip is read/unlocked/consumed regardless
 * of the runtime duration.</p>
 */
public final class GTOCScannerUnlock {

    private GTOCScannerUnlock() {
    }

    /** Installs the unlock-at-start handler on every scanner tier above LV. */
    public static void apply() {
        for (int tier : GTMachineUtils.ELECTRIC_TIERS) {
            if (tier <= GTValues.LV) {
                continue;
            }
            MachineDefinition def = GTMachines.SCANNER[tier];
            if (def == null) {
                continue;
            }
            def.setOnWorking(GTOCScannerUnlock::onWorking);
            GTOCutCorners.jlog("[GTOCScannerUnlock] scanner tier " + tier + " unlock-at-start handler installed");
        }
    }

    private static void onWorking(IRecipeLogicMachine machine) {
        if (machine.getProgress() > 1) {
            return;
        }
        machine.forEachItems(true, (stack, amount) -> {
            CompoundTag tag = stack.getTag();
            if (tag != null) {
                String planet = tag.getString("planet");
                if (!planet.isEmpty()) {
                    UUID uuid = tag.getUUID("uuid");
                    ResourceKey<Level> dimKey = GTODimensions.getDimensionKey(RLUtils.parse(planet));
                    PlanetManagement.unlock(uuid, dimKey);
                    stack.setCount(0);
                    notifyUnlock(uuid, planet, dimKey);
                    return true;
                }
            }
            return false;
        });
    }

    private static void notifyUnlock(UUID uuid, String planet, ResourceKey<Level> dimKey) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            GTOCutCorners.jlog("[GTOCScannerUnlock] unlocked " + planet + " for UUID " + uuid + " (no server context)");
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(uuid);
        if (player != null) {
            boolean unlocked = PlanetManagement.isUnlocked(player, dimKey);
            player.sendSystemMessage(Component.translatable(
                unlocked ? "gtocutcorners.scanner.unlock" : "gtocutcorners.scanner.unlock_unknown", planet));
            GTOCutCorners.jlog("[GTOCScannerUnlock] unlocked " + planet + " for " + uuid
                + " (verified=" + unlocked + ")");
        } else {
            GTOCutCorners.jlog("[GTOCScannerUnlock] unlocked " + planet + " for offline UUID " + uuid);
        }
    }
}
