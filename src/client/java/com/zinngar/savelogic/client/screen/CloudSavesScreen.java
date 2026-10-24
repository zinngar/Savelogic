package com.zinngar.savelogic.client.screen;

import com.zinngar.savelogic.GitHubStorageProvider.CloudSave;
import com.zinngar.savelogic.client.CloudStorageManager;
import com.zinngar.savelogic.client.CloudSync;
import com.zinngar.savelogic.client.CloudText;
import com.zinngar.savelogic.client.SaveLogicClient;
import com.zinngar.savelogic.client.SetupSync;
import com.zinngar.savelogic.SaveLogic;
import com.zinngar.savelogic.client.SchematicSync;
import com.zinngar.savelogic.client.TransferStatus;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;


public class CloudSavesScreen extends Screen {

    private final Screen parent;
    private boolean wasIdle = !TransferStatus.INSTANCE.isActive();
    private String message;
    private boolean messageIsError;

    public CloudSavesScreen(Screen parent) {
        super(Component.translatable("savelogic.menu.cloud_saves"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        // Returning here (e.g. pressing Back on the world picker) cancels a pending upload pick.
        SaveLogicClient.uploadPickerScreen = null;

        boolean configured = CloudStorageManager.getProvider() != null;
        // One transfer at a time; the progress bar at the top shows the one that is running.
        boolean enabled = configured && !TransferStatus.INSTANCE.isActive();
        int left = left();
        int right = left + 154;
        int top = top();

        // Worlds
        Button saveButton = this.addRenderableWidget(Button.builder(Component.translatable("savelogic.menu.save"), button -> {
            SelectWorldScreen picker = new SelectWorldScreen(this);
            SaveLogicClient.uploadPickerScreen = picker;
            SaveLogicClient.uploadReturnScreen = this;
            this.minecraft.gui.setScreen(picker);
        }).bounds(left, top, 150, 20).build());
        Button loadButton = this.addRenderableWidget(Button.builder(Component.translatable("savelogic.menu.load"),
                button -> this.minecraft.gui.setScreen(new SaveLogicSelectionScreen(this)))
                .bounds(right, top, 150, 20).build());

        // Mods & configs
        int setupTop = top + 40;
        Button uploadSetup = this.addRenderableWidget(Button.builder(Component.translatable("savelogic.menu.upload_setup"),
                button -> confirmUploadSetup()).bounds(left, setupTop, 150, 20).build());
        Button installSetup;
        if (SetupSync.isInstallPending()) {
            installSetup = this.addRenderableWidget(Button.builder(Component.translatable("savelogic.menu.cancel_setup"), button -> {
                SetupSync.cancelPendingInstall();
                showMessage("Install cancelled. Your mods and configs stay as they are.", false);
                this.rebuildWidgets();
            }).bounds(right, setupTop, 150, 20).build());
        } else {
            installSetup = this.addRenderableWidget(Button.builder(Component.translatable("savelogic.menu.install_setup_short"),
                    button -> lookUpSetup()).bounds(right, setupTop, 150, 20).build());
        }
        // Checking for updates only needs Modrinth, not the GitHub settings.
        Button modUpdates = this.addRenderableWidget(Button.builder(Component.translatable("savelogic.menu.mod_updates"),
                button -> this.minecraft.gui.setScreen(new ModUpdatesScreen(this)))
                .bounds(left, setupTop + 24, 150, 20).build());

        // Schematics
        Button syncSchematics = this.addRenderableWidget(Button.builder(Component.translatable("savelogic.menu.sync_schematics"),
                button -> {
                    SchematicSync.sync(true, summary -> this.rebuildWidgets());
                    this.rebuildWidgets();
                }).bounds(left, schematicsTop(), 150, 20).build());

        saveButton.active = enabled;
        loadButton.active = enabled;
        uploadSetup.active = enabled;
        installSetup.active = enabled || SetupSync.isInstallPending();
        modUpdates.active = !TransferStatus.INSTANCE.isActive() && !SetupSync.isInstallPending();
        syncSchematics.active = configured && !SchematicSync.isRunning();

        this.addRenderableWidget(Button.builder(Component.translatable("savelogic.menu.settings"),
                button -> this.minecraft.gui.setScreen(new SettingsScreen(this)))
                .bounds(this.width / 2 - 100, this.height - 26, 98, 20).build());

        this.addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> this.onClose())
                .bounds(this.width / 2 + 2, this.height - 26, 98, 20).build());
    }

