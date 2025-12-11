package com.example.mod.client.screen;

import com.example.mod.CloudSaveOperation;
import com.example.mod.CloudSaves;
import com.example.mod.CloudStorageProvider;
import com.example.mod.GitHubStorageProvider;
import com.example.mod.GoogleDriveProvider;
import com.example.mod.util.ZipUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class CloudSaveSelectionScreen extends Screen {

    private final CloudSaveOperation operation;
    private List<String> saves = new ArrayList<>();
    private CloudStorageProvider provider;

    public CloudSaveSelectionScreen(CloudSaveOperation operation) {
        super(Component.literal("Select Cloud Save to Load"));
        this.operation = operation;

        if (CloudSaves.CONFIG.provider.equalsIgnoreCase("github")) {
            this.provider = new GitHubStorageProvider();
        } else {
            this.provider = new GoogleDriveProvider();
        }
    }

    @Override
    protected void init() {
        provider.listSaves().thenAccept(saves -> {
            this.saves = saves;
            this.clearWidgets();
            addButtons();
        });

        addButtons();
    }

    private void addButtons() {
        int y = 40;
        for (String saveName : saves) {
            this.addRenderableWidget(
                Button.builder(
                        Component.literal(saveName),
                        btn -> onSaveSelected(saveName)
                ).bounds(this.width / 2 - 100, y, 200, 20).build()
            );
            y += 24;
        }

        this.addRenderableWidget(
            Button.builder(
                    Component.literal("Back"),
                    btn -> this.minecraft.setScreen(operation.parentScreen)
            ).bounds(this.width / 2 - 100, this.height - 30, 200, 20).build()
        );
    }

    private void onSaveSelected(String saveName) {
        provider.downloadSave(saveName).thenAccept(file -> {
            if (file != null) {
                try {
                    File savesDir = Minecraft.getInstance().gameDirectory.toPath().resolve("saves").toFile();
                    ZipUtil.unzip(file, savesDir);
                    CloudSaves.LOGGER.info("Unzipped save: " + saveName);
                } catch (Exception e) {
                    CloudSaves.LOGGER.error("Failed to unzip save", e);
                }
            }
        });
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(operation.parentScreen);
    }

    @Override
    public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredString(this.font, this.title, this.width / 2, 15, 16777215);
    }
}
