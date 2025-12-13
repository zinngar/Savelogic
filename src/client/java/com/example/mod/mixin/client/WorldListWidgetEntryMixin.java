package com.example.mod.mixin.client;

import com.example.mod.CloudSaves;
import com.example.mod.CloudStorageProvider;
import com.example.mod.GitHubStorageProvider;
import com.example.mod.GoogleDriveProvider;
import com.example.mod.util.ZipUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.world.WorldSelectionList;
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

@Mixin(WorldSelectionList.Entry.class)
public abstract class WorldListWidgetEntryMixin {

    @Shadow @Final private LevelSummary summary;

    @Inject(method = "joinWorld", at = @At("HEAD"), cancellable = true)
    private void onJoinWorld(CallbackInfo ci) {
        if (CloudSaves.isSavingOperation) {
            ci.cancel(); // Prevent the world from loading
            CloudSaves.isSavingOperation = false; // Reset the flag

            CloudSaves.LOGGER.info("Intercepted world selection for saving:");
            CloudSaves.LOGGER.info("  Display Name: {}", summary.getLevelName());
            CloudSaves.LOGGER.info("  Folder Name: {}", summary.getLevelId());

            Minecraft client = Minecraft.getInstance();
            if (client == null) {
                CloudSaves.LOGGER.error("Minecraft client not available");
                return;
            }

            // Correct 1.21.1 API: resolve saves directory
            Path savesDir = client.gameDirectory.toPath().resolve("saves");

            // Full path to the selected world
            Path worldDir = savesDir.resolve(summary.getLevelId());

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
