package com.gtocutcorners;

import com.gregtechceu.gtceu.api.recipe.GTRecipeDefinition;
import net.minecraft.world.item.crafting.*;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

@Mod(GTOCutCorners.MODID)
public class GTOCutCorners {
    public static final String MODID = "gtocutcorners";
    private static FileWriter fw;
    static { try { fw = new FileWriter("gtocutcorners_java.log", true); } catch (Exception e) {} }

    public GTOCutCorners() { MinecraftForge.EVENT_BUS.register(this); }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        new Thread(GTOCutCorners::loadAndPatch, "GTOCutCorners-Patcher").start();
    }

    private static synchronized void jlog(String msg) {
        String line = "[GTO] " + msg + "\n";
        System.out.print(line);
        if (fw != null) { try { fw.write(line); fw.flush(); } catch (Exception ignored) {} }
    }

    /* JNI */
    private static native Collection<GTRecipeDefinition> getRecipeCollection();
    private static native int nativeSetIntField(Object obj, String fieldName, int newValue);

    private static final Set<String> GENS = Set.of(
        "combustion","gas_turbine","steam_boiler","large_boiler",
        "rock_breaker","heat_exchanger","steam_turbine","plasma_generator"
    );

    private static int patchGT(Collection<GTRecipeDefinition> recipes) {
        jlog("patchGT: size=" + recipes.size());
        long start = System.currentTimeMillis(), lastLog = start;
        int count = 0, skipped = 0, errors = 0, total = 0;

        for (GTRecipeDefinition r : recipes) {
            total++;
            try {
                if (r.recipeType != null) {
                    String tn = r.recipeType.toString().toLowerCase();
                    boolean isGen = false;
                    for (String kw : GENS) { if (tn.contains(kw)) { isGen = true; break; } }
                    if (isGen) { skipped++; continue; }
                }
                int old = nativeSetIntField(r, "duration", 1);
                count++;
                if (count <= 10) jlog("  #" + count + " " + r.id + " " + old + "->1");
            } catch (Exception e) {
                errors++;
                if (errors <= 10) jlog("  ERR " + r.id + ": " + e.getMessage());
            }
            long now = System.currentTimeMillis();
            if (now - lastLog > 5000) {
                jlog("  progress " + total + "/" + recipes.size() + " p=" + count + " s=" + skipped);
                lastLog = now;
            }
        }
        jlog("patchGT done: " + count + " patched " + skipped + " skipped " + errors + " errors " + (System.currentTimeMillis()-start) + "ms");
        return count;
    }

    private static int patchVanilla() {
        jlog("patchVanilla start");
        int count = 0;
        try {
            Class<?> mcC = Class.forName("net.minecraft.client.Minecraft");
            
            // 列出所有方法找getInstance
            jlog("--- Minecraft methods returning Minecraft ---");
            for (Method m : mcC.getDeclaredMethods())
                if (Modifier.isStatic(m.getModifiers()) && m.getReturnType()==mcC && m.getParameterCount()==0)
                    jlog("  static factory: " + m.getName());
            // 列出所有字段
            for (Field f : mcC.getDeclaredFields())
                if (Modifier.isStatic(f.getModifiers()) && f.getType()==mcC)
                    jlog("  static field: " + f.getName());

            // 试用所有方法
            Object mc = null;
            for (Method m : mcC.getDeclaredMethods())
                if (Modifier.isStatic(m.getModifiers()) && m.getReturnType()==mcC && m.getParameterCount()==0)
                    { try { mc=m.invoke(null); jlog("mc via: "+m.getName()); break; } catch(Exception e){} }
            if (mc==null) for (Field f : mcC.getDeclaredFields())
                if (Modifier.isStatic(f.getModifiers()) && f.getType()==mcC)
                    { try { f.setAccessible(true); mc=f.get(null); jlog("mc via field: "+f.getName()); break; } catch(Exception e){} }
            if (mc==null){ jlog("mc FAIL"); return 0; }

            // 列出所有可能level的字段
            jlog("--- Minecraft level candidates ---");
            for (Field f : mcC.getDeclaredFields()) {
                String tn = f.getType().getSimpleName();
                if (tn.contains("Level") || tn.contains("ClientLevel")) jlog("  level candidate: "+f.getName()+" type="+f.getType().getName());
            }

            // 试用 - 必须精确匹配 ClientLevel, 不能用 LevelRenderer
            Object lvl = null;
            for (Field f : mcC.getDeclaredFields()) {
                String tn = f.getType().getSimpleName();
                if (tn.equals("ClientLevel"))
                    { try { f.setAccessible(true); lvl=f.get(mc); jlog("lvl via: "+f.getName()+" ("+f.getType().getName()+")"); break; } catch(Exception e){ jlog("lvl fail "+f.getName()+": "+e.getMessage()); } }
            }
            if (lvl==null){ jlog("lvl FAIL"); return 0; }
            Class<?> lvlC = lvl.getClass();

            // RecipeManager
            jlog("--- RecipeManager methods ---");
            Object rm = null;
            for (Method m : lvlC.getDeclaredMethods())
                if (m.getParameterCount()==0 && m.getReturnType().getSimpleName().contains("RecipeManager"))
                    jlog("  rm candidate: "+m.getName()+" -> "+m.getReturnType().getSimpleName());
            for (Method m : lvlC.getMethods())
                if (m.getParameterCount()==0 && m.getReturnType().getSimpleName().contains("RecipeManager"))
                    { try { rm=m.invoke(lvl); jlog("rm via: "+m.getName()); break; } catch(Exception e){ jlog("rm fail "+m.getName()+": "+e.getMessage()); } }
            if (rm==null){ jlog("rm FAIL"); return 0; }
            Class<?> rmC = rm.getClass();

            // getRecipes
            jlog("--- RM getRecipes candidates ---");
            Collection<?> all = null;
            for (Method m : rmC.getDeclaredMethods())
                if (m.getParameterCount()==0 && Collection.class.isAssignableFrom(m.getReturnType()))
                    jlog("  recipes candidate: "+m.getName()+" -> "+m.getReturnType().getSimpleName());
            for (Method m : rmC.getMethods())
                if (m.getParameterCount()==0 && Collection.class.isAssignableFrom(m.getReturnType()))
                    { try { all=(Collection<?>)m.invoke(rm); jlog("recipes via: "+m.getName()+" size="+all.size()); break; } catch(Exception e){ jlog("recipes fail "+m.getName()+": "+e.getMessage()); } }
            if (all==null){ jlog("recipes FAIL"); return 0; }

            // cookingTime field
            jlog("--- AbstractCookingRecipe fields ---");
            Class<?> acrC = AbstractCookingRecipe.class;
            Field ctF = null;
            for (Field f : acrC.getDeclaredFields())
                if (f.getType()==int.class) jlog("  int field: "+f.getName());
            // 找cookingTime - 唯一int字段
            for (Field f : acrC.getDeclaredFields()) {
                if (f.getType()==int.class) {
                    try { ctF=f; ctF.setAccessible(true); jlog("ct via: "+f.getName()); break; }
                    catch(Exception e){ jlog("ct fail: "+f.getName()); }
                }
            }
            if (ctF==null){ jlog("ct FAIL"); return 0; }

            for (Object r : all)
                if (r instanceof AbstractCookingRecipe)
                    { nativeSetIntField(r, ctF.getName(), 1); count++; }
            jlog("vanilla done: " + count);
        } catch (Exception e) { jlog("vanilla ERR: " + e); }
        return count;
    }

    private static void loadAndPatch() {
        jlog("=== START ===");
        try {
            String dll = "libgtocutcorners_native.dll";
            InputStream in = GTOCutCorners.class.getClassLoader().getResourceAsStream("native/" + dll);
            if (in == null) { jlog("DLL not found"); return; }
            Path tmp = Files.createTempDirectory("g"); Path dp = tmp.resolve(dll);
            Files.copy(in, dp, StandardCopyOption.REPLACE_EXISTING); in.close();
            System.load(dp.toAbsolutePath().toString());
            jlog("DLL loaded");

            Collection<GTRecipeDefinition> recipes = getRecipeCollection();
            jlog("Got " + recipes.size() + " recipes");

            int gt = patchGT(recipes);
            int va = patchVanilla();
            jlog("=== DONE GT=" + gt + " Vanilla=" + va + " ===");
        } catch (Throwable t) {
            jlog("FATAL: " + t);
            for (StackTraceElement s : t.getStackTrace()) jlog("  " + s);
        }
    }
}
