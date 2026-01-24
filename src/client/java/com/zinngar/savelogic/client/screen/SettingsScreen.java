package com.zinngar.savelogic.client.screen;

import com.zinngar.savelogic.CloudStorageProvider;
import com.zinngar.savelogic.GitHubStorageProvider;
import com.zinngar.savelogic.GoogleDriveProvider;
import com.zinngar.savelogic.SaveLogic;
import com.zinngar.savelogic.config.Config;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
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

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Save"), button -> {
            Config newConfig = new Config();
            newConfig.provider = this.providerField.getText();
            newConfig.github.repositoryUrl = this.repoUrlField.getText();
            newConfig.github.personalAccessToken = this.patField.getText();
            newConfig.google.clientId = this.googleClientIdField.getText();
            newConfig.google.clientSecret = this.googleClientSecretField.getText();
            newConfig.google.refreshToken = config.google.refreshToken; // Preserve the refresh token
            newConfig.save();
            SaveLogic.CONFIG = newConfig;
            this.client.setScreen(this.parent);
        }).dimensions(this.width / 2 - 100, this.height - 54, 200, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Test Connection"), button -> {
            testConnection();
        }).dimensions(this.width / 2 - 100, 230, 200, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Logout").formatted(Formatting.RED), button -> {
            this.client.setScreen(new ConfirmScreen(
                confirmed -> {
                    if (confirmed) {
                        SaveLogic.CONFIG.google.refreshToken = "";
                        SaveLogic.CONFIG.github.personalAccessToken = "";
                        SaveLogic.CONFIG.save();
                        this.client.getToastManager().add(new SystemToast(
                            SystemToast.Type.PERIODIC_NOTIFICATION,
                            Text.literal("Credentials Removed"),
                            Text.literal("You will need to re-authenticate.")
                        ));
                    }
                    this.client.setScreen(this);
                },
                Text.literal("Remove all stored credentials?"),
                Text.literal("This action cannot be undone locally."),
                Text.literal("Yes"), Text.literal("Cancel")
            ));
        }).dimensions(this.width / 2 - 100, this.height - 78, 200, 20).build());

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
        Config tempConfig = new Config();
        tempConfig.provider = this.providerField.getText();
        tempConfig.github.repositoryUrl = this.repoUrlField.getText();
        tempConfig.github.personalAccessToken = this.patField.getText();
        tempConfig.google.clientId = this.googleClientIdField.getText();
        tempConfig.google.clientSecret = this.googleClientSecretField.getText();
        tempConfig.google.refreshToken = SaveLogic.CONFIG.google.refreshToken;

        CloudStorageProvider provider;
        if ("github".equalsIgnoreCase(tempConfig.provider)) {
            provider = new GitHubStorageProvider();
        } else {
            provider = new GoogleDriveProvider();
        }

        provider.testConnection().thenAccept(success -> {
            if (success) {
                this.connectionStatus = Text.literal("Connection successful!");
            } else {
                this.connectionStatus = Text.literal("Connection failed!");
            }
        });
    }
}
