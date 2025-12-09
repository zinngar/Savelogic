package com.example.mod.mixin;

import com.example.mod.CloudSaveOperation;
import com.example.mod.CloudSaves;
import com.example.mod.GoogleDriveProvider;
import com.example.mod.OperationType;
import com.example.mod.client.screen.CloudSaveSelectionScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public class TitleScreenMixin extends Screen {
    protected TitleScreenMixin(Text title) {
        super(title);
    }

    @Inject(at = @At("HEAD"), method = "init()V")
    private void init(CallbackInfo ci) {
        int l = this.height / 4 + 48;
        this.addDrawableChild(ButtonWidget.builder(Text.translatable("menu.cloud_saves.save"), button -> {
            CloudSaves.isSavingOperation = true;
            CloudSaves.parentScreen = this;
            this.client.setScreen(new SelectWorldScreen(this));
        }).dimensions(this.width / 2 - 100, l + 24, 200, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.translatable("menu.cloud_saves.load"), button -> {
            this.client.setScreen(new CloudSaveSelectionScreen(new CloudSaveOperation(OperationType.LOAD, this)));
        }).dimensions(this.width / 2 - 100, l + 48, 200, 20).build());

        if ("google".equalsIgnoreCase(CloudSaves.CONFIG.provider) &&
            (CloudSaves.CONFIG.google.refreshToken == null || CloudSaves.CONFIG.google.refreshToken.isEmpty())) {

            this.addDrawableChild(ButtonWidget.builder(Text.literal("Login with Google Drive"), button -> {
                new GoogleDriveProvider().initiateAuthorization();
            }).dimensions(this.width / 2 - 100, l + 72, 200, 20).build());
        }
    }
}
