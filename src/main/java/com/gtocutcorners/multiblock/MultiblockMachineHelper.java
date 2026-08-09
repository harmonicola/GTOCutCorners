package com.gtocutcorners.multiblock;

import com.gtocutcorners.GTOCutCorners;

import java.lang.reflect.*;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/**
 * Reflection-based machine factory creation for GTOLib multiblock machine types.
 *
 * Each method reflectively calls a GTOLib machine class's static factory method
 * (or creates a constructor-based factory) and returns a Function&lt;MetaMachineBlockEntity,
 * ? extends MultiblockControllerMachine&gt; suitable for passing to
 * GTOCMultiblocks.registerFromCommonSetup().
 */
public class MultiblockMachineHelper {

    // ════════════════════════════════════════════════════════════
    // Cached reflection handles
    // ════════════════════════════════════════════════════════════

    private static Class<?> metaMachineBlockEntityClass;
    private static Class<?> multiblockControllerMachineClass;

    // Machine classes
    private static Class<?> tierCasingMachineClass;
    private static Class<?> coilMachineClass;
    private static Class<?> crossRecipeMachineClass;
    private static Class<?> coilCrossRecipeMachineClass;
    private static Class<?> customParallelMachineClass;
    private static Class<?> tierCasingParallelMachineClass;
    private static Class<?> coilCustomParallelMachineClass;
    private static Class<?> noEnergyMachineClass;
    private static Class<?> noRecipeLogicMachineClass;
    private static Class<?> electricMultiblockMachineClass;
    private static Class<?> storageMultiblockMachineClass;

    private static Class<?> machineUtilsClass;
    private static boolean initOk;

    static {
        try {
            metaMachineBlockEntityClass = Class.forName("com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity");
            multiblockControllerMachineClass = Class.forName("com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine");

            String pkg = "com.gtolib.api.machine.multiblock.";
            tierCasingMachineClass = Class.forName(pkg + "TierCasingMultiblockMachine");
            coilMachineClass = Class.forName(pkg + "CoilMultiblockMachine");
            crossRecipeMachineClass = Class.forName(pkg + "CrossRecipeMultiblockMachine");
            coilCrossRecipeMachineClass = Class.forName(pkg + "CoilCrossRecipeMultiblockMachine");
            customParallelMachineClass = Class.forName(pkg + "CustomParallelMultiblockMachine");
            tierCasingParallelMachineClass = Class.forName(pkg + "TierCasingParallelMultiblockMachine");
            noEnergyMachineClass = Class.forName(pkg + "NoEnergyMultiblockMachine");
            noRecipeLogicMachineClass = Class.forName(pkg + "NoRecipeLogicMultiblockMachine");
            electricMultiblockMachineClass = Class.forName(pkg + "ElectricMultiblockMachine");
            storageMultiblockMachineClass = Class.forName(pkg + "StorageMultiblockMachine");

            try {
                coilCustomParallelMachineClass = Class.forName(pkg + "CoilCustomParallelMultiblockMachine");
            } catch (ClassNotFoundException e) {
                coilCustomParallelMachineClass = null;
            }

            try {
                machineUtilsClass = Class.forName("com.gtolib.utils.MachineUtils");
            } catch (ClassNotFoundException e) {
                machineUtilsClass = null;
            }

            initOk = true;
            GTOCutCorners.jlog("[MultiblockMachineHelper] Reflection init OK");
        } catch (Exception e) {
            GTOCutCorners.jlog("[MultiblockMachineHelper] Reflection init FAILED: " + e);
            initOk = false;
        }
    }

    // ════════════════════════════════════════════════════════════
    // Public factory methods — each returns a Function
    // ════════════════════════════════════════════════════════════

    /**
     * TierCasingMultiblockMachine.createMachine(casingKeys...)
     * Suitable for machines that depend on tiered casing blocks.
     */
    public static Object tierCasing(String... casingKeys) {
        return callStaticFactory(tierCasingMachineClass, "createMachine",
            new Class[]{String[].class}, (Object) casingKeys);
    }

