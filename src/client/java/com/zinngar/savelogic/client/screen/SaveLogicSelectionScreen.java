package com.zinngar.savelogic.client.screen;

import com.zinngar.savelogic.CloudStorageProvider;
import com.zinngar.savelogic.SaveLogic;
import com.zinngar.savelogic.client.CloudSaveOperation;
import com.zinngar.savelogic.client.CloudStorageManager;
import com.zinngar.savelogic.util.ZipUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.io.File;
import java.util.Collections;
import java.util.List;

public class SaveLogicSelectionScreen extends Screen {

    private final CloudSaveOperation operation;
    private List<String> saves = Collections.emptyList();
    private CloudStorageProvider provider;
    private boolean isLoading = true;
    private String errorMessage = null;

    public SaveLogicSelectionScreen(CloudSaveOperation operation) {
        super(Text.literal("Select Cloud Save to Load"));
        this.operation = operation;
        this.provider = CloudStorageManager.getProvider();
    }

    @Override
    protected void init() {
        if (provider == null) {
            isLoading = false;
            errorMessage = "Cloud provider not configured. Please check your settings.";
            addButtons();
            return;
        }

        provider.listSaves().thenAccept(cloudSaves -> {
            this.saves = cloudSaves;
            this.isLoading = false;
            this.clearChildren();
            addButtons();
        }).exceptionally(e -> {
            this.isLoading = false;
            this.errorMessage = "Failed to load saves: " + e.getMessage();
            this.clearChildren();
            addButtons();
            return null;
        });

        addButtons();
    }

    private void addButtons() {
        this.clearChildren();
        int y = 40;
        if (!isLoading && !saves.isEmpty()) {
            for (String saveName : saves) {
                this.addDrawableChild(
                    ButtonWidget.builder(
                        Text.literal(saveName),
                        btn -> onSaveSelected(saveName)
                    ).dimensions(this.width / 2 - 100, y, 200, 20).build()
                );
                y += 24;
            }
        }

        this.addDrawableChild(
            ButtonWidget.builder(
                Text.literal("Back"),
                btn -> this.client.setScreen(operation.parentScreen)
            ).dimensions(this.width / 2 - 100, this.height - 30, 200, 20).build()
        );
    }

    private void onSaveSelected(String saveName) {
        if (provider == null) return;

        if (localSaveExists(saveName.replace(".zip", ""))) {
            this.client.setScreen(new ConfirmOverwriteScreen(this, saveName, () -> downloadAndUnzip(saveName)));
        } else {
            downloadAndUnzip(saveName);
        }
    }

    private void downloadAndUnzip(String saveName) {
        provider.downloadSave(saveName).thenAccept(file -> {
            if (file != null) {
                try {
                    File savesDir = MinecraftClient.getInstance().getLevelStorage().getSavesDirectory().toFile();
                    ZipUtil.unzip(file, savesDir);
                    SaveLogic.LOGGER.info("Unzipped save: " + saveName);
                    this.client.execute(() -> this.client.setScreen(operation.parentScreen));
                } catch (Exception e) {
                    SaveLogic.LOGGER.error("Failed to unzip save", e);
                    this.errorMessage = "Error unzipping save: " + e.getMessage();
                } finally {
                    if (file.exists()) {
                        file.delete();
                    }
                }
            } else {
                this.errorMessage = "Failed to download save.";
            }
        });
    }

    private boolean localSaveExists(String saveName) {
        File savesDir = MinecraftClient.getInstance().getLevelStorage().getSavesDirectory().toFile();
        return new File(savesDir, saveName).exists();
    }

    @Override
    public void close() {
        this.client.setScreen(operation.parentScreen);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 15, 16777215);

        if (isLoading) {
            context.drawCenteredTextWithShadow(this.textRenderer, "Loading saves...", this.width / 2, 60, 16777215);
        } else if (errorMessage != null) {
            context.drawCenteredTextWithShadow(this.textRenderer, errorMessage, this.width / 2, 60, 16711680); // Red
        } else if (saves.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, "No cloud saves found.", this.width / 2, 60, 16777215);
        }
    }
}
