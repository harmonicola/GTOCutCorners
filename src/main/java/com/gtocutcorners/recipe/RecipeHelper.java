package com.gtocutcorners.recipe;

import com.gtocutcorners.GTOCutCorners;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Pure-reflection recipe helper.
 */
public class RecipeHelper {

    public static final String MODID = GTOCutCorners.MODID;

    private static Class<?> gtoRecipeTypesClass;
    private static Method   recipeBuilderMethod;
    private static Method   inputItemsMethod;
    private static Method   outputItemsMethod;
    private static Method   durationMethod;
    private static Method   eutMethod;
    private static Method   circuitMetaMethod;
    private static Method   buildMethod;
    private static Field    recipesField;
    private static boolean  initOk;

    static {
        try {
            gtoRecipeTypesClass = Class.forName("com.gtocore.common.data.GTORecipeTypes");
            Field arf = gtoRecipeTypesClass.getField("ASSEMBLER_RECIPES");
            Object rt = arf.get(null);
            Class<?> rtClass = rt.getClass();

            recipeBuilderMethod = rtClass.getMethod("recipeBuilder", String.class);
            Object probe = recipeBuilderMethod.invoke(rt, "_probe_");
            Class<?> bc = probe.getClass();

            inputItemsMethod  = bc.getMethod("inputItems", Item.class, int.class);
            outputItemsMethod = bc.getMethod("outputItems", Item.class, int.class);
            durationMethod    = bc.getMethod("duration", int.class);
            eutMethod         = bc.getMethod("EUt", long.class);
            circuitMetaMethod = bc.getMethod("circuitMeta", int.class);
            buildMethod       = bc.getMethod("build");

            recipesField = findField(rtClass, "recipes");
            if (recipesField != null) recipesField.setAccessible(true);

            initOk = recipesField != null && circuitMetaMethod != null;
            GTOCutCorners.jlog("[RecipeHelper] init OK");
        } catch (Exception e) {
            GTOCutCorners.jlog("[RecipeHelper] init FAILED: " + e);
        }
    }

    private static Field findField(Class<?> cls, String name) {
        while (cls != null && cls != Object.class) {
            try { return cls.getDeclaredField(name); }
            catch (NoSuchFieldException e) { cls = cls.getSuperclass(); }
        }
        return null;
    }

    // ====================== public ======================

    public static void generic(String recipeTypeField, String name,
            String[] itemsIn, int[] countsIn,
            String[] itemsOut, int[] countsOut,
            long eut, int dur) {
        genericWithCircuit(recipeTypeField, name, itemsIn, countsIn, itemsOut, countsOut, eut, dur, -1);
    }

    /** Same as generic but with circuit=N selector (centrifuge recipes). Pass -1 to skip. */
    public static void genericWithCircuit(String recipeTypeField, String name,
            String[] itemsIn, int[] countsIn,
            String[] itemsOut, int[] countsOut,
            long eut, int dur, int circuit) {

        if (!initOk) { GTOCutCorners.jlog("[RecipeHelper] SKIP " + name); return; }

        Item[] inItems = resolveAll(itemsIn);
        if (itemsIn != null && inItems == null) return;
        Item[] outItems = resolveAll(itemsOut);
        if (itemsOut != null && outItems == null) return;

        try {
            Field typeField = gtoRecipeTypesClass.getField(recipeTypeField);
            Object recipeType = typeField.get(null);
            Object builder = recipeBuilderMethod.invoke(recipeType, name);

            if (inItems != null)
                for (int i = 0; i < inItems.length; i++)
                    inputItemsMethod.invoke(builder, inItems[i], countsIn[i]);
            if (outItems != null)
                for (int i = 0; i < outItems.length; i++)
                    outputItemsMethod.invoke(builder, outItems[i], countsOut[i]);

            if (circuit >= 0)
                circuitMetaMethod.invoke(builder, circuit);

            durationMethod.invoke(builder, dur);
            eutMethod.invoke(builder, eut);

            Object recipe = buildMethod.invoke(builder);

            @SuppressWarnings("unchecked")
            Map<Object, Object> map = (Map<Object, Object>) recipesField.get(recipeType);
            ResourceLocation rl = new ResourceLocation(MODID, name);
            if (map.containsKey(rl)) {
                GTOCutCorners.jlog("[RecipeHelper] SKIP (already exists): " + name);
                return;
            }
            map.put(rl, recipe);

            GTOCutCorners.jlog("[RecipeHelper] OK: " + name);
        } catch (Exception e) {
            GTOCutCorners.jlog("[RecipeHelper] FAIL " + name + ": " + e);
        }
    }

    public static void assembler(String recipeTypeField, String name,
            String[] itemsIn, int[] countsIn,
            String itemOut, int countOut, long eut, int dur) {
        generic(recipeTypeField, name, itemsIn, countsIn,
                new String[]{itemOut}, new int[]{countOut}, eut, dur);
    }

    // ====================== helpers ======================

    private static Item[] resolveAll(String[] ids) {
        if (ids == null) return null;
        Item[] result = new Item[ids.length];
        for (int i = 0; i < ids.length; i++) {
            result[i] = ForgeRegistries.ITEMS.getValue(new ResourceLocation(ids[i]));
            if (result[i] == null) {
                GTOCutCorners.jlog("[RecipeHelper] MISSING: " + ids[i]);
                return null;
            }
        }
        return result;
    }

    public static String[] si(String... a) { return a; }
    public static int[] ci(int... a) { return a; }
}
