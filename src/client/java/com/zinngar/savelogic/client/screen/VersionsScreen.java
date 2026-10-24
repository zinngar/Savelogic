package com.zinngar.savelogic.client.screen;

import com.zinngar.savelogic.GitHubStorageProvider.CloudSave;
import com.zinngar.savelogic.GitHubStorageProvider.CloudWorld;
import com.zinngar.savelogic.SaveLogic;
import com.zinngar.savelogic.client.CloudSync;
import com.zinngar.savelogic.client.CloudText;
import com.zinngar.savelogic.client.TransferStatus;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Every version of one cloud world, newest first. Clicking one downloads (restores) it. */
public class VersionsScreen extends Screen {

    private final Screen parent;
    private final CloudWorld world;
    private boolean wasIdle = !TransferStatus.INSTANCE.isActive();

    public VersionsScreen(Screen parent, CloudWorld world) {
        super(Component.literal("Versions of \"" + world.worldName() + "\""));
        this.parent = parent;
        this.world = world;
    }

    @Override
    protected void init() {
        boolean idle = !TransferStatus.INSTANCE.isActive();
        int y = 48;
        int maxRows = Math.max(1, (this.height - 100) / 24);
        for (int i = 0; i < Math.min(world.versions().size(), maxRows); i++) {
            CloudSave version = world.versions().get(i);
            String label = (i == 0 ? "Latest: " : "") + "played " + CloudText.when(version.played()) + "  ·  "
                    + CloudText.device(version.device()) + "  (" + (version.isFull() ? "full copy " : "changes ")
                    + CloudText.size(version.size()) + ")";
            Button button = this.addRenderableWidget(Button.builder(Component.literal(label),
                            b -> CloudSync.downloadWorld(world, version, this, folder -> this.minecraft.gui.setScreen(parent)))
                    .bounds(this.width / 2 - 140, y, 280, 20).build());
            button.active = idle;
            y += 24;
        }
        this.addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> this.onClose())
                .bounds(this.width / 2 - 100, this.height - 28, 200, 20).build());
    }

    @Override
    public void tick() {
        super.tick();
        boolean idle = !TransferStatus.INSTANCE.isActive();
        if (idle != wasIdle) {
            wasIdle = idle;
            this.rebuildWidgets();
        }
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(this.font, this.title, this.width / 2, 15, 0xFFFFFFFF);
        graphics.centeredText(this.font, Component.literal("Click a version to download it. Keeping the newest "
                + SaveLogic.CONFIG.keepVersions + "."), this.width / 2, 30, 0xFFA0A0A0);
    }
}
