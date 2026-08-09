package com.gtocutcorners.machine;

import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;
import com.gtocore.api.machine.part.GTOPartAbility;
import com.gtocore.common.machine.multiblock.part.ae.MEPatternBufferProxyPartMachine;
import com.gtocore.utils.register.MachineRegisterUtils;
import com.gtocutcorners.GTOCutCorners;
import com.gtocutcorners.client.render.machine.SuperPatternBufferRenderer;
import com.gtocutcorners.machine.part.MESuperPatternBufferPartMachine;
import com.gtolib.GTOCore;
import net.minecraft.network.chat.Component;

/**
 * Registers the Super ME Pattern Buffer and its proxy in the GTO machine
 * registration window (called from GTOCMultiblocks.registerFromGtoWindow).
 */
public final class GTOCSuperPatternBuffer {

    private GTOCSuperPatternBuffer() {
    }

    public static void register() {
        if (!GTOCutCorners.registerMachines()) {
            return;
        }
        GTOCutCorners.jlog("[GTOCSuperPatternBuffer] Registering super ME pattern buffer parts...");

        MachineRegisterUtils.machine(
            "me_super_pattern_buffer",
            "超级样板总成",
            MESuperPatternBufferPartMachine::new)
            .tier(9)
            .allRotation()
            .abilities(PartAbility.IMPORT_ITEMS, PartAbility.IMPORT_FLUIDS, GTOPartAbility.DUAL_INPUT)
            .renderer(() -> new SuperPatternBufferRenderer(
                GTValues.UHV,
                GTOCore.id("block/machine/part/me_pattern_buffer_red")))
            .langValue("Me Super Pattern Buffer")
            .tooltips(
                Component.translatable("block.gtceu.pattern_buffer.desc.0"),
                Component.translatable("block.gtceu.pattern_buffer.desc.1"),
                Component.translatable("block.gtceu.pattern_buffer.desc.2"),
                Component.translatable("gtocutcorners.machine.me_super_pattern_buffer.desc.0"))
            .genLang("超级样板总成")
            .register();

        MachineRegisterUtils.machine(
            "me_super_pattern_buffer_proxy",
            "超级样板总成镜像",
            MEPatternBufferProxyPartMachine::new)
            .tier(6)
            .allRotation()
            .abilities(PartAbility.IMPORT_ITEMS, PartAbility.IMPORT_FLUIDS, GTOPartAbility.DUAL_INPUT)
            .renderer(() -> new SuperPatternBufferRenderer(
                GTValues.UHV,
                GTOCore.id("block/machine/part/me_pattern_buffer_red")))
            .langValue("Me Super Pattern Buffer Proxy")
            .tooltips(
                Component.translatable("block.gtceu.pattern_buffer_proxy.desc.0"),
                Component.translatable("block.gtceu.pattern_buffer_proxy.desc.1"),
                Component.translatable("block.gtceu.pattern_buffer_proxy.desc.2"))
            .genLang("超级样板总成镜像")
            .register();

        GTOCutCorners.jlog("[GTOCSuperPatternBuffer] Done.");
    }
}