    private int left() {
        return this.width / 2 - 152;
    }

    private int top() {
        return Math.max(40, this.height / 4 - 10);
    }

    private int schematicsTop() {
        return top() + 104;
    }

    private void confirmUploadSetup() {
        this.minecraft.gui.setScreen(new ConfirmScreen(confirmed -> {
            this.minecraft.gui.setScreen(this);
            if (confirmed) {
                SetupSync.uploadSetup();
                this.rebuildWidgets();
            }
        }, Component.translatable("savelogic.menu.upload_setup"),
                Component.literal("Upload every mod in your mods folder and your config folder, replacing the setup saved for Minecraft "
                        + SetupSync.minecraftVersion() + ". Your SaveLogic token is never uploaded.")));
    }

    private void lookUpSetup() {
        showMessage("Looking for a saved setup...", false);
        SetupSync.findSetup(found -> {
            if (found.isEmpty()) {
                showMessage("No mods & configs saved for Minecraft " + SetupSync.minecraftVersion()
                        + " yet. Use Upload on the computer that has your setup.", true);
                return;
            }
            message = null;
            confirmInstall(found.get());
        }, error -> showMessage("Could not check for a setup: " + error, true));
    }

    private void confirmInstall(CloudSave setup) {
        this.minecraft.gui.setScreen(new ConfirmScreen(confirmed -> {
            this.minecraft.gui.setScreen(this);
            if (confirmed) {
                SetupSync.downloadSetup(setup, this::offerQuit);
                this.rebuildWidgets();
            }
        }, Component.translatable("savelogic.menu.install_setup"),
                Component.literal("Replace your mods and configs with the setup saved " + describe(setup) + "? "
                        + "Your current ones are backed up to savelogic-backups first. "
                        + "It is installed when you close Minecraft.")));
    }

    private void offerQuit() {
        this.minecraft.gui.setScreen(new ConfirmScreen(quit -> {
            if (quit) {
                this.minecraft.stop();
            } else {
                showMessage("Your new setup will be installed the next time you close Minecraft.", false);
                this.minecraft.gui.setScreen(this);
            }
        }, Component.literal("Setup downloaded").withStyle(ChatFormatting.GREEN),
                Component.literal("Close Minecraft to install it. Start Minecraft again afterwards and your mods and configs will match."),
                Component.translatable("savelogic.setup.quit_now"), Component.translatable("savelogic.setup.later")));
    }

    private static String describe(CloudSave setup) {
        return CloudText.when(setup.uploaded()) + " (" + CloudText.size(setup.size()) + ")";
    }

    private void showMessage(String text, boolean error) {
        this.message = text;
        this.messageIsError = error;
        if (error) {
            CloudSync.toast("Mods & configs", text); // The status line may be too narrow for the whole message.
        }
    }

    @Override
    public void tick() {
        super.tick();
        // Re-enable the buttons as soon as a running transfer finishes.
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
        int left = left();
        int top = top();
        graphics.centeredText(this.font, this.title, this.width / 2, top - 28, 0xFFFFFFFF);
        graphics.text(this.font, Component.translatable("savelogic.section.worlds"), left, top - 11, 0xFFA0A0A0);
        graphics.text(this.font, Component.translatable("savelogic.section.setup"), left, top + 29, 0xFFA0A0A0);
        graphics.text(this.font, Component.translatable("savelogic.section.schematics"), left, schematicsTop() - 11, 0xFFA0A0A0);
        String schematicsStatus = SchematicSync.isRunning() ? "Syncing..."
                : SaveLogic.CONFIG.schematicsAutoSync ? "Auto-sync on" : "Auto-sync off";
        graphics.text(this.font, schematicsStatus, left + 160, schematicsTop() + 6, 0xFFA0A0A0);

        // One status line above Settings/Back; the newest message wins.
        int noteY = schematicsTop() + 30;
        if (message != null) {
            graphics.centeredText(this.font, this.font.plainSubstrByWidth(message, this.width - 20), this.width / 2, noteY,
                    messageIsError ? 0xFFFF6060 : 0xFFA0A0A0);
        } else if (SetupSync.isInstallPending()) {
            graphics.centeredText(this.font, Component.translatable("savelogic.setup.pending"), this.width / 2, noteY, 0xFFFFD060);
        } else if (CloudStorageManager.getProvider() == null) {
            graphics.centeredText(this.font, Component.translatable("savelogic.message.not_configured"), this.width / 2, noteY, 0xFFFF6060);
        }
    }

}
