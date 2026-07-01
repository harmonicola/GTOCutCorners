package com.gtocutcorners.mixin;

import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gtocutcorners.GTOCutCorners;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin for RecipeLogic instance registration only.
 * Duration patching is handled by JVMTI bytecode injection (jvmti_patch.c).
 */
@Mixin(value = RecipeLogic.class, remap = false)
public abstract class RecipeLogicMixin {

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void gtocutcorners$onConstruct(CallbackInfo ci) {
        GTOCutCorners.jlog("[Mixin] RecipeLogic construct: " + this.getClass().getSimpleName());
        // 仅在 native 模式下向 C 链表注册 RecipeLogic
        if (!GTOCutCorners.nativeModeActive) {
            GTOCutCorners.jlog("[Mixin] nativeMode=false, skipping native registration");
            return;
        }
        try {
            GTOCutCorners.nativeRegisterRecipeLogic(this);
        } catch (Throwable t) {
            GTOCutCorners.jlog("[Mixin] register err: " + t.getMessage());
        }
    }

    @Inject(method = "onMachineUnLoad", at = @At("HEAD"), remap = false)
    private void gtocutcorners$onUnload(CallbackInfo ci) {
        if (!GTOCutCorners.nativeModeActive) return;
        try {
            GTOCutCorners.nativeUnregisterRecipeLogic(this);
        } catch (Throwable ignored) {
        }
    }
}
