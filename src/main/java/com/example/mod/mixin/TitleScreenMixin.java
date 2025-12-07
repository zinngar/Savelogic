package com.example.mod.mixin;

import com.example.mod.CloudSaves;
import com.example.mod.GoogleDriveManager;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public class TitleScreenMixin extends Screen {
    protected TitleScreenMixin(Text title) {
        super(title);
    }

    @Inject(at = @At("HEAD"), method = "init()V")
    private void init(CallbackInfo ci) {
        int l = this.height / 4 + 48;
        this.addDrawableChild(ButtonWidget.builder(Text.translatable("menu.cloud_saves.save"), button -> {
            GoogleDriveManager.getAccessToken().thenAccept(accessToken -> {
                CloudSaves.LOGGER.info("Access token: " + accessToken);
                // For now, we'll just create a dummy file to "upload"
                java.io.File dummyFile = new java.io.File("dummy.txt");
                try {
                    dummyFile.createNewFile();
                } catch (java.io.IOException e) {
                    e.printStackTrace();
                }
                GoogleDriveManager.uploadFile(accessToken, dummyFile).thenRun(() -> {
                    CloudSaves.LOGGER.info("File upload complete!");
                });
            });
        }).dimensions(this.width / 2 - 100, l + 24, 200, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.translatable("menu.cloud_saves.load"), button -> {
            GoogleDriveManager.getAccessToken().thenAccept(accessToken -> {
                CloudSaves.LOGGER.info("Access token: " + accessToken);
                // For now, we'll just use a dummy file ID to "download"
                GoogleDriveManager.downloadFile(accessToken, "dummy_file_id").thenAccept(file -> {
                    CloudSaves.LOGGER.info("File download complete! File path: " + file.getAbsolutePath());
                });
            });
        }).dimensions(this.width / 2 - 100, l + 48, 200, 20).build());
    }
}
