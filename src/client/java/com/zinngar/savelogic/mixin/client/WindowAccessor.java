package com.zinngar.savelogic.mixin.client;

import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Window.class)
public interface WindowAccessor {

    /** Clears a request to close the window, so the game isn't asked to quit again every frame. */
    @Accessor("shouldClose")
    void savelogic$setShouldClose(boolean shouldClose);
}
