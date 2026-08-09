package com.gtocutcorners.machine.part;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.pattern.AEProcessingPattern;
import com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity;
import com.gregtechceu.gtceu.api.gui.fancy.ConfiguratorPanel;
import com.gto.datasynclib.annotations.SaveToDisk;
import com.gto.datasynclib.annotations.SyncToClient;
import com.gto.datasynclib.annotations.SyncToServer;
import com.gto.datasynclib.listener.IntNotifiableHolder;
import com.gtocore.common.machine.multiblock.part.ae.MEPatternBufferPartMachine;
import com.gtocore.common.machine.multiblock.part.ae.MEPatternBufferPartMachineKt;
import com.gtocore.common.machine.multiblock.part.ae.MEPatternPartMachineKt;
import com.gtocutcorners.GTOConfig;
import com.gtocutcorners.GTOCutCorners;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Super ME Pattern Buffer, ported from GTLAdditions.
 *
 * <p>The GTOCore pattern-buffer base already provides paginated UI, AE2 network
 * exposure and proxy support; this subclass only adds a configurable capacity
 * and the "Forge Pattern Mode" (神锻样板模式) that rewrites the exposed AE2
 * processing patterns so their outputs (and, when configured, recipe-cycle
 * containers) are multiplied by the configured factor.</p>
 */
public class MESuperPatternBufferPartMachine extends MEPatternBufferPartMachineKt {

    public static final int MIN_MULTIPLIER = 1;
    public static final int MAX_MULTIPLIER = 30;

    private static final int DEFAULT_MULTIPLIER = 15;
    private static final int MAX_SLOTS = 18 * 10 * 255;

    /** Items that are consumed once per pattern cycle and must scale with the multiplier. */
    private static final Set<Item> RECIPE_CYCLE_CONTAINERS = new HashSet<>();

    /**
     * Pack-specific extra-input rules, mirroring GTLAdditions'
     * FORGE_OF_THE_ANTICHRIST_SPECIAL_INPUT_RULES. Left empty for the base port;
     * add entries via {@link #registerSpecialInputRule(Set, GenericStack)}.
     */
    private static final Map<Set<AEKey>, GenericStack> SPECIAL_INPUT_RULES = new HashMap<>();

    @SaveToDisk
    @SyncToClient
    @SyncToServer
    private IntNotifiableHolder foaModeHolder = IntNotifiableHolder.create(0);

    @SaveToDisk
    @SyncToClient
    @SyncToServer
    private IntNotifiableHolder foaMultiplierHolder = IntNotifiableHolder.create(DEFAULT_MULTIPLIER);

    public MESuperPatternBufferPartMachine(MetaMachineBlockEntity holder) {
        super(holder, slotCount());
        foaModeHolder.setSenderListener((side, oldValue, newValue) -> {
            if (side.isServer()) {
                refreshAllByProduct();
            }
        });
        foaMultiplierHolder.setSenderListener((side, oldValue, newValue) -> {
            if (side.isServer() && foaModeHolder.get() != 0) {
                refreshAllByProduct();
            }
        });
    }

    public static void registerRecipeCycleContainer(Item item) {
        RECIPE_CYCLE_CONTAINERS.add(item);
    }

    public static void registerSpecialInputRule(Set<AEKey> inputs, GenericStack extraInput) {
        SPECIAL_INPUT_RULES.put(inputs, extraInput);
    }

    public boolean isFOAModeEnabled() {
        return foaModeHolder.get() != 0;
    }

    public void setFOAModeEnabled(boolean enabled) {
        int value = enabled ? 1 : 0;
        if (foaModeHolder.get() != value) {
            foaModeHolder.set(value);
        }
        foaModeHolder.markAsChanged();
        syncToServer();
    }

    public int getFOAPatternOutputMultiplier() {
        return foaMultiplierHolder.get();
    }

    public void setFOAPatternOutputMultiplier(int multiplier) {
        int value = clamp(multiplier, MIN_MULTIPLIER, MAX_MULTIPLIER);
        if (foaMultiplierHolder.get() != value) {
            foaMultiplierHolder.set(value);
        }
        foaMultiplierHolder.markAsChanged();
        syncToServer();
    }

    @Override
    public void attachConfigurators(ConfiguratorPanel configuratorPanel) {
        super.attachConfigurators(configuratorPanel);
        configuratorPanel.attachConfigurators(new FOAPatternConfigurator(this));
    }

    /**
     * GTOCore decodes every slot through {@code decodePattern -> convertPattern}
     * before exposing it to the AE2 grid, so this is the single hook that makes
     * the network (and the UI preview) see the Forge-mode-rewritten pattern.
     */
    @Override
    public IPatternDetails convertPattern(IPatternDetails pattern, int index) {
        IPatternDetails converted = super.convertPattern(pattern, index);
        if (isFOAModeEnabled() && converted instanceof AEProcessingPattern processing) {
            return rewriteForgePattern(processing, getFOAPatternOutputMultiplier());
        }
        return converted;
    }

