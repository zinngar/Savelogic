package com.zinngar.savelogic.client.screen;

import com.zinngar.savelogic.GitHubStorageProvider;
import com.zinngar.savelogic.GitHubStorageProvider.CloudSave;
import com.zinngar.savelogic.client.CloudStorageManager;
import com.zinngar.savelogic.client.CloudSync;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.concurrent.CompletionException;

/** Lists the worlds stored in the cloud and downloads the one that is clicked. */
public class SaveLogicSelectionScreen extends Screen {

    private static final int ROW_HEIGHT = 24;

    private final Screen parent;
    private List<CloudSave> saves = List.of();
    private boolean busy = true;
    private String status = "Loading cloud saves...";
    private int scroll = 0;

    public SaveLogicSelectionScreen(Screen parent) {
        super(Component.translatable("savelogic.menu.load"));
        this.parent = parent;
    }

    @Override
    public void added() {
        super.added();
        GitHubStorageProvider provider = CloudStorageManager.getProvider();
        if (provider == null) {
            busy = false;
            status = Component.translatable("savelogic.message.not_configured").getString();
            return;
        }
        provider.listSaves().whenComplete((result, error) -> this.minecraft.execute(() -> {
            busy = false;
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                status = "Could not load cloud saves: " + cause.getMessage();
            } else {
                saves = result;
                status = result.isEmpty() ? "No cloud saves yet." : null;
            }
            this.rebuildWidgets();
        }));
    }

    @Override
    protected void init() {
        int visibleRows = Math.max(1, (this.height - 110) / ROW_HEIGHT);
        scroll = Math.max(0, Math.min(scroll, saves.size() - visibleRows));

        int y = 44;
        for (int i = scroll; i < Math.min(saves.size(), scroll + visibleRows); i++) {
            CloudSave save = saves.get(i);
            Button button = this.addRenderableWidget(Button.builder(
                    Component.literal(save.worldName() + "  (" + formatSize(save.size()) + ")"),
                    b -> download(save)).bounds(this.width / 2 - 125, y, 250, 20).build());
            button.active = !busy;
            y += ROW_HEIGHT;
        }

        if (saves.size() > visibleRows) {
            Button up = this.addRenderableWidget(Button.builder(Component.literal("▲"), b -> {
                scroll--;
                this.rebuildWidgets();
            }).bounds(this.width / 2 + 130, 44, 20, 20).build());
            up.active = scroll > 0;
            Button down = this.addRenderableWidget(Button.builder(Component.literal("▼"), b -> {
                scroll++;
                this.rebuildWidgets();
            }).bounds(this.width / 2 + 130, 44 + (visibleRows - 1) * ROW_HEIGHT, 20, 20).build());
            down.active = scroll + visibleRows < saves.size();
        }

        this.addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> this.onClose())
                .bounds(this.width / 2 - 100, this.height - 30, 200, 20).build());
    }

    private void download(CloudSave save) {
        busy = true;
        status = "Downloading " + save.worldName() + " (" + formatSize(save.size()) + ")...";
        this.rebuildWidgets();
        CloudSync.downloadWorld(save, this::onClose, error -> {
            busy = false;
            status = "Download failed: " + error;
            this.rebuildWidgets();
        });
    }

    private static String formatSize(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) {
            return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(this.font, this.title, this.width / 2, 15, 0xFFFFFFFF);
        if (status != null) {
            boolean failed = status.startsWith("Could not") || status.startsWith("Download failed");
            graphics.centeredText(this.font, status, this.width / 2, 28, failed ? 0xFFFF6060 : 0xFFA0A0A0);
        }
    }
}
