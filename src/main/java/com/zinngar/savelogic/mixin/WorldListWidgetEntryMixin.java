package com.zinngar.savelogic.mixin;

import com.zinngar.savelogic.CloudSaves;
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
        if (CloudSaves.isSavingOperation) {
            ci.cancel(); // Prevent the world from loading
            CloudSaves.isSavingOperation = false; // Reset the flag

            CloudSaves.LOGGER.info("Intercepted world selection for saving:");
            CloudSaves.LOGGER.info("  Display Name: {}", summary.getDisplayName());
            CloudSaves.LOGGER.info("  Folder Name: {}", summary.getName());

            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null) {
                CloudSaves.LOGGER.error("MinecraftClient not available");
                return;
            }

            // Correct 1.21.1 API: resolve saves directory
            Path savesDir = client.getLevelStorage().getSavesDirectory();

            // Full path to the selected world
            Path worldDir = savesDir.resolve(summary.getName());

            File worldDirFile = worldDir.toFile();
            if (!worldDirFile.exists() || !worldDirFile.isDirectory()) {
                CloudSaves.LOGGER.error("World directory does not exist: {}", worldDir);
                return;
            }

            CloudSaves.LOGGER.info("Resolved world directory: {}", worldDir.toAbsolutePath());

            // Choose storage provider
            CloudStorageProvider provider;
            if ("github".equalsIgnoreCase(CloudSaves.CONFIG.provider)) {
                provider = new GitHubStorageProvider();
            } else {
                provider = new GoogleDriveProvider();
            }

            CompletableFuture.runAsync(() -> {
                try {
                    CloudSaves.LOGGER.info("Zipping world...");
                    Path zip = ZipUtil.zipWorld(worldDir);
                    CloudSaves.LOGGER.info("World zipped to: {}", zip.toAbsolutePath());
                    provider.uploadSave(zip.toFile());
                } catch (Exception e) {
                    CloudSaves.LOGGER.error("Failed to zip and upload world", e);
                }
            });

            // Return to parent screen
            client.setScreen(CloudSaves.parentScreen);
        }
    }
}
