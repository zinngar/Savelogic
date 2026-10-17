package com.zinngar.savelogic.mixin.client;

import com.zinngar.savelogic.client.screen.CloudSavesScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {

    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void savelogic$addCloudSavesButton(CallbackInfo ci) {
        // Place the button just below the lowest vanilla button.
        int lowestBottom = this.height / 4 + 48 + 72 + 20;
        for (GuiEventListener child : this.children()) {
            if (child instanceof Button button) {
                lowestBottom = Math.max(lowestBottom, button.getBottom());
            }
        }

        this.addRenderableWidget(Button.builder(Component.translatable("savelogic.menu.cloud_saves"),
                        button -> this.minecraft.gui.setScreen(new CloudSavesScreen(this)))
                .bounds(this.width / 2 - 100, Math.min(lowestBottom + 4, this.height - 24), 200, 20)
                .build());
    }
}
