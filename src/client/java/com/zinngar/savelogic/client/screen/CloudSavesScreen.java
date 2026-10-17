package com.zinngar.savelogic.client.screen;

import com.zinngar.savelogic.client.CloudStorageManager;
import com.zinngar.savelogic.client.SaveLogicClient;
import com.zinngar.savelogic.client.TransferStatus;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;

public class CloudSavesScreen extends Screen {

    private final Screen parent;

    public CloudSavesScreen(Screen parent) {
        super(Component.translatable("savelogic.menu.cloud_saves"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        // Returning here (e.g. pressing Back on the world picker) cancels a pending upload pick.
        SaveLogicClient.uploadPickerScreen = null;

        boolean configured = CloudStorageManager.getProvider() != null;
        int top = this.height / 4 + 24;

        Button saveButton = this.addRenderableWidget(Button.builder(Component.translatable("savelogic.menu.save"), button -> {
            SelectWorldScreen picker = new SelectWorldScreen(this);
            SaveLogicClient.uploadPickerScreen = picker;
            SaveLogicClient.uploadReturnScreen = this;
            this.minecraft.gui.setScreen(picker);
        }).bounds(this.width / 2 - 100, top, 200, 20).build());

        Button loadButton = this.addRenderableWidget(Button.builder(Component.translatable("savelogic.menu.load"),
                button -> this.minecraft.gui.setScreen(new SaveLogicSelectionScreen(this)))
                .bounds(this.width / 2 - 100, top + 24, 200, 20).build());

        // One transfer at a time; the progress bar at the top shows the one that is running.
        boolean idle = !TransferStatus.INSTANCE.isActive();
        saveButton.active = configured && idle;
        loadButton.active = configured && idle;

        this.addRenderableWidget(Button.builder(Component.translatable("savelogic.menu.settings"),
                button -> this.minecraft.gui.setScreen(new SettingsScreen(this)))
                .bounds(this.width / 2 - 100, top + 48, 200, 20).build());

        this.addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> this.onClose())
                .bounds(this.width / 2 - 100, this.height - 30, 200, 20).build());
    }

    @Override
    public void tick() {
        super.tick();
        // Re-enable Save/Load as soon as a running transfer finishes.
        boolean idle = !TransferStatus.INSTANCE.isActive();
        if (idle != wasIdle) {
            wasIdle = idle;
            this.rebuildWidgets();
        }
    }

    private boolean wasIdle = !TransferStatus.INSTANCE.isActive();

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(this.font, this.title, this.width / 2, 20, 0xFFFFFFFF);
        if (CloudStorageManager.getProvider() == null) {
            graphics.centeredText(this.font, Component.translatable("savelogic.message.not_configured"),
                    this.width / 2, this.height / 4 + 24 + 76, 0xFFFF6060);
        }
    }
}
