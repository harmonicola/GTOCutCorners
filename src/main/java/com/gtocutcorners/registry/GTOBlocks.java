package com.gtocutcorners.registry;

import com.gtocutcorners.GTOCutCorners;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Blocks registered by GTOCutCorners itself.
 * Machine blocks are intentionally NOT registered here: multiblock machines are
 * created through the private GTRegistrate (see GTOCMultiblocks) so they
 * appear under the gtocore namespace like native GTO content.
 */
public final class GTOBlocks {

    public static final DeferredRegister<Block> BLOCKS =
        DeferredRegister.create(ForgeRegistries.BLOCKS, GTOCutCorners.MODID);

    public static final RegistryObject<Block> INTEGRAL_BRONZE_FRAMEWORK = BLOCKS.register(
        "integral_bronze_framework",
        () -> new Block(BlockBehaviour.Properties.of()
            .mapColor(MapColor.METAL)
            .strength(5.0F, 6.0F)
            .sound(SoundType.METAL)
            .requiresCorrectToolForDrops()));

    private GTOBlocks() {
    }
}
