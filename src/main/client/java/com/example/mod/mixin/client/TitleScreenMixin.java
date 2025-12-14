package com.example.mod.mixin.client;

import com.example.mod.CloudSaves;
import com.example.mod.CloudSavesClient;
import com.example.mod.OperationType;
import com.example.mod.client.screen.CloudSaveSelectionScreen;
import com.example.mod.GoogleDriveProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.world.SelectWorldScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.example.mod.CloudSaveOperation;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {

    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        int y = this.height / 4 + 48;

        // Save Button
        this.addRenderableWidget(
            Button.builder(
                Component.translatable("menu.savelogic.save"),
                button -> {
                    CloudSavesClient.isSavingOperation = true;
                    CloudSavesClient.parentScreen = this;
                    this.minecraft.setScreen(new SelectWorldScreen(this));
                }
            ).bounds(this.width / 2 - 100, y + 24 * 2, 200, 20).build()
        );

        // Load Button
        this.addRenderableWidget(
            Button.builder(
                Component.translatable("menu.savelogic.load"),
                button -> this.minecraft.setScreen(new CloudSaveSelectionScreen(new CloudSaveOperation(OperationType.LOAD, this)))
            ).bounds(this.width / 2 - 100, y + 24 * 3, 200, 20).build()
        );

        // Google Login Button
        if ("google".equalsIgnoreCase(CloudSaves.CONFIG.provider)
                && (CloudSaves.CONFIG.google.refreshToken == null
                || CloudSaves.CONFIG.google.refreshToken.isEmpty())) {
            this.addRenderableWidget(
                Button.builder(Component.literal("Login with Google Drive"), button -> {
                    new GoogleDriveProvider().initiateAuthorization();
                }).bounds(this.width / 2 - 100, y + 24 * 4, 200, 20).build()
            );
        }
    }
}
