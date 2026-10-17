package com.zinngar.savelogic.client.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Shown briefly while checking the cloud before opening a synced world. */
public class SyncCheckScreen extends Screen {

    private final Runnable onCancel;
    private boolean cancelled;

    public SyncCheckScreen(String worldName, Runnable onCancel) {
        super(Component.literal("Checking the cloud for \"" + worldName + "\"..."));
        this.onCancel = onCancel;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    protected void init() {
        this.addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> this.onClose())
                .bounds(this.width / 2 - 100, this.height / 2 + 20, 200, 20).build());
    }

    @Override
    public void onClose() {
        cancelled = true;
        onCancel.run();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 10, 0xFFFFFFFF);
    }
}
