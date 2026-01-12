package com.zinngar.savelogic.client.screen;

import com.zinngar.savelogic.CloudStorageProvider;
import com.zinngar.savelogic.CloudStorageProvider;
import com.zinngar.savelogic.SaveLogic;
import com.zinngar.savelogic.client.CloudStorageManager;
import com.zinngar.savelogic.config.Config;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.client.gui.DrawContext;

public class SettingsScreen extends Screen {

    private final Screen parent;
    private TextFieldWidget providerField;
    private TextFieldWidget repoUrlField;
    private TextFieldWidget patField;
    private TextFieldWidget googleClientIdField;
    private TextFieldWidget googleClientSecretField;
    private Text connectionStatus = Text.literal("");

    public SettingsScreen(Screen parent) {
        super(Text.literal("SaveLogic Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();

        Config config = SaveLogic.CONFIG;

        this.providerField = new TextFieldWidget(this.textRenderer, this.width / 2 - 100, 40, 200, 20, Text.literal("Provider"));
        this.providerField.setText(config.provider != null ? config.provider : "");
        this.addDrawableChild(this.providerField);

        this.repoUrlField = new TextFieldWidget(this.textRenderer, this.width / 2 - 100, 80, 200, 20, Text.literal("GitHub Repo URL"));
        this.repoUrlField.setText(config.github.repositoryUrl != null ? config.github.repositoryUrl : "");
        this.addDrawableChild(this.repoUrlField);

        this.patField = new TextFieldWidget(this.textRenderer, this.width / 2 - 100, 120, 200, 20, Text.literal("GitHub PAT"));
        this.patField.setText(config.github.personalAccessToken != null ? config.github.personalAccessToken : "");
        this.addDrawableChild(this.patField);

        this.googleClientIdField = new TextFieldWidget(this.textRenderer, this.width / 2 - 100, 160, 200, 20, Text.literal("Google Client ID"));
        this.googleClientIdField.setText(config.google.clientId != null ? config.google.clientId : "");
        this.addDrawableChild(this.googleClientIdField);

        this.googleClientSecretField = new TextFieldWidget(this.textRenderer, this.width / 2 - 100, 200, 200, 20, Text.literal("Google Client Secret"));
        this.googleClientSecretField.setText(config.google.clientSecret != null ? config.google.clientSecret : "");
        this.addDrawableChild(this.googleClientSecretField);

        this.addDrawableChild(ButtonWidget.builder(
            Text.literal("Keep Local Backups: " + (config.keepLocalBackups ? "ON" : "OFF")),
            button -> {
                config.keepLocalBackups = !config.keepLocalBackups;
                button.setMessage(Text.literal("Keep Local Backups: " + (config.keepLocalBackups ? "ON" : "OFF")));
            }
        ).dimensions(this.width / 2 - 100, 224, 200, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Test Connection"), button -> {
            testConnection();
        }).dimensions(this.width / 2 - 100, 248, 200, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Save"), button -> {
            config.provider = this.providerField.getText();
            config.github.repositoryUrl = this.repoUrlField.getText();
            config.github.personalAccessToken = this.patField.getText();
            config.google.clientId = this.googleClientIdField.getText();
            config.google.clientSecret = this.googleClientSecretField.getText();
            config.save();
            this.client.setScreen(this.parent);
        }).dimensions(this.width / 2 - 100, this.height - 54, 200, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Back"), button -> {
            this.client.setScreen(this.parent);
        }).dimensions(this.width / 2 - 100, this.height - 30, 200, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 15, 16777215);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Provider (github/google)"), this.width / 2 - 100, 28, 10526880);
        context.drawTextWithShadow(this.textRenderer, Text.literal("GitHub Repository URL"), this.width / 2 - 100, 68, 10526880);
        context.drawTextWithShadow(this.textRenderer, Text.literal("GitHub Personal Access Token"), this.width / 2 - 100, 108, 10526880);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Google Client ID"), this.width / 2 - 100, 148, 10526880);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Google Client Secret"), this.width / 2 - 100, 188, 10526880);
        context.drawCenteredTextWithShadow(this.textRenderer, this.connectionStatus, this.width / 2, 250, 16777215);
    }

    @Override
    public void close() {
        this.client.setScreen(this.parent);
    }

    private void testConnection() {
        SaveLogic.CONFIG.provider = this.providerField.getText();
        SaveLogic.CONFIG.github.repositoryUrl = this.repoUrlField.getText();
        SaveLogic.CONFIG.github.personalAccessToken = this.patField.getText();
        SaveLogic.CONFIG.google.clientId = this.googleClientIdField.getText();
        SaveLogic.CONFIG.google.clientSecret = this.googleClientSecretField.getText();

        CloudStorageManager.initialize();
        CloudStorageProvider provider = CloudStorageManager.getProvider();

        if (provider != null) {
            provider.testConnection().thenAccept(success -> {
                if (success) {
                    this.connectionStatus = Text.literal("Connection successful!");
                } else {
                    this.connectionStatus = Text.literal("Connection failed!");
                }
            });
        } else {
            this.connectionStatus = Text.literal("Invalid provider configured.");
        }
    }
}