    // ======================= Forge Pattern Mode =======================

    private IPatternDetails rewriteForgePattern(AEProcessingPattern pattern, int multiplier) {
        int m = clamp(multiplier, MIN_MULTIPLIER, MAX_MULTIPLIER);
        if (m <= 1) {
            return pattern;
        }

        GenericStack[] sparseInputs = createForgeInputs(pattern.getSparseInputs(), m);
        GenericStack[] sparseOutputs = createForgeOutputs(pattern.getSparseOutputs(), m);
        GenericStack extraInput = tryGetSpecialInput(pattern.getSparseInputs());
        if (extraInput != null) {
            sparseInputs = appendMatchingSpecialInput(sparseInputs, extraInput, m);
        }

        IPatternDetails decoded = PatternDetailsHelper.decodePattern(
            PatternDetailsHelper.encodeProcessingPattern(sparseInputs, sparseOutputs),
            getLevel());
        return decoded != null ? decoded : pattern;
    }

    private static GenericStack[] createForgeInputs(GenericStack[] stacks, int multiplier) {
        List<GenericStack> filtered = new ArrayList<>();
        for (GenericStack input : stacks) {
            if (input == null) {
                continue;
            }
            filtered.add(isRecipeCycleContainerStack(input)
                ? new GenericStack(input.what(), saturatedMultiply(input.amount(), multiplier))
                : input);
        }
        return filtered.toArray(new GenericStack[0]);
    }

    private static GenericStack[] createForgeOutputs(GenericStack[] stacks, int multiplier) {
        List<GenericStack> filtered = new ArrayList<>();
        for (GenericStack output : stacks) {
            if (output == null) {
                continue;
            }
            filtered.add(isRecipeCycleContainerStack(output)
                ? output
                : new GenericStack(output.what(), saturatedMultiply(output.amount(), multiplier)));
        }
        return filtered.toArray(new GenericStack[0]);
    }

    private static GenericStack tryGetSpecialInput(GenericStack[] stacks) {
        if (SPECIAL_INPUT_RULES.isEmpty()) {
            return null;
        }
        Set<AEKey> inputKeys = new HashSet<>();
        for (GenericStack stack : stacks) {
            if (stack != null) {
                inputKeys.add(stack.what());
            }
        }
        return SPECIAL_INPUT_RULES.get(inputKeys);
    }

    private static GenericStack[] appendMatchingSpecialInput(GenericStack[] stacks, GenericStack extraInput, int multiplier) {
        long extraAmount = saturatedMultiply(extraInput.amount(), multiplier - 1L);
        if (extraAmount <= 0) {
            return stacks;
        }
        List<GenericStack> filtered = new ArrayList<>();
        for (GenericStack stack : stacks) {
            if (stack != null) {
                filtered.add(stack);
            }
        }
        if (filtered.size() >= AEProcessingPattern.MAX_INPUT_SLOTS) {
            return stacks;
        }
        filtered.add(new GenericStack(extraInput.what(), extraAmount));
        return filtered.toArray(new GenericStack[0]);
    }

    private static boolean isRecipeCycleContainerStack(GenericStack stack) {
        AEKey key = stack.what();
        return key instanceof AEItemKey itemKey && RECIPE_CYCLE_CONTAINERS.contains(itemKey.getItem());
    }

    private static long saturatedMultiply(long value, long multiplier) {
        if (value <= 0 || multiplier <= 0) {
            return 0;
        }
        try {
            return Math.multiplyExact(value, multiplier);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    // ======================= capacity =======================

    private static int slotCount() {
        GTOConfig config = GTOCutCorners.getConfig();
        int perRow = clamp(config.superPatternBuffer.patternsPerRow, 1, 18);
        int rows = clamp(config.superPatternBuffer.rowsPerPage, 1, 10);
        int pages = clamp(config.superPatternBuffer.maxPages, 1, 255);
        return Math.min(perRow * rows * pages, MAX_SLOTS);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ======================= refresh =======================

    private void refreshAllByProduct() {
        if (getLevel() == null || isRemote()) {
            return;
        }
        for (int i = 0; i < getMaxPatternCount(); i++) {
            ItemStack stack = getPatternInventory().getStackInSlot(i);
            if (stack.isEmpty()) {
                continue;
            }
            MEPatternPartMachineKt.AbstractInternalSlot slot = getInternalInventory()[i];
            boolean lock = false;
            if (slot instanceof MEPatternBufferPartMachine.InternalSlot internal) {
                lock = internal.isLock();
            }
            onPatternChange(i);
            if (slot instanceof MEPatternBufferPartMachine.InternalSlot internal) {
                internal.setLock(lock);
            }
        }
    }
}
