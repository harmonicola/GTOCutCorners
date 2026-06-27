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
            Object mc = null;
            for (String s : new String[]{"getInstance","m_91087_"})
                { try { mc = mcC.getMethod(s).invoke(null); jlog("mc:"+s); break; } catch (Exception e) { jlog("mc fail:"+s); } }
            if (mc == null) for (String s : new String[]{"instance","f_90981_"})
                { try { Field f = mcC.getDeclaredField(s); f.setAccessible(true); mc = f.get(null); jlog("mc field:"+s); break; } catch (Exception e) { jlog("mc field fail:"+s); } }
            if (mc == null) { jlog("mc FAIL"); return 0; }

            Object lvl = null;
            for (String s : new String[]{"level","f_91073_"})
                { try { Field f = mcC.getDeclaredField(s); f.setAccessible(true); lvl = f.get(mc); jlog("lvl:"+s); break; } catch (Exception e) { jlog("lvl fail:"+s); } }
            if (lvl == null) { jlog("lvl FAIL"); return 0; }

            Object rm = null;
            for (String s : new String[]{"getRecipeManager","m_9598_"})
                { try { rm = lvl.getClass().getMethod(s).invoke(lvl); jlog("rm:"+s); break; } catch (Exception e) { jlog("rm fail:"+s); } }
            if (rm == null) { jlog("rm FAIL"); return 0; }

            Collection<?> all = null;
            for (String s : new String[]{"getRecipes","m_44054_"})
                { try { all = (Collection<?>) rm.getClass().getMethod(s).invoke(rm); jlog("recipes:"+s+" "+all.size()); break; } catch (Exception e) { jlog("recipes fail:"+s); } }
            if (all == null) { jlog("recipes FAIL"); return 0; }

            for (Object r : all) {
                if (r instanceof AbstractCookingRecipe) {
                    nativeSetIntField(r, "cookingTime", 1); count++;
                }
            }
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
