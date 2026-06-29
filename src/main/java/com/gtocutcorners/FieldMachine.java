package com.gtocutcorners;

import com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.FactoryBlockPattern;
import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.common.data.GTBlocks;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class FieldMachine {
    public static MultiblockMachineDefinition DEFINITION;

    @SuppressWarnings("unchecked")
    public static void register() {
        try {
            GTOCutCorners.jlog("[FM] register called");
        } catch (Exception ignored) {
        }

        try {
            // 强制初始化 registrate ClientEvent 的所有 null ConcurrentHashMap
            try {
                Class<?> ce = Class.forName("com.gto.registrate.ClientEvent");
                for (java.lang.reflect.Field f : ce.getDeclaredFields()) {
                    if (ConcurrentHashMap.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        if (f.get(null) == null) {
                            f.set(null, new ConcurrentHashMap<>());
                            GTOCutCorners.jlog("FM: init " + f.getName());
                        }
                    }
                }
            } catch (Exception e) {
                GTOCutCorners.jlog("FM: init err: " + e.getMessage());
            }

            GTOCutCorners.jlog("FM:1 start");
            Class<?> mru = Class.forName("com.gtocore.utils.register.MachineRegisterUtils");
            GTOCutCorners.jlog("FM:2 class=" + mru.getName());

            Method mb = mru.getMethod("multiblock", String.class, String.class, Function.class);
            GTOCutCorners.jlog("FM:3 method=" + mb);

            Object builder = mb.invoke(
                null,
                "gtocutcorners",
                "field_machine",
                (Function<MetaMachineBlockEntity, MultiblockControllerMachine>) MultiblockControllerMachine::new
            );
            GTOCutCorners.jlog("FM:4 builder=" + builder.getClass().getName());

            Method pf = builder.getClass().getMethod("pattern", Function.class);
            GTOCutCorners.jlog("FM:5 patternFactory=" + pf);

            pf.invoke(builder, (Function<MultiblockMachineDefinition, BlockPattern>) FieldMachine::createPattern);
            GTOCutCorners.jlog("FM:6 pattern set");

            Method reg = builder.getClass().getMethod("register");

            // 调用 Data.commonInit() 里同样的 unfreeze: GTRegistry$RL.unfreeze()
            try {
                Class<?> rlCls = Class.forName("com.gregtechceu.gtceu.api.registry.GTRegistry$RL");
                Method unfreezeM = rlCls.getMethod("unfreeze");

                // 需要获取 machine registry 的 RL 实例 - 从 GTORegistration.GTO 找
                Class<?> gtoReg = Class.forName("com.gtolib.api.registries.GTORegistration");
                java.lang.reflect.Field gtoF = gtoReg.getDeclaredField("GTO");
                gtoF.setAccessible(true);
                Object gto = gtoF.get(null);

                // 遍历所有字段找 GTRegistry$RL 类型
                for (java.lang.reflect.Field f : gto.getClass().getFields()) {
                    if (rlCls.isAssignableFrom(f.getType())) {
                        Object rl = f.get(gto);
                        if (rl != null) {
                            boolean wasOpen = (boolean) rlCls.getMethod("isOpen").invoke(rl);
                            GTOCutCorners.jlog("FM: Registry RL " + f.getName() + " open=" + wasOpen);
                            if (!wasOpen) {
                                unfreezeM.invoke(rl);
                            }
                        }
                    }
                }
                GTOCutCorners.jlog("FM: unfreeze done");
            } catch (Exception e) {
                GTOCutCorners.jlog("FM: unfreeze err: " + e.getMessage());
            }

            // 遍历 GTORegistration 及所有父类, 找含 frozen 字段的 Registry
            try {
                Class<?> regCls = Class.forName("com.gto.datasynclib.util.Registry");
                java.lang.reflect.Field frozenF = regCls.getDeclaredField("frozen");
                frozenF.setAccessible(true);
                Class<?> gtoReg = Class.forName("com.gtolib.api.registries.GTORegistration");
                java.lang.reflect.Field gtoF = gtoReg.getDeclaredField("GTO");
                gtoF.setAccessible(true);
                Object gto = gtoF.get(null);

                // 遍历所有父类找 Registry 类型字段
                Class<?> cls = gto.getClass();
                int found = 0;
                while (cls != null && found == 0) {
                    for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                        if (regCls.isAssignableFrom(f.getType())) {
                            f.setAccessible(true);
                            Object regObj = f.get(gto);
                            if (reg != null) {
                                boolean wasFrozen = frozenF.getBoolean(regObj);
                                GTOCutCorners.jlog(
                                    "FM: found Registry in " + cls.getSimpleName() + "." + f.getName()
                                        + " frozen=" + wasFrozen + " obj=" + regObj
                                );
                                frozenF.set(regObj, false);
                                found++;
                            }
                        }
                    }
                    cls = cls.getSuperclass();
                }
                GTOCutCorners.jlog("FM: unfroze " + found + " registries");
            } catch (Exception e) {
                GTOCutCorners.jlog("FM: unfreeze err: " + e.getMessage());
            }

            GTOCutCorners.jlog("FM:7 register method=" + reg);
            DEFINITION = (MultiblockMachineDefinition) reg.invoke(builder);
            GTOCutCorners.jlog("FM:8 DONE! " + DEFINITION);
        } catch (Throwable t) {
            Throwable cause = t.getCause();
            String info = t.getClass().getSimpleName() + ": " + t.getMessage();
            if (cause != null) {
                info += " | cause: " + cause.getClass().getSimpleName() + ": " + cause.getMessage();
            }
            GTOCutCorners.jlog("FM:ERR " + info);

            for (StackTraceElement s : t.getStackTrace()) {
                String line = "  at " + s;
                GTOCutCorners.jlog(line);
                if (s.getClassName().contains("FieldMachine") && cause != null) {
                    break;
                }
            }

            if (cause != null) {
                GTOCutCorners.jlog("  Caused by:");
                for (StackTraceElement s : cause.getStackTrace()) {
                    GTOCutCorners.jlog("    at " + s);
                    if (s.getClassName().contains("MultiblockBuilder")) {
                        break;
                    }
                }
            }
        }
    }

    private static BlockPattern createPattern(MultiblockMachineDefinition def) {
        return FactoryBlockPattern.start()
            .aisle("CCC", "CCC", "CCC")
            .aisle("CCC", "CSC", "CCC")
            .aisle("CCC", "CCC", "CCC")
            .where('S', Predicates.blocks(GTBlocks.CASING_STEEL_SOLID.get()))
            .where('C', Predicates.blocks(GTBlocks.CASING_STEEL_SOLID.get()))
            .build();
    }
}
