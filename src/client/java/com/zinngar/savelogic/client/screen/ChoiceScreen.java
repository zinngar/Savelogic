package com.zinngar.savelogic.client.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/** A question with a short explanation and a stack of answer buttons. Escape picks the last (cancel) answer. */
public class ChoiceScreen extends Screen {

    public record Choice(Component label, Runnable action) {}

    private final Component message;
    private final List<Choice> choices;
    private List<FormattedCharSequence> lines = List.of();

    public ChoiceScreen(Component title, Component message, List<Choice> choices) {
        super(title);
        this.message = message;
        this.choices = choices;
    }

    @Override
    protected void init() {
        lines = this.font.split(message, Math.min(320, this.width - 40));
        int y = buttonsTop();
        for (Choice choice : choices) {
            this.addRenderableWidget(Button.builder(choice.label(), button -> choice.action().run())
                    .bounds(this.width / 2 - 125, y, 250, 20).build());
            y += 24;
        }
    }

    private int textTop() {
        int total = 14 + lines.size() * 10 + 10 + choices.size() * 24;
        return Math.max(10, (this.height - total) / 2);
    }

    private int buttonsTop() {
        return textTop() + 14 + lines.size() * 10 + 10;
    }

    @Override
    public void onClose() {
        choices.get(choices.size() - 1).action().run();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        int y = textTop();
        graphics.centeredText(this.font, this.title, this.width / 2, y, 0xFFFFFFFF);
        y += 14;
        for (FormattedCharSequence line : lines) {
            graphics.centeredText(this.font, line, this.width / 2, y, 0xFFD0D0D0);
            y += 10;
        }
    }
}
