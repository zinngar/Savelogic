package com.zinngar.savelogic.client.screen;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.client.gui.DrawContext;

public class ConfirmOverwriteScreen extends Screen {

    private final Screen parent;
    private final String saveName;
    private final Runnable onConfirm;

    public ConfirmOverwriteScreen(Screen parent, String saveName, Runnable onConfirm) {
        super(Text.literal("Confirm Overwrite"));
        this.parent = parent;
        this.saveName = saveName;
        this.onConfirm = onConfirm;
    }

    @Override
    protected void init() {
        super.init();

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Overwrite"), button -> {
            this.onConfirm.run();
            this.client.setScreen(this.parent);
        }).dimensions(this.width / 2 - 102, this.height / 2 + 20, 100, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), button -> {
            this.client.setScreen(this.parent);
        }).dimensions(this.width / 2 + 2, this.height / 2 + 20, 100, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, this.height / 2 - 30, 16777215);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("A local save named '" + this.saveName + "' already exists."), this.width / 2, this.height / 2 - 10, 16777215);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Do you want to overwrite it?"), this.width / 2, this.height / 2, 16777215);
    }

    @Override
    public void close() {
        this.client.setScreen(this.parent);
    }
}
