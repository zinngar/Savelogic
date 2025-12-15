package com.example.mod.client.screen;

import com.example.mod.client.CloudSaveOperation;
import com.example.mod.CloudSaves;
import com.example.mod.CloudStorageProvider;
import com.example.mod.GitHubStorageProvider;
import com.example.mod.GoogleDriveProvider;
import com.example.mod.util.ZipUtil;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class CloudSaveSelectionScreen extends Screen {

    private final CloudSaveOperation operation;
    private List<String> saves = new ArrayList<>();
    private CloudStorageProvider provider;

    public CloudSaveSelectionScreen(CloudSaveOperation operation) {
        super(Text.literal("Select Cloud Save to Load"));
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
            this.clearChildren();
            addButtons();
        });

        addButtons();
    }

    private void addButtons() {
        int y = 40;
        for (String saveName : saves) {
            this.addDrawableChild(
                ButtonWidget.builder(
                        Text.literal(saveName),
                        btn -> onSaveSelected(saveName)
                ).dimensions(this.width / 2 - 100, y, 200, 20).build()
            );
            y += 24;
        }

        this.addDrawableChild(
            ButtonWidget.builder(
                    Text.literal("Back"),
                    btn -> this.client.setScreen(operation.parentScreen)
            ).dimensions(this.width / 2 - 100, this.height - 30, 200, 20).build()
        );
    }

    private void onSaveSelected(String saveName) {
        provider.downloadSave(saveName).thenAccept(file -> {
            if (file != null) {
                try {
                    File savesDir = MinecraftClient.getInstance().getLevelStorage().getSavesDirectory().toFile();
                    ZipUtil.unzip(file, savesDir);
                    CloudSaves.LOGGER.info("Unzipped save: " + saveName);
                } catch (Exception e) {
                    CloudSaves.LOGGER.error("Failed to unzip save", e);
                }
            }
        });
    }

    @Override
    public void close() {
        this.client.setScreen(operation.parentScreen);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 15, 16777215);
    }
}
