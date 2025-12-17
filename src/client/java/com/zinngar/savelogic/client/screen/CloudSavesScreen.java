package com.zinngar.savelogic.client.screen;

import com.zinngar.savelogic.client.CloudSaveOperation;
import com.zinngar.savelogic.client.SaveLogicClient;
import com.zinngar.savelogic.GoogleDriveProvider;
import com.zinngar.savelogic.OperationType;
import com.zinngar.savelogic.SaveLogic;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public class CloudSavesScreen extends Screen {

    private final Screen parent;

    public CloudSavesScreen(Screen parent) {
        super(Text.literal("Cloud Saves"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();

        int l = this.height / 4 + 48;

        this.addDrawableChild(ButtonWidget.builder(Text.translatable("menu.cloud_saves.save"), button -> {
            SaveLogicClient.startCloudSave(this, OperationType.SAVE);
            this.client.setScreen(new SelectWorldScreen(this));
        }).dimensions(this.width / 2 - 100, l, 200, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.translatable("menu.cloud_saves.load"), button -> {
            this.client.setScreen(new SaveLogicSelectionScreen(new CloudSaveOperation(OperationType.LOAD, this)));
        }).dimensions(this.width / 2 - 100, l + 24, 200, 20).build());

        if ("google".equalsIgnoreCase(SaveLogic.CONFIG.provider) &&
            (SaveLogic.CONFIG.google.refreshToken == null || SaveLogic.CONFIG.google.refreshToken.isEmpty())) {

            this.addDrawableChild(ButtonWidget.builder(Text.literal("Login with Google Drive"), button -> {
                new GoogleDriveProvider().initiateAuthorization();
            }).dimensions(this.width / 2 - 100, l + 48, 200, 20).build());
        }

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Back"), button -> {
            this.client.setScreen(parent);
        }).dimensions(this.width / 2 - 100, this.height - 30, 200, 20).build());
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }
}
