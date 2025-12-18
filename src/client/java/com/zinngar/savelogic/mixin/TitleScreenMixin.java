package com.zinngar.savelogic.mixin;

import com.zinngar.savelogic.client.screen.CloudSavesScreen;
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
        int l = this.height / 4 + 48;

        this.addDrawableChild(ButtonWidget.builder(Text.translatable("savelogic.menu.cloud_saves"), button -> {
            this.client.setScreen(new CloudSavesScreen(this));
        }).dimensions(this.width / 2 - 100, l + 60, 200, 20).build());
    }
}
