package com.gtocutcorners.registry;

import com.gtocutcorners.GTOCutCorners;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * Creative tab for GTOCutCorners own items.
 */
public final class GTOCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> TABS =
        DeferredRegister.create(Registries.CREATIVE_MODE_TAB, GTOCutCorners.MODID);

    public static final RegistryObject<CreativeModeTab> MAIN = TABS.register("main", () ->
        CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.gtocutcorners"))
            .icon(() -> new ItemStack(GTOItems.ME_NETWORK_SCANNER.get()))
            .displayItems((params, output) -> {
                output.accept(GTOItems.ME_NETWORK_SCANNER.get());
                output.accept(GTOItems.RECIPE_DURATION_PROBE.get());
                output.accept(GTOItems.INTEGRAL_BRONZE_FRAMEWORK.get());
            })
            .build());

    private GTOCreativeTabs() {
    }
}
