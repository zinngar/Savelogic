package com.zinngar.savelogic.client.screen;

import com.zinngar.savelogic.GitHubStorageProvider;
import com.zinngar.savelogic.GitHubStorageProvider.CloudSave;
import com.zinngar.savelogic.GitHubStorageProvider.CloudWorld;
import com.zinngar.savelogic.client.CloudStorageManager;
import com.zinngar.savelogic.client.CloudSync;
import com.zinngar.savelogic.client.CloudText;
import com.zinngar.savelogic.client.SyncGate;
import com.zinngar.savelogic.client.TransferStatus;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.concurrent.CompletionException;

/**
 * Lists the worlds in the cloud. Clicking a world downloads its latest version; "Versions" lists older ones.
 * Subclasses list something else (see {@link VersionsScreen}).
 */
public class SaveLogicSelectionScreen extends Screen {

    private static final int ROW_HEIGHT = 24;

    private final Screen parent;
    private List<CloudWorld> worlds = List.of();
    private boolean loading = true;
    private String status = "Loading cloud saves...";
    private boolean statusIsError;
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
            loading = false;
            setStatus(Component.translatable("savelogic.message.not_configured").getString(), true);
            return;
        }
        provider.listWorlds().whenComplete((result, error) -> this.minecraft.execute(() -> {
            loading = false;
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                setStatus("Could not load cloud saves: " + cause.getMessage(), true);
            } else {
                worlds = result;
                SyncGate.rememberCloudWorlds(result);
                setStatus(result.isEmpty() ? "No cloud saves yet." : null, false);
            }
            this.rebuildWidgets();
        }));
    }

    private void setStatus(String text, boolean error) {
        this.status = text;
        this.statusIsError = error;
    }

    @Override
    protected void init() {
        int visibleRows = Math.max(1, (this.height - 100) / ROW_HEIGHT);
        scroll = Math.max(0, Math.min(scroll, worlds.size() - visibleRows));
        boolean idle = !loading && !TransferStatus.INSTANCE.isActive();

        int left = this.width / 2 - 155;
        int y = 44;
        for (int i = scroll; i < Math.min(worlds.size(), scroll + visibleRows); i++) {
            CloudWorld world = worlds.get(i);
            CloudSave newest = world.newest();
            String label = world.worldName() + "  ·  played " + CloudText.when(newest.played()) + "  ·  "
                    + CloudText.device(newest.device());
            Button open = this.addRenderableWidget(Button.builder(Component.literal(label), b -> download(world, newest))
                    .bounds(left, y, 232, 20)
                    .tooltip(Tooltip.create(Component.literal(world.lock() != null
                            ? "In use on " + CloudText.device(world.lock().device()) + ". Click to download the latest version."
                            : "Download the latest version")))
                    .build());
            open.active = idle;
            Button versions = this.addRenderableWidget(Button.builder(
                            Component.literal("Versions (" + world.versions().size() + ")"),
                            b -> this.minecraft.setScreen(new VersionsScreen(this, world)))
                    .bounds(left + 236, y, 74, 20).build());
            versions.active = idle;
            y += ROW_HEIGHT;
        }

        if (worlds.size() > visibleRows) {
            Button up = this.addRenderableWidget(Button.builder(Component.literal("▲"), b -> {
                scroll--;
                this.rebuildWidgets();
            }).bounds(left + 314, 44, 20, 20).build());
            up.active = scroll > 0;
            Button down = this.addRenderableWidget(Button.builder(Component.literal("▼"), b -> {
                scroll++;
                this.rebuildWidgets();
            }).bounds(left + 314, 44 + (visibleRows - 1) * ROW_HEIGHT, 20, 20).build());
            down.active = scroll + visibleRows < worlds.size();
        }

        this.addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> this.onClose())
                .bounds(this.width / 2 - 100, this.height - 28, 200, 20).build());
    }

    void download(CloudWorld world, CloudSave version) {
        CloudSync.downloadWorld(world, version, this, folder -> this.onClose());
        this.rebuildWidgets();
    }

    @Override
    public void tick() {
        super.tick();
        // Re-enable the rows when a transfer finishes (or report one that failed while we waited).
        boolean idle = !TransferStatus.INSTANCE.isActive();
        if (idle != wasIdle) {
            wasIdle = idle;
            this.rebuildWidgets();
        }
    }

    private boolean wasIdle = !TransferStatus.INSTANCE.isActive();

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 15, 0xFFFFFFFF);
        if (status != null) {
            graphics.drawCenteredString(this.font, this.font.plainSubstrByWidth(status, this.width - 20), this.width / 2, 28,
                    statusIsError ? 0xFFFF6060 : 0xFFA0A0A0);
        }
    }
}
