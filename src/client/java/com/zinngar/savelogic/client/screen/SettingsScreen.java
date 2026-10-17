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

    private static final Integer[] KEEP_CHOICES = {1, 3, 5, 10};

    private final Screen parent;
    private EditBox repoUrlField;
    private EditBox tokenField;
    private EditBox deviceField;
    private boolean automaticBackup;
    private boolean autoUpload;
    private int keepVersions;
    private Component connectionStatus = Component.empty();

    public SettingsScreen(Screen parent) {
        super(Component.translatable("savelogic.settings.title"));
        this.parent = parent;
        this.automaticBackup = SaveLogic.CONFIG.automaticBackup;
        this.autoUpload = SaveLogic.CONFIG.autoUpload;
        this.keepVersions = SaveLogic.CONFIG.keepVersions;
    }

    @Override
    protected void init() {
        Config config = SaveLogic.CONFIG;
        int left = this.width / 2 - 150;

        String repoUrl = repoUrlField != null ? repoUrlField.getValue() : config.github.repositoryUrl;
        String token = tokenField != null ? tokenField.getValue() : config.github.personalAccessToken;
        String device = deviceField != null ? deviceField.getValue() : config.device().name();

        repoUrlField = new EditBox(this.font, left, 30, 300, 20, Component.translatable("savelogic.settings.repository"));
        repoUrlField.setMaxLength(256);
        repoUrlField.setHint(Component.literal("https://github.com/you/minecraft-saves"));
        repoUrlField.setValue(repoUrl == null ? "" : repoUrl);
        this.addRenderableWidget(repoUrlField);

        tokenField = new EditBox(this.font, left, 64, 300, 20, Component.translatable("savelogic.settings.token"));
        tokenField.setMaxLength(512);
        tokenField.setHint(Component.literal("github_pat_..."));
        tokenField.addFormatter((text, offset) -> FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY));
        tokenField.setValue(token == null ? "" : token);
        this.addRenderableWidget(tokenField);

        deviceField = new EditBox(this.font, left, 98, 300, 20, Component.translatable("savelogic.settings.device"));
        deviceField.setMaxLength(40);
        deviceField.setValue(device);
        this.addRenderableWidget(deviceField);

        this.addRenderableWidget(CycleButton.onOffBuilder(autoUpload)
                .create(left, 124, 147, 20, Component.translatable("savelogic.settings.auto_upload"),
                        (button, value) -> autoUpload = value));
        this.addRenderableWidget(CycleButton.builder((Integer value) -> Component.literal(String.valueOf(value)), keepVersions)
                .withValues(KEEP_CHOICES)
                .create(left + 153, 124, 147, 20, Component.translatable("savelogic.settings.keep_versions"),
                        (button, value) -> keepVersions = value));
        this.addRenderableWidget(CycleButton.onOffBuilder(automaticBackup)
                .create(left, 148, 147, 20, Component.translatable("savelogic.settings.auto_backup"),
                        (button, value) -> automaticBackup = value));
        this.addRenderableWidget(Button.builder(Component.translatable("savelogic.settings.test"), button -> testConnection())
                .bounds(left + 153, 148, 147, 20).build());

        this.addRenderableWidget(Button.builder(Component.translatable("savelogic.settings.forget")
                        .withStyle(ChatFormatting.RED), button -> confirmForget())
                .bounds(left, this.height - 50, 147, 20).build());

        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> {
            SaveLogic.CONFIG.github.repositoryUrl = repoUrlField.getValue().trim();
            SaveLogic.CONFIG.github.personalAccessToken = tokenField.getValue().trim();
            if (!deviceField.getValue().isBlank()) {
                SaveLogic.CONFIG.deviceName = deviceField.getValue().trim();
            }
            SaveLogic.CONFIG.autoUpload = autoUpload;
            SaveLogic.CONFIG.keepVersions = keepVersions;
            SaveLogic.CONFIG.automaticBackup = automaticBackup;
            SaveLogic.CONFIG.save();
            this.onClose();
        }).bounds(left + 153, this.height - 50, 147, 20).build());

        this.addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> this.onClose())
                .bounds(this.width / 2 - 100, this.height - 26, 200, 20).build());
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
        graphics.centeredText(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);
        graphics.text(this.font, Component.translatable("savelogic.settings.repository"), left, 20, 0xFFA0A0A0);
        graphics.text(this.font, Component.translatable("savelogic.settings.token"), left, 54, 0xFFA0A0A0);
        graphics.text(this.font, Component.translatable("savelogic.settings.device"), left, 88, 0xFFA0A0A0);
        graphics.textWithWordWrap(this.font, connectionStatus, left, 172, 300, 0xFFFFFFFF);
    }
}
