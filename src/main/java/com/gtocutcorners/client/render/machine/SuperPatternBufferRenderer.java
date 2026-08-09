package com.gtocutcorners.client.render.machine;

import com.gregtechceu.gtceu.client.renderer.machine.OverlayTieredMachineRenderer;
import com.gtolib.GTOCore;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;

/**
 * GTO keeps a texture-existence index whose whitelisted namespaces are
 * gtceu/gtocore/gtmthings; models load through the normal resource manager.
 * The body therefore uses a custom casing texture shipped by this mod under
 * {@code assets/gtocore/textures/...} (whitelisted namespace, unique path), so
 * the next cache rebuild records it in the index. The front panel reuses
 * GTOCore's red ME pattern-buffer overlay.
 */
public final class SuperPatternBufferRenderer extends OverlayTieredMachineRenderer {

    private static final ResourceLocation CASING_MODEL =
        GTOCore.id("block/machine/part/me_super_pattern_buffer_body");
    private static final ResourceLocation CASING_TEXTURE =
        GTOCore.id("block/casings/me_super_pattern_buffer_casing");

    public SuperPatternBufferRenderer(int tier, ResourceLocation overlayModel) {
        super(tier, overlayModel);
        updateModelWithoutReloadingResource(CASING_MODEL);
        setTextureOverride(Map.of(
            "all", CASING_TEXTURE,
            "side", CASING_TEXTURE));
    }
}
