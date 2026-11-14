package com.zinngar.savelogic.mixin.client;

import com.zinngar.savelogic.client.QuitGuard;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    /** Both the Quit Game button and closing the window end up here. */
    @Inject(method = "stop", at = @At("HEAD"), cancellable = true)
    private void savelogic$waitForCloudSave(CallbackInfo ci) {
        if (QuitGuard.holdQuit((Minecraft) (Object) this)) {
            ci.cancel();
        }
    }
}
