package com.example.mod.client.mixin;

import com.example.mod.client.CloudSavesCommonClient;
import com.example.mod.CloudSavesCommon;
import com.example.mod.CloudStorageProvider;
import com.example.mod.GoogleDriveProvider;
import com.example.mod.GitHubStorageProvider;
import com.example.mod.util.ZipUtil;
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
        if (CloudSavesCommonClient.isSavingOperation) {
            ci.cancel(); // Prevent the world from loading
            CloudSavesCommonClient.isSavingOperation = false; // Reset the flag

            CloudSavesCommon.LOGGER.info("Intercepted world selection for saving:");
            CloudSavesCommon.LOGGER.info("  Display Name: {}", summary.getDisplayName());
            CloudSavesCommon.LOGGER.info("  Folder Name: {}", summary.getName());

            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null) {
                CloudSavesCommon.LOGGER.error("MinecraftClient not available");
                return;
            }

            Path savesDir = client.getLevelStorage().getSavesDirectory();
            Path worldDir = savesDir.resolve(summary.getName());
            File worldDirFile = worldDir.toFile();
            if (!worldDirFile.exists() || !worldDirFile.isDirectory()) {
                CloudSavesCommon.LOGGER.error("World directory does not exist: {}", worldDir);
                return;
            }

            CloudSavesCommon.LOGGER.info("Resolved world directory: {}", worldDir.toAbsolutePath());

            CloudStorageProvider provider;
            if ("github".equalsIgnoreCase(CloudSavesCommon.CONFIG.provider)) {
                provider = new GitHubStorageProvider();
            } else {
                provider = new GoogleDriveProvider();
            }

            CompletableFuture.runAsync(() -> {
                try {
                    CloudSavesCommon.LOGGER.info("Zipping world...");
                    Path zip = ZipUtil.zipWorld(worldDir);
                    CloudSavesCommon.LOGGER.info("World zipped to: {}", zip.toAbsolutePath());
                    provider.uploadSave(zip.toFile());
                } catch (Exception e) {
                    CloudSavesCommon.LOGGER.error("Failed to zip and upload world", e);
                }
            });

            client.setScreen(CloudSavesCommonClient.parentScreen);
        }
    }
}
