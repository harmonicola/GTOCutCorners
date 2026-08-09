package com.gtocutcorners.multiblock;

import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.FactoryBlockPattern;
import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;
import com.gregtechceu.gtceu.api.recipe.modifier.RecipeModifier;
import com.gregtechceu.gtceu.common.data.GCYMBlocks;
import com.gregtechceu.gtceu.common.data.GTRecipeTypes;
import com.gregtechceu.gtceu.common.machine.multiblock.steam.SteamParallelMultiblockMachine;
import com.gtocutcorners.GTOCutCorners;
import com.gtocutcorners.data.GTOCRecipeTypes;
import com.gtocutcorners.machine.GTOCSuperPatternBuffer;
import com.gtocutcorners.registry.ClientEventGuard;
import com.gtocore.api.machine.part.GTOPartAbility;
import com.gtocore.api.pattern.GTOPredicates;
import com.gtocore.common.data.GTORecipeTypes;
import com.gtocore.utils.register.MachineRegisterUtils;
import com.gtolib.api.registries.MultiblockBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import com.gregtechceu.gtceu.common.data.GTBlocks;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * GTOHJS-style multiblock registration.
 *
 * <p>Machines are registered through GTO's own {@link MachineRegisterUtils}
 * pipeline (gtocore namespace) from the generated coremod injection at the end of
 * {@code GTOMachines.<clinit>}. This runs inside GTO's own registration window,
 * before {@code GTRegistries.MACHINES} is frozen.</p>
 */
public final class GTOCMultiblocks {

    private static final AtomicBoolean DONE = new AtomicBoolean(false);
    private static final Set<String> NAMES = new LinkedHashSet<>();

    /**
     * Stone furnace modifier: zero energy only.
     *
     * <p>Parallelism (including multiple different recipes at once) is handled by
     * GTO's own cross-recipe machine trait, so we must
     * not multiply contents ourselves here — that would double-apply parallel.</p>
     */
    private static final RecipeModifier STONE_FURNACE_ZERO_EU =
        (holder, unit, recipe) -> { recipe.setEUt(0); return recipe; };

    private GTOCMultiblocks() {
    }

    /** Called from the generated coremod inside GTOMachines.<clinit>; idempotent. */
    public static synchronized void registerFromGtoWindow() {
        if (!GTOCutCorners.registerMachines()) {
            GTOCutCorners.jlog("[GTOCMultiblocks] SKIP (registerMachines=false)");
            return;
        }
        if (!DONE.compareAndSet(false, true)) {
            GTOCutCorners.jlog("[GTOCMultiblocks] SKIP (already registered)");
            return;
        }
        ClientEventGuard.ensureColorMaps();
        if (!MultiblockMachineHelper.isInitOk()) {
            GTOCutCorners.jlog("[GTOCMultiblocks] SKIP (machine helper init failed)");
            return;
        }
        GTOCutCorners.jlog("[GTOCMultiblocks] Registering machines in gtocore namespace (GTO window)...");
        registerCoilBlastFurnace();
        registerTierCasingMachine();
        registerSpaceWorkableMachine();
        registerEasyBox();
        registerPrimitiveStoneFurnace();
        registerNotHardBox();
        GTOCSuperPatternBuffer.register();
        GTOCutCorners.jlog("[GTOCMultiblocks] Done: " + NAMES);
    }

    // ======================= machines =======================

    private static void registerCoilBlastFurnace() {
        Object factory = MultiblockMachineHelper.coil(false, false);
        if (factory == null) return;
        MultiblockBuilder builder = MachineRegisterUtils.multiblock(
            "gto_blast_furnace", "GTO高炉", machineFactory(factory));
        builder.tier(4)
            .recipeType(GTORecipeTypes.BLAST_RECIPES)
            .overclock()
            .perfectOverclock()
            .coilParallelTooltips()
            .parallelizableTooltips()
            .workableCasingRenderer(
                new ResourceLocation("gtceu", "block/casings/firebox/machine_casing_firebox_titanium"),
                new ResourceLocation("gtceu", "block/casings/firebox/machine_casing_firebox_titanium"))
            .nonYAxisRotation()
            .tooltips(Component.translatable("gtocutcorners.machine.gto_blast_furnace.tooltip.0"))
            .genLang("GTO高炉")
            .multiblockPreviewRenderer(true, true)
            .pattern(def -> commonPattern(def))
            .register();
        NAMES.add("gto_blast_furnace");
    }

