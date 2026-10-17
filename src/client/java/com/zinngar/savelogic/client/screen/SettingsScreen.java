package com.zinngar.savelogic.client.screen;

import com.zinngar.savelogic.GitHubStorageProvider;
import com.zinngar.savelogic.SaveLogic;
import com.zinngar.savelogic.config.Config;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

public class SettingsScreen extends Screen {

    private final Screen parent;
    private EditBox repoUrlField;
    private EditBox tokenField;
    private boolean automaticBackup;
    private Component connectionStatus = Component.empty();

    public SettingsScreen(Screen parent) {
        super(Component.translatable("savelogic.settings.title"));
        this.parent = parent;
        this.automaticBackup = SaveLogic.CONFIG.automaticBackup;
    }

    @Override
    protected void init() {
        Config config = SaveLogic.CONFIG;
        int left = this.width / 2 - 150;

        String repoUrl = repoUrlField != null ? repoUrlField.getValue() : config.github.repositoryUrl;
        String token = tokenField != null ? tokenField.getValue() : config.github.personalAccessToken;

        repoUrlField = new EditBox(this.font, left, 50, 300, 20, Component.translatable("savelogic.settings.repository"));
        repoUrlField.setMaxLength(256);
        repoUrlField.setHint(Component.literal("https://github.com/you/minecraft-saves"));
        repoUrlField.setValue(repoUrl == null ? "" : repoUrl);
        this.addRenderableWidget(repoUrlField);

        tokenField = new EditBox(this.font, left, 90, 300, 20, Component.translatable("savelogic.settings.token"));
        tokenField.setMaxLength(512);
        tokenField.setHint(Component.literal("github_pat_..."));
        tokenField.addFormatter((text, offset) -> FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY));
        tokenField.setValue(token == null ? "" : token);
        this.addRenderableWidget(tokenField);

        this.addRenderableWidget(CycleButton.onOffBuilder(automaticBackup)
                .create(left, 120, 300, 20, Component.translatable("savelogic.settings.auto_backup"),
                        (button, value) -> automaticBackup = value));

        this.addRenderableWidget(Button.builder(Component.translatable("savelogic.settings.test"), button -> testConnection())
                .bounds(left, 146, 300, 20).build());

        this.addRenderableWidget(Button.builder(Component.translatable("savelogic.settings.forget")
                        .withStyle(ChatFormatting.RED), button -> confirmForget())
                .bounds(left, this.height - 54, 145, 20).build());

        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> {
            SaveLogic.CONFIG.github.repositoryUrl = repoUrlField.getValue().trim();
            SaveLogic.CONFIG.github.personalAccessToken = tokenField.getValue().trim();
            SaveLogic.CONFIG.automaticBackup = automaticBackup;
            SaveLogic.CONFIG.save();
            this.onClose();
        }).bounds(left + 155, this.height - 54, 145, 20).build());

        this.addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> this.onClose())
                .bounds(this.width / 2 - 100, this.height - 30, 200, 20).build());
    }

    private void testConnection() {
        connectionStatus = Component.literal("Testing...").withStyle(ChatFormatting.GRAY);
        // Test what is typed in the fields, not what was saved before.
        new GitHubStorageProvider(repoUrlField.getValue(), tokenField.getValue()).testConnection()
                .thenAccept(problem -> this.minecraft.execute(() -> connectionStatus = problem == null
                        ? Component.literal("Connected! The token can write to this private repository.").withStyle(ChatFormatting.GREEN)
                        : Component.literal(problem).withStyle(ChatFormatting.RED)));
    }

    private void confirmForget() {
        this.minecraft.gui.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                SaveLogic.CONFIG.github.personalAccessToken = "";
                SaveLogic.CONFIG.save();
                tokenField.setValue("");
                connectionStatus = Component.literal("Token removed from this computer.").withStyle(ChatFormatting.YELLOW);
            }
            this.minecraft.gui.setScreen(this);
        }, Component.translatable("savelogic.settings.forget"),
                Component.literal("Your cloud saves stay on GitHub; you will need to paste the token again to use them.")));
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        int left = this.width / 2 - 150;
        graphics.centeredText(this.font, this.title, this.width / 2, 15, 0xFFFFFFFF);
        graphics.text(this.font, Component.translatable("savelogic.settings.repository"), left, 39, 0xFFA0A0A0);
        graphics.text(this.font, Component.translatable("savelogic.settings.token"), left, 79, 0xFFA0A0A0);
        graphics.textWithWordWrap(this.font, connectionStatus, left, 172, 300, 0xFFFFFFFF);
    }
}
