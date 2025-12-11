package com.example.mod.mixin;

import com.example.mod.CloudSaves;
import com.example.mod.CloudStorageProvider;
import com.example.mod.GitHubStorageProvider;
import com.example.mod.GoogleDriveProvider;
import com.example.mod.util.ZipUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.File;
import java.nio.file.Path;

@Mixin(WorldSelectionList.WorldListEntry.class)
public abstract class WorldListWidgetEntryMixin {

    @Shadow @Final private LevelSummary summary;
    @Shadow @Final private Minecraft minecraft;

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void onMouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (CloudSaves.isSavingOperation) {
            cir.setReturnValue(true);
            CloudSaves.isSavingOperation = false;

            CloudSaves.LOGGER.info("Attempting to save world: " + this.summary.getLevelId());

            Path savesDir = this.minecraft.gameDirectory.toPath().resolve("saves");
            Path worldDir = savesDir.resolve(this.summary.getLevelId());

            try {
                Path zipPath = ZipUtil.zipWorld(worldDir);
                File zipFile = zipPath.toFile();
                CloudSaves.LOGGER.info("Successfully zipped world: " + this.summary.getLevelId());

                CloudStorageProvider provider;
                if ("google".equalsIgnoreCase(CloudSaves.CONFIG.provider)) {
                    provider = new GoogleDriveProvider();
                } else {
                    provider = new GitHubStorageProvider();
                }

                provider.uploadSave(zipFile);
            } catch (Exception e) {
                CloudSaves.LOGGER.error("Failed to save world", e);
            }

            if (CloudSaves.parentScreen != null) {
                this.minecraft.setScreen(CloudSaves.parentScreen);
            }
        }
    }
}