    private static void registerTierCasingMachine() {
        Object factory = MultiblockMachineHelper.tierCasing("pm");
        if (factory == null) return;
        MultiblockBuilder builder = MachineRegisterUtils.multiblock(
            "gto_tier_casing_machine", "GTO等级外壳机器", machineFactory(factory));
        builder.tier(5)
            .maxTier(8)
            .upgradable()
            .recipeType(GTORecipeTypes.ASSEMBLY_LINE_RECIPES)
            .parallelizableOverclock()
            .parallelizableTooltips()
            .eutMultiplierTooltips(0.8)
            .allRotation()
            .moduleTooltips()
            .tooltips(Component.translatable("gtocutcorners.machine.gto_tier_casing_machine.tooltip.0"))
            .genLang("GTO等级外壳机器")
            .multiblockPreviewRenderer(true, true)
            .pattern(def -> commonPattern(def))
            .register();
        NAMES.add("gto_tier_casing_machine");
    }

    private static void registerSpaceWorkableMachine() {
        Object factory = MultiblockMachineHelper.electric();
        if (factory == null) return;
        MultiblockBuilder builder = MachineRegisterUtils.multiblock(
            "gto_space_workable", "GTO太空工作机", machineFactory(factory));
        builder.tier(6)
            .recipeType(GTORecipeTypes.SPACE_DEBRIS_COLLECTION_RECIPES)
            .workableInSpace()
            .perfectOCTooltips()
            .laserTooltips()
            .nonYAxisRotation()
            .tooltips(Component.translatable("gtocutcorners.machine.gto_space_workable.tooltip.0"))
            .fromSourceTooltips("GTOCutCorners")
            .genLang("GTO太空工作机器")
            .multiblockPreviewRenderer(true, true)
            .pattern(def -> commonPattern(def))
            .register();
        NAMES.add("gto_space_workable");
    }

    /**
     * Easy Box: a workable multiblock running the {@code easy_box} recipe type
     * (1 item input -> up to 80 item outputs). Structure and predicates ported
     * faithfully from gtecore's GTEMultiMachine (steam machine, industrial steam
     * casing shell, bookshelf/brick/grass/clay/log/dirt/glass accents).
     */
    private static void registerEasyBox() {
        if (GTOCRecipeTypes.EASY_BOX == null) {
            GTOCutCorners.jlog("[GTOCMultiblocks] SKIP easy_box (recipe type not registered)");
            return;
        }
        MultiblockBuilder builder = MachineRegisterUtils.multiblock(
            "easy_box", "简单之盒",
            holder -> new SteamParallelMultiblockMachine(holder));
        builder.recipeType(GTOCRecipeTypes.EASY_BOX)
            .recipeModifier(GTOCEasyBoxModifier::applyModifier)
            .parallelizableTooltips()
            .workableCasingRenderer(
                new ResourceLocation("gtceu", "block/casings/gcym/industrial_steam_casing"),
                new ResourceLocation("gtceu", "block/multiblock/steam_oven"))
            .allRotation()
            .tooltips(Component.translatable("gtocutcorners.machine.easy_box.tooltip.0"))
            .genLang("简单之盒")
            .multiblockPreviewRenderer(true, true)
            .pattern(def -> easyBoxPattern(def))
            .register();
        NAMES.add("easy_box");
    }

