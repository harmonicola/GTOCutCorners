package com.gtocutcorners.multiblock;

import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity;
import com.gregtechceu.gtceu.api.capability.IEnergyContainer;
import com.gregtechceu.gtceu.api.machine.feature.IDummyEnergyMachine;
import com.gregtechceu.gtceu.api.misc.EnergyContainerList;
import com.gtolib.api.machine.multiblock.CrossRecipeMultiblockMachine;
import net.minecraft.core.Direction;

/**
 * Cross-recipe stone furnace machine.
 *
 * <p>Extends GTO's cross-recipe machine so multiple different furnace recipes
 * can run at once (thread hatches add more concurrent recipe types). The voltage
 * getters are overridden to MAX because GTO's native recipe logic checks the
 * machine's actual voltage before modifiers run; our modifier still zeroes the
 * recipe EU, so no power is ever consumed.</p>
 */
public final class StoneFurnaceMachine extends CrossRecipeMultiblockMachine implements IDummyEnergyMachine {

    private static final long INFINITE_ENERGY = Long.MAX_VALUE / 4;

    /**
     * Dummy energy source: reports MAX voltage and a huge buffer so GTO's native
     * recipe logic accepts EU recipes without a physical energy hatch. The recipe
     * modifier zeroes the actual EU, so nothing is ever consumed.
     */
    private static final IEnergyContainer DUMMY_ENERGY = new IEnergyContainer() {
        @Override
        public long acceptEnergyFromNetwork(Object object, Direction direction, long amount, long voltage) {
            return 0;
        }

        @Override
        public boolean inputsEnergy(Direction direction) {
            return true;
        }

        @Override
        public long changeEnergy(long amount) {
            return amount;
        }

        @Override
        public long getEnergyStored() {
            return Long.MAX_VALUE / 4;
        }

        @Override
        public long getEnergyCapacity() {
            return Long.MAX_VALUE / 4;
        }

        @Override
        public long getInputAmperage() {
            return Long.MAX_VALUE / 4;
        }

        @Override
        public long getInputVoltage() {
            return GTValues.V[GTValues.MAX];
        }
    };

    private static final EnergyContainerList DUMMY_ENERGY_LIST =
        new EnergyContainerList(DUMMY_ENERGY);

    public StoneFurnaceMachine(MetaMachineBlockEntity holder) {
        super(holder, false, true, machine -> Integer.MAX_VALUE);
        energyBuffer = INFINITE_ENERGY;
        maxEnergyBuffer = INFINITE_ENERGY;
    }

    @Override
    public boolean useEnergy(long eu, boolean simulate) {
        return true;
    }

    @Override
    public long getEnergyBuffer() {
        return INFINITE_ENERGY;
    }

    @Override
    public long getMaxEnergyBuffer() {
        return INFINITE_ENERGY;
    }

    @Override
    public double getTotalEu() {
        return INFINITE_ENERGY;
    }

    @Override
    public long getOverclockMaxUnit() {
        return INFINITE_ENERGY;
    }

    @Override
    public void onStructureFormed() {
        super.onStructureFormed();
        energyBuffer = INFINITE_ENERGY;
        maxEnergyBuffer = INFINITE_ENERGY;
    }

    @Override
    public void onStructureInvalid() {
        super.onStructureInvalid();
        energyBuffer = INFINITE_ENERGY;
        maxEnergyBuffer = INFINITE_ENERGY;
    }

    @Override
    public long getMaxVoltage() {
        return GTValues.V[GTValues.MAX];
    }

    @Override
    public long getOverclockVoltage() {
        return GTValues.V[GTValues.MAX];
    }

    @Override
    public EnergyContainerList getEnergyContainer() {
        return DUMMY_ENERGY_LIST;
    }
}
