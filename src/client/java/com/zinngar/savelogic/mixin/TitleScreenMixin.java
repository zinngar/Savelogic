package com.zinngar.savelogic.mixin;

import com.zinngar.savelogic.client.SaveLogicClient;
import com.zinngar.savelogic.OperationType;
import com.zinngar.savelogic.client.CloudSaveOperation;
import com.zinngar.savelogic.GoogleDriveProvider;
import com.zinngar.savelogic.client.screen.SaveLogicSelectionScreen;
import com.zinngar.savelogic.SaveLogic;

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
public abstract class TitleScreenMixin extends Screen {

    protected TitleScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        int l = this.height / 4 + 48;

        this.addDrawableChild(ButtonWidget.builder(Text.translatable("menu.cloud_saves.save"), button -> {
            SaveLogicClient.startCloudSave(this, OperationType.SAVE);
            this.client.setScreen(new SelectWorldScreen(this));
        }).dimensions(this.width / 2 - 100, l + 24, 200, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.translatable("menu.cloud_saves.load"), button -> {
            this.client.setScreen(new SaveLogicSelectionScreen(new CloudSaveOperation(OperationType.LOAD, this)));
        }).dimensions(this.width / 2 - 100, l + 48, 200, 20).build());

        if ("google".equalsIgnoreCase(SaveLogic.CONFIG.provider) &&
            (SaveLogic.CONFIG.google.refreshToken == null || SaveLogic.CONFIG.google.refreshToken.isEmpty())) {

            this.addDrawableChild(ButtonWidget.builder(Text.literal("Login with Google Drive"), button -> {
                new GoogleDriveProvider().initiateAuthorization();
            }).dimensions(this.width / 2 - 100, l + 72, 200, 20).build());
        }
    }
}