    /** Original 13x7x13 Easy Box structure from gtecore. */
    private static BlockPattern easyBoxPattern(MultiblockMachineDefinition def) {
        return FactoryBlockPattern.start()
            .aisle("AAAAAAAAAAAAA", "BB.........BB", "B...........B", ".............", ".............", ".............", ".............")
            .aisle("AAAAAAAAAAAAA", "B...........B", ".............", ".............", ".............", ".............", ".............")
            .aisle("AAAAAAAAAAAAA", "..ACCCCCCCA..", "..ADDDDDDDA..", ".............", ".............", ".............", ".............")
            .aisle("AAAAAAAAAAAAA", "..CCCCCCCCC..", "..D.......D..", "...A.....A...", ".............", ".............", ".............")
            .aisle("AAAAAAAAAAAAA", "..CCCCCCCCC..", "..D.......D..", ".............", "....A...A....", ".............", ".............")
            .aisle("AAAAAAAAAAAAA", "..CCCCCCCCC..", "..D..AAA..D..", "......E......", "......F......", ".....AGA.....", ".............")
            .aisle("AAAAAAAAAAAAA", "..CCCCCCCCC..", "..D..AAA..D..", ".....EAE.....", ".....FAF.....", ".....GAG.....", "......H......")
            .aisle("AAAAAAAAAAAAA", "..CCCCCCCCC..", "..D..AAA..D..", "......E......", "......F......", ".....AGA.....", ".............")
            .aisle("AAAAAAAAAAAAA", "..CCCCCCCCC..", "..D.......D..", ".............", "....A...A....", ".............", ".............")
            .aisle("AAAAAAAAAAAAA", "..CCCCCCCCC..", "..D.......D..", "...A.....A...", ".............", ".............", ".............")
            .aisle("AAAAAAAAAAAAA", "..ACCCCCCCA..", "..ADDDDDDDA..", ".............", ".............", ".............", ".............")
            .aisle("AAAAAAAAAAAAA", "B...........B", ".............", ".............", ".............", ".............", ".............")
            .aisle("AAAAAA#AAAAAA", "BB.........BB", "B...........B", ".............", ".............", ".............", ".............")
            .where('.', Predicates.any())
            .where('A', Predicates.blocks(GCYMBlocks.CASING_INDUSTRIAL_STEAM.get())
                .or(Predicates.abilities(PartAbility.IMPORT_ITEMS))
                .or(Predicates.abilities(PartAbility.EXPORT_ITEMS))
                .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS))
                .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS))
                .or(Predicates.abilities(PartAbility.PARALLEL_HATCH))
                .or(Predicates.abilities(PartAbility.STEAM).setExactLimit(1)))
            .where('B', Predicates.blocks(Blocks.BOOKSHELF))
            .where('C', Predicates.blocks(Blocks.BRICKS))
            .where('D', Predicates.blocks(Blocks.GRASS_BLOCK))
            .where('E', Predicates.blocks(Blocks.CLAY))
            .where('F', Predicates.blocks(Blocks.OAK_LOG))
            .where('G', Predicates.blocks(Blocks.DIRT))
            .where('H', Predicates.blocks(Blocks.GLASS))
            .where('#', Predicates.controller(def))
            .build();
    }

    /**
     * Primitive Stone Furnace (太古石炉): zero-energy cross-recipe version of
     * vanilla furnace recipes. Structure ported from GTLsupb; multi-recipe
     * parallel behavior uses GTO's native CrossRecipeMultiblockMachine API.
     */
    private static void registerPrimitiveStoneFurnace() {
        MultiblockBuilder builder = MachineRegisterUtils.multiblock(
            "primitive_stone_furnace", "太古石炉",
            StoneFurnaceMachine::new);
        // MAX tier so GTO's native voltage-tier check accepts any furnace recipe;
        // the modifier still zeroes EU, so no real power is required.
        builder.tier(GTValues.MAX)
            .recipeTypes(GTRecipeTypes.FURNACE_RECIPES)
            .recipeModifiers(STONE_FURNACE_ZERO_EU)
            .multipleRecipesTooltips()
            .moduleTooltips(GTOPartAbility.THREAD_HATCH)
            .workableCasingRenderer(
                new ResourceLocation("minecraft", "block/stone"),
                new ResourceLocation("gtocore", "block/multiblock/cosmos_simulation"))
            .allRotation()
            .tooltips(Component.translatable("gtocutcorners.machine.primitive_stone_furnace.tooltip.0"))
            .genLang("太古石炉")
            .multiblockPreviewRenderer(true, true)
            .pattern(def -> stoneBoxPattern(def))
            .register();
        NAMES.add("primitive_stone_furnace");
    }

    private static BlockPattern stoneBoxPattern(MultiblockMachineDefinition def) {
        return FactoryBlockPattern.start()
            .aisle("AAA", "AAA", "AAA")
            .aisle("AAA", "A A", "AAA")
            .aisle("AAA", "A~A", "AAA")
            .where('~', Predicates.controller(def))
            .where('A', Predicates.blocks(Blocks.STONE)
                .or(Predicates.abilities(PartAbility.IMPORT_ITEMS))
                .or(Predicates.abilities(PartAbility.EXPORT_ITEMS))
                .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS))
                .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS))
                .or(GTOPredicates.autoThreadLaserAbilities(def.getRecipeTypes())))
            .where(' ', Predicates.any())
            .build();
    }

    /**
     * Not Hard Box (不难之盒): electric version of the Easy Box, ported from
     * gtecore. Runs the same easy_box recipe type (1 item in -> up to 80 items
     * out) with perfect overclocking and parallel-hatch support.
     */
    private static void registerNotHardBox() {
        MultiblockBuilder builder = MachineRegisterUtils.multiblock(
            "not_hard_box", "不难之盒",
            NotHardBoxMachine::new);
        builder.tier(4)
            .recipeTypes(GTOCRecipeTypes.EASY_BOX)
            .parallelizablePerfectOverclock()
            .allRotation()
            .tooltips(
                Component.translatable("gtocutcorners.machine.not_hard_box.tooltip.0"),
                Component.translatable("gtocutcorners.machine.not_hard_box.tooltip.1"),
                Component.translatable("gtocutcorners.machine.not_hard_box.tooltip.2"))
            .workableCasingRenderer(
                new ResourceLocation("gtceu", "block/casings/solid/machine_casing_solid_steel"),
                new ResourceLocation("gtceu", "block/multiblock/distillation_tower"))
            .genLang("不难之盒")
            .multiblockPreviewRenderer(true, true)
            .pattern(def -> notHardBoxPattern(def))
            .register();
        NAMES.add("not_hard_box");
    }

    private static BlockPattern notHardBoxPattern(MultiblockMachineDefinition def) {
        return FactoryBlockPattern.start()
            .aisle("BBB", "BBB", "BBB")
            .aisle("BBB", "BAB", "BBB")
            .aisle("BBB", "B#B", "BBB")
            .where('B', Predicates.blocks(GTBlocks.CASING_STEEL_SOLID.get())
                .or(Predicates.autoAbilities(def.getRecipeTypes()))
                .or(Predicates.abilities(PartAbility.INPUT_LASER))
                .or(Predicates.abilities(PartAbility.PARALLEL_HATCH)))
            .where('A', Predicates.any())
            .where('#', Predicates.controller(def))
            .build();
    }

    @SuppressWarnings("unchecked")
    private static Function<MetaMachineBlockEntity, ? extends MultiblockControllerMachine> machineFactory(Object factory) {
        return (Function<MetaMachineBlockEntity, ? extends MultiblockControllerMachine>) factory;
    }

    /**
     * Programmatic structure mirroring GTOHJS: every symbol is mapped to a real
     * block predicate, so the pattern is valid for both structure formation and
     * the EMI/JEI preview bake (the native .mbs reader path produced an
     * incomplete pattern that crashed GTO's client-side EMI data init).
     */
    private static BlockPattern commonPattern(MultiblockMachineDefinition def) {
        return FactoryBlockPattern.start()
            .aisle("AAA", "AAA", "AAA")
            .aisle("AAA", "ABA", "AAA")
            .aisle("AAA", "AAA", "AAA")
            .where('A', Predicates.blocks(GTBlocks.CASING_STEEL_SOLID.get()))
            .where('B', Predicates.controller(def))
            .build();
    }
}
