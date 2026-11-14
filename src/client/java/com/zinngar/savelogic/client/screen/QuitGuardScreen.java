package com.zinngar.savelogic.client.screen;

import com.zinngar.savelogic.client.QuitGuard;
import com.zinngar.savelogic.client.TransferStatus;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Shown while Minecraft waits for a cloud save before closing; see {@link QuitGuard}. The progress bar is on top. */
public class QuitGuardScreen extends Screen {

    private final boolean failed;

    public QuitGuardScreen() {
        super(Component.literal("Finishing the cloud save"));
        this.failed = QuitGuard.state() == QuitGuard.State.FAILED;
    }

    @Override
    protected void init() {
        this.addRenderableWidget(Button.builder(Component.literal(failed ? "Quit Anyway" : "Quit Now (lose this upload)")
                        .withStyle(ChatFormatting.RED), b -> QuitGuard.quit(this.minecraft))
                .bounds(this.width / 2 - 154, this.height / 2 + 24, 150, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal(failed ? "Back to Menu" : "Keep Playing"), b -> this.onClose())
                .bounds(this.width / 2 + 4, this.height / 2 + 24, 150, 20).build());
    }

    @Override
    public void onClose() {
        QuitGuard.keepPlaying(this.minecraft);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false; // Too easy to hit by accident; use a button.
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        int y = this.height / 2 - 30;
        if (failed) {
            graphics.centeredText(this.font, Component.literal("The cloud save didn't finish").withStyle(ChatFormatting.RED), this.width / 2, y, 0xFFFFFFFF);
            String result = TransferStatus.INSTANCE.lastResult();
            graphics.textWithWordWrap(this.font, Component.literal(result == null ? "" : result), this.width / 2 - 150, y + 16, 300, 0xFFA0A0A0);
        } else {
            graphics.centeredText(this.font, this.title, this.width / 2, y, 0xFFFFFFFF);
            graphics.centeredText(this.font, Component.literal("Minecraft will close as soon as it's done."), this.width / 2, y + 16, 0xFFA0A0A0);
            graphics.centeredText(this.font, Component.literal("Quitting now loses this upload. The previous cloud version stays safe."), this.width / 2, y + 30, 0xFFA0A0A0);
        }
    }
}
