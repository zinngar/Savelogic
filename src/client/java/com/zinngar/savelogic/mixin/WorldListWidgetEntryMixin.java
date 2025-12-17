package com.zinngar.savelogic.mixin;

import com.zinngar.savelogic.client.SaveLogicClient;
import com.zinngar.savelogic.SaveLogic;
import com.zinngar.savelogic.CloudStorageProvider;
import com.zinngar.savelogic.GoogleDriveProvider;
import com.zinngar.savelogic.GitHubStorageProvider;
import com.zinngar.savelogic.util.ZipUtil;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.world.WorldListWidget;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

@Mixin(WorldListWidget.Entry.class)
public abstract class WorldListWidgetEntryMixin {

    @Shadow @Final private LevelSummary summary;

    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void onPlay(CallbackInfo ci) {
        if (SaveLogicClient.isSavingOperation) {
            ci.cancel();
            SaveLogicClient.isSavingOperation = false;

            SaveLogic.LOGGER.info("Intercepted world selection for saving:");
            SaveLogic.LOGGER.info("  Display Name: {}", summary.getDisplayName());
            SaveLogic.LOGGER.info("  Folder Name: {}", summary.getName());

            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null) {
                SaveLogic.LOGGER.error("MinecraftClient not available");
                return;
            }

            Path savesDir = client.getLevelStorage().getSavesDirectory();
            Path worldDir = savesDir.resolve(summary.getName());
            File worldDirFile = worldDir.toFile();
            if (!worldDirFile.exists() || !worldDirFile.isDirectory()) {
                SaveLogic.LOGGER.error("World directory does not exist: {}", worldDir);
                return;
            }

            SaveLogic.LOGGER.info("Resolved world directory: {}", worldDir.toAbsolutePath());

            CloudStorageProvider provider;
            if ("github".equalsIgnoreCase(SaveLogic.CONFIG.provider)) {
                provider = new GitHubStorageProvider();
            } else {
                provider = new GoogleDriveProvider();
            }

            CompletableFuture.runAsync(() -> {
                try {
                    SaveLogic.LOGGER.info("Zipping world...");
                    Path zip = ZipUtil.zipWorld(worldDir);
                    SaveLogic.LOGGER.info("World zipped to: {}", zip.toAbsolutePath());
                    provider.uploadSave(zip.toFile());
                } catch (Exception e) {
                    SaveLogic.LOGGER.error("Failed to zip and upload world", e);
                }
            });

            client.setScreen(SaveLogicClient.parentScreen);
        }
    }
}
