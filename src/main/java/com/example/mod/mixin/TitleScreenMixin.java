package com.example.mod.mixin;

import com.example.mod.CloudSaveOperation;
import com.example.mod.CloudSaves;
import com.example.mod.GoogleDriveProvider;
import com.example.mod.OperationType;
import com.example.mod.client.screen.CloudSaveSelectionScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public class TitleScreenMixin extends Screen {
    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(at = @At("HEAD"), method = "init()V")
    private void init(CallbackInfo ci) {
        int l = this.height / 4 + 48;
        this.addRenderableWidget(Button.builder(Component.translatable("menu.cloud_saves.save"), button -> {
            CloudSaves.isSavingOperation = true;
            CloudSaves.parentScreen = this;
            this.minecraft.setScreen(new SelectWorldScreen(this));
        }).bounds(this.width / 2 - 100, l + 24, 200, 20).build());

        this.addRenderableWidget(Button.builder(Component.translatable("menu.cloud_saves.load"), button -> {
            this.minecraft.setScreen(new CloudSaveSelectionScreen(new CloudSaveOperation(OperationType.LOAD, this)));
        }).bounds(this.width / 2 - 100, l + 48, 200, 20).build());

        if ("google".equalsIgnoreCase(CloudSaves.CONFIG.provider) &&
            (CloudSaves.CONFIG.google.refreshToken == null || CloudSaves.CONFIG.google.refreshToken.isEmpty())) {

            this.addRenderableWidget(Button.builder(Component.literal("Login with Google Drive"), button -> {
                new GoogleDriveProvider().initiateAuthorization();
            }).bounds(this.width / 2 - 100, l + 72, 200, 20).build());
        }
    }
}
