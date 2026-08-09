package com.gtocutcorners.multiblock;

import com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity;
import com.gregtechceu.gtceu.api.capability.IParallelHatch;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine;
import com.gtolib.api.machine.feature.multiblock.IParallelMachine;

/**
 * Not Hard Box (不难之盒): electric version of the Easy Box with a built-in
 * 1,048,576 parallel. Both parallel paths are wired:
 *
 * <ul>
 *   <li>GTO path: {@link IParallelMachine} (getMaxParallel/getParallel).</li>
 *   <li>GTCEu path: {@link IParallelHatch} via getParallelHatch().</li>
 * </ul>
 */
public class NotHardBoxMachine extends WorkableElectricMultiblockMachine implements IParallelMachine {

    public static final long BUILT_IN_PARALLEL = 1_048_576L;

    private static final IParallelHatch DUMMY_PARALLEL_HATCH = () -> BUILT_IN_PARALLEL;

    public NotHardBoxMachine(MetaMachineBlockEntity holder) {
        super(holder);
    }

    @Override
    public long getMaxParallel() {
        return BUILT_IN_PARALLEL;
    }

    @Override
    public long getMinParallel() {
        return 1;
    }

    @Override
    public long getParallel() {
        return BUILT_IN_PARALLEL;
    }

    @Override
    public void setParallel(long parallel) {
        // Built-in fixed parallel; ignore external changes.
    }

    @Override
    public IParallelHatch getParallelHatch() {
        return DUMMY_PARALLEL_HATCH;
    }
}
