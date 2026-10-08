package com.github.Jesper_Andersson.biomepicknchoose.mixin;

import com.github.Jesper_Andersson.biomepicknchoose.client.preview.BiomePreviewCapture;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    // Where NeoForge fires RenderFrameEvent.Post: right after the frame is rendered
    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V", shift = At.Shift.AFTER))
    private void bpnc$afterRender(boolean renderLevel, CallbackInfo ci) {
        BiomePreviewCapture.onRenderFrame();
    }
}
