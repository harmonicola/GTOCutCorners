package com.gtocutcorners.registry;

import com.gtocutcorners.GTOCutCorners;
import com.gtocutcorners.item.DurationProbeItem;
import com.gtocutcorners.item.MeNetworkScannerItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Items registered by GTOCutCorners itself.
 */
public final class GTOItems {

    public static final DeferredRegister<Item> ITEMS =
        DeferredRegister.create(ForgeRegistries.ITEMS, GTOCutCorners.MODID);

    /** ME tool: right-click any ME network block to inspect stored items/fluids. */
    public static final RegistryObject<Item> ME_NETWORK_SCANNER = ITEMS.register(
        "me_network_scanner",
        () -> new MeNetworkScannerItem(new Item.Properties().stacksTo(1)));

    /** GT tool: right-click a GT machine to inspect its current recipe duration. */
    public static final RegistryObject<Item> RECIPE_DURATION_PROBE = ITEMS.register(
        "recipe_duration_probe",
        () -> new DurationProbeItem(new Item.Properties().stacksTo(1)));

    /** Block item for the decorative framework block. */
    public static final RegistryObject<Item> INTEGRAL_BRONZE_FRAMEWORK = ITEMS.register(
        "integral_bronze_framework",
        () -> new BlockItem(GTOBlocks.INTEGRAL_BRONZE_FRAMEWORK.get(), new Item.Properties()));

    private GTOItems() {
    }
}