    /**
     * CoilMultiblockMachine.createCoilMachine(checkTemperature, infiniteCoil)
     * Suitable for machines that depend on coil blocks.
     */
    public static Object coil(boolean checkTemperature, boolean infiniteCoil) {
        return callStaticFactory(coilMachineClass, "createCoilMachine",
            new Class[]{boolean.class, boolean.class}, checkTemperature, infiniteCoil);
    }

    /**
     * CrossRecipeMultiblockMachine.createParallel(crossDimension, hasMultipleRecipes, parallelCalc)
     */
    public static Object crossRecipeParallel(boolean crossDimension, boolean hasMultipleRecipes, Object toLongFunction) {
        return callStaticFactory(crossRecipeMachineClass, "createParallel",
            new Class[]{boolean.class, boolean.class, ToLongFunction.class},
            crossDimension, hasMultipleRecipes, toLongFunction);
    }

    /**
     * CrossRecipeMultiblockMachine.createHatchParallel(entity)
     * Returns an instance-based factory (not a static Function factory).
     * We wrap it in a Function.
     */
    @SuppressWarnings("unchecked")
    public static Object crossRecipeHatchParallel() {
        if (!initOk) return null;
        // createHatchParallel is an instance method that takes MetaMachineBlockEntity
        // We need to create a Function that calls new CrossRecipeMultiblockMachine.createHatchParallel(entity)
        // Actually, createHatchParallel is static: public static CrossRecipeMultiblockMachine createHatchParallel(MetaMachineBlockEntity)
        // But it returns a CrossRecipeMultiblockMachine instance, not a Function.
        // We wrap it.
        try {
            return Proxy.newProxyInstance(
                Function.class.getClassLoader(),
                new Class[]{Function.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("apply") && args != null && args.length == 1) {
                        try {
                            Method factory = crossRecipeMachineClass.getMethod("createHatchParallel",
                                metaMachineBlockEntityClass);
                            return factory.invoke(null, args[0]);
                        } catch (Exception e) {
                            GTOCutCorners.jlog("[MachineHelper] crossRecipeHatchParallel FAIL: " + e);
                            return null;
                        }
                    }
                    return null;
                }
            );
        } catch (Exception e) {
            GTOCutCorners.jlog("[MachineHelper] crossRecipeHatchParallel FAIL: " + e);
            return null;
        }
    }

    /**
     * CoilCrossRecipeMultiblockMachine.createHatchParallel(notCheckTemperature)
     */
    public static Object coilCrossRecipeHatchParallel(boolean notCheckTemperature) {
        return callStaticFactory(coilCrossRecipeMachineClass, "createHatchParallel",
            new Class[]{boolean.class}, notCheckTemperature);
    }

    /**
     * CoilCrossRecipeMultiblockMachine.createCoilParallelEBF()
     */
    public static Object coilCrossRecipeEBF() {
        return callStaticFactory(coilCrossRecipeMachineClass, "createCoilParallelEBF", new Class[]{});
    }

    /**
     * CoilCrossRecipeMultiblockMachine.createCoilParallel(entity)
     */
    @SuppressWarnings("unchecked")
    public static Object coilCrossRecipeCoilParallel() {
        if (!initOk) return null;
        try {
            return Proxy.newProxyInstance(
                Function.class.getClassLoader(),
                new Class[]{Function.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("apply") && args != null && args.length == 1) {
                        try {
                            Method factory = coilCrossRecipeMachineClass.getMethod("createCoilParallel",
                                metaMachineBlockEntityClass);
                            return factory.invoke(null, args[0]);
                        } catch (Exception e) {
                            return null;
                        }
                    }
                    return null;
                }
            );
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * CoilCrossRecipeMultiblockMachine.createInfiniteCoilParallel(entity)
     */
    @SuppressWarnings("unchecked")
    public static Object coilCrossRecipeInfiniteCoilParallel() {
        if (!initOk) return null;
        try {
            return Proxy.newProxyInstance(
                Function.class.getClassLoader(),
                new Class[]{Function.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("apply") && args != null && args.length == 1) {
                        try {
                            Method factory = coilCrossRecipeMachineClass.getMethod("createInfiniteCoilParallel",
                                metaMachineBlockEntityClass);
                            return factory.invoke(null, args[0]);
                        } catch (Exception e) {
                            return null;
                        }
                    }
                    return null;
                }
            );
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * CustomParallelMultiblockMachine.createParallel(parallelCalc, hasMultipleRecipes)
     */
    public static Object customParallel(Object toLongFunction, boolean hasMultipleRecipes) {
        return callStaticFactory(customParallelMachineClass, "createParallel",
            new Class[]{ToLongFunction.class, boolean.class},
            toLongFunction, hasMultipleRecipes);
    }

    /**
     * TierCasingParallelMultiblockMachine.createParallel(parallelCalc, hasMultipleRecipes, casingKeys...)
     */
    public static Object tierCasingParallel(Object toLongFunction, boolean hasMultipleRecipes, String... casingKeys) {
        return callStaticFactory(tierCasingParallelMachineClass, "createParallel",
            new Class[]{ToLongFunction.class, boolean.class, String[].class},
            toLongFunction, hasMultipleRecipes, casingKeys);
    }

    /**
     * NoEnergyMultiblockMachine constructor-based factory.
     * Simple machine with no energy requirement.
     */
    @SuppressWarnings("unchecked")
    public static Object noEnergy() {
        return constructorFactory(noEnergyMachineClass, new Class[]{metaMachineBlockEntityClass});
    }

    /**
     * NoRecipeLogicMultiblockMachine constructor-based factory.
     * Machine with no recipe processing logic (display/structure only).
     */
    @SuppressWarnings("unchecked")
    public static Object noRecipeLogic() {
        return constructorFactory(noRecipeLogicMachineClass, new Class[]{metaMachineBlockEntityClass});
    }

    /**
     * ElectricMultiblockMachine constructor-based factory.
     * Base electric multiblock machine.
     */
    @SuppressWarnings("unchecked")
    public static Object electric() {
        return constructorFactory(electricMultiblockMachineClass, new Class[]{metaMachineBlockEntityClass});
    }

    /**
     * StorageMultiblockMachine constructor-based factory.
     * Machine with built-in storage slot.
     */
    @SuppressWarnings("unchecked")
    public static Object storage(int slotLimit, Object filter) {
        if (!initOk) return null;
        try {
            Constructor<?> ctor = storageMultiblockMachineClass.getConstructor(
                metaMachineBlockEntityClass, int.class, java.util.function.Predicate.class);
            return (Function) entity -> {
                try {
                    return ctor.newInstance(entity, slotLimit, filter);
                } catch (Exception e) {
                    return null;
                }
            };
        } catch (Exception e) {
            GTOCutCorners.jlog("[MachineHelper] storage FAIL: " + e);
            return null;
        }
    }

    // ════════════════════════════════════════════════════════════
    // ToLongFunction helpers — create parallel calculation functions
    // ════════════════════════════════════════════════════════════

    /**
     * Create a ToLongFunction that uses the machine's hatch count for parallel.
     * Reflects MachineUtils.getHatchParallel(machine).
     */
    @SuppressWarnings("unchecked")
    public static Object hatchParallelFunction() {
        if (!initOk || machineUtilsClass == null) return null;
        try {
            // Find getHatchParallel method
            Method getHatchParallel = findMethod(machineUtilsClass, "getHatchParallel");
            if (getHatchParallel == null) return null;

            return Proxy.newProxyInstance(
                ToLongFunction.class.getClassLoader(),
                new Class[]{ToLongFunction.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("applyAsLong") && args != null && args.length == 1) {
                        try {
                            return (long) getHatchParallel.invoke(null, args[0]);
                        } catch (Exception e) {
                            return 0L;
                        }
                    }
                    return 0L;
                }
            );
        } catch (Exception e) {
            GTOCutCorners.jlog("[MachineHelper] hatchParallelFunction FAIL: " + e);
            return null;
        }
    }

    /**
     * Create a ToLongFunction that calculates temperature-based parallel.
     * Formula: 2^(temperature / 900), capped at 2^60.
     * The function reflectively calls getTemperature() on the machine.
     */
    @SuppressWarnings("unchecked")
    public static Object temperatureParallelFunction() {
        if (!initOk) return null;
        return Proxy.newProxyInstance(
            ToLongFunction.class.getClassLoader(),
            new Class[]{ToLongFunction.class},
            (proxy, method, args) -> {
                if (method.getName().equals("applyAsLong") && args != null && args.length == 1) {
                    try {
                        Object machine = args[0];
                        Method getTemp = findMethod(machine.getClass(), "getTemperature");
                        if (getTemp != null) {
                            int temp = (int) getTemp.invoke(machine);
                            return 1L << Math.min(60, temp / 900);
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                    return 0L;
                }
                return 0L;
            }
        );
    }

    /**
     * Create a ToLongFunction that calculates glass-tier-based parallel.
     * Formula: 4^(glassTier).
     */
    @SuppressWarnings("unchecked")
    public static Object glassParallelFunction(String tierKey) {
        if (!initOk) return null;
        return Proxy.newProxyInstance(
            ToLongFunction.class.getClassLoader(),
            new Class[]{ToLongFunction.class},
            (proxy, method, args) -> {
                if (method.getName().equals("applyAsLong") && args != null && args.length == 1) {
                    try {
                        Object machine = args[0];
                        Method getCasingTiers = findMethod(machine.getClass(), "getCasingTiers");
                        if (getCasingTiers != null) {
                            Object tiers = getCasingTiers.invoke(machine);
                            Method getInt = findMethod(tiers.getClass(), "getInt", Object.class);
                            if (getInt != null) {
                                int tier = (int) getInt.invoke(tiers, tierKey);
                                return 1L << (2 * tier);
                            }
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                    return 0L;
                }
                return 0L;
            }
        );
    }

    // ════════════════════════════════════════════════════════════
    // Internal helpers
    // ════════════════════════════════════════════════════════════

    /**
     * Call a static factory method that returns a Function.
     */
    private static Object callStaticFactory(Class<?> clazz, String methodName,
                                             Class<?>[] paramTypes, Object... args) {
        if (!initOk || clazz == null) return null;
        try {
            Method factoryMethod = findMethod(clazz, methodName, paramTypes);
            if (factoryMethod == null) {
                GTOCutCorners.jlog("[MachineHelper] Factory method not found: " +
                    clazz.getSimpleName() + "." + methodName);
                return null;
            }
            return factoryMethod.invoke(null, args);
        } catch (Exception e) {
            GTOCutCorners.jlog("[MachineHelper] " + clazz.getSimpleName() + "." +
                methodName + " FAIL: " + e);
            return null;
        }
    }

    /**
     * Create a Function from a constructor.
     */
    @SuppressWarnings("unchecked")
    private static Object constructorFactory(Class<?> clazz, Class<?>[] paramTypes) {
        if (!initOk || clazz == null) return null;
        try {
            Constructor<?> ctor = clazz.getDeclaredConstructor(paramTypes);
            ctor.setAccessible(true);
            return (Function<Object, Object>) entity -> {
                try {
                    return ctor.newInstance(entity);
                } catch (Exception e) {
                    GTOCutCorners.jlog("[MachineHelper] Constructor FAIL: " + e);
                    return null;
                }
            };
        } catch (Exception e) {
            GTOCutCorners.jlog("[MachineHelper] constructorFactory FAIL: " + e);
            return null;
        }
    }

    /**
     * Find a method by name and parameter types in a class hierarchy.
     */
    private static Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
        if (clazz == null) return null;
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                Method m = current.getDeclaredMethod(name, paramTypes);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e) {
                current = current.getSuperclass();
            }
        }
        // Fallback: match by name and param count
        current = clazz;
        while (current != null && current != Object.class) {
            for (Method m : current.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == paramTypes.length) {
                    m.setAccessible(true);
                    return m;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    public static boolean isInitOk() { return initOk; }
}
