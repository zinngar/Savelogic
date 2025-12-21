package com.zinngar.savelogic.mixin;

import com.zinngar.savelogic.client.screen.CloudSavesScreen;
import com.zinngar.savelogic.client.screen.SettingsScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {

    protected TitleScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        // Find the lowest button on the screen to position our buttons below it.
        int lowestButtonY = 0;
        for (net.minecraft.client.gui.Element element : this.children()) {
            if (element instanceof ButtonWidget) {
                ButtonWidget button = (ButtonWidget) element;
                lowestButtonY = Math.max(lowestButtonY, button.getY());
            }
        }

        // Default position if no buttons are found (fallback)
        if (lowestButtonY == 0) {
            lowestButtonY = this.height / 4 + 48 + 72;
        }

        int buttonY = lowestButtonY + 24; // 24 pixels below the lowest button.
        int buttonHeight = 20;
        int buttonSpacing = 4;

        this.addDrawableChild(ButtonWidget.builder(Text.translatable("savelogic.menu.load_from_cloud"), button -> {
            this.client.setScreen(new CloudSavesScreen(this));
        }).dimensions(this.width / 2 - 100, buttonY, 200, buttonHeight).build());

        this.addDrawableChild(ButtonWidget.builder(Text.translatable("savelogic.menu.settings"), button -> {
            this.client.setScreen(new SettingsScreen(this));
        }).dimensions(this.width / 2 - 100, buttonY + buttonHeight + buttonSpacing, 200, buttonHeight).build());
    }
}
