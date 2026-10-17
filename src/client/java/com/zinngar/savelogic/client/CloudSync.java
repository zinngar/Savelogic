package com.zinngar.savelogic.client;

import com.zinngar.savelogic.GitHubStorageProvider;
import com.zinngar.savelogic.SaveLogic;
import com.zinngar.savelogic.util.ZipUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/** Zips/uploads and downloads/unzips worlds, reporting the result with toasts. */
public class CloudSync {

    public static void uploadWorld(String worldFolder) {
        GitHubStorageProvider provider = CloudStorageManager.getProvider();
        if (provider == null) {
            toast("Cloud save failed", "Set up your GitHub repository in SaveLogic settings.");
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Path worldDir = minecraft.getLevelSource().getBaseDir().resolve(worldFolder);
        if (!Files.isDirectory(worldDir)) {
            toast("Cloud save failed", "World folder not found: " + worldFolder);
            return;
        }

        toast("Uploading " + worldFolder, "This can take a minute for big worlds...");
        CompletableFuture.supplyAsync(() -> {
            try {
                Path tempDir = Files.createTempDirectory("savelogic-upload");
                Path zip = tempDir.resolve(worldFolder + ".zip");
                ZipUtil.zipWorld(worldDir, zip);
                return zip;
            } catch (Exception e) {
                throw new RuntimeException("Could not zip world: " + e.getMessage(), e);
            }
        }).thenCompose(zip -> provider.uploadSave(worldFolder, zip, step -> toast("Uploading " + worldFolder, step))
                .whenComplete((ignored, error) -> deleteQuietly(zip)))
          .whenComplete((ignored, error) -> {
              if (error == null) {
                  toast("Saved to cloud", worldFolder + " was uploaded to GitHub.");
              } else {
                  SaveLogic.LOGGER.error("Cloud upload of {} failed", worldFolder, error);
                  toast("Cloud save failed", rootMessage(error));
              }
          });
    }

    /**
     * Downloads a cloud save into a new world folder. The folder is named after the world, with a
     * numeric suffix if a world of that name already exists, so local worlds are never overwritten.
     */
    public static void downloadWorld(GitHubStorageProvider.CloudSave save, Runnable onSuccess, Consumer<String> onError) {
        GitHubStorageProvider provider = CloudStorageManager.getProvider();
        if (provider == null) {
            onError.accept("Set up your GitHub repository in SaveLogic settings.");
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Path savesDir = minecraft.getLevelSource().getBaseDir();
        // The name comes from the repository, so strip anything that could leave the saves folder.
        String baseName = save.worldName().replaceAll("[\\\\/:*?\"<>|]", "_").replaceFirst("^\\.+", "_");
        Path zip;
        try {
            zip = Files.createTempFile("savelogic-download", ".zip");
        } catch (Exception e) {
            onError.accept("Could not create a temporary file: " + e.getMessage());
            return;
        }

        provider.downloadSave(save, zip, step -> toast("Downloading " + baseName, step)).thenApply(ignored -> {
            try {
                String folder = baseName;
                for (int i = 2; Files.exists(savesDir.resolve(folder)); i++) {
                    folder = baseName + " (" + i + ")";
                }
                ZipUtil.unzip(zip, savesDir.resolve(folder));
                return folder;
            } catch (Exception e) {
                throw new RuntimeException("Could not unzip world: " + e.getMessage(), e);
            }
        }).whenComplete((folder, error) -> minecraft.execute(() -> {
            deleteQuietly(zip);
            if (error == null) {
                toast("Loaded from cloud", "Downloaded as world folder \"" + folder + "\".");
                onSuccess.run();
            } else {
                SaveLogic.LOGGER.error("Cloud download of {} failed", baseName, error);
                onError.accept(rootMessage(error));
            }
        }));
    }

    public static void toast(String title, String message) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> SystemToast.addOrUpdate(minecraft.gui.toastManager(),
                SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.literal(title), Component.literal(message)));
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (Exception e) {
            SaveLogic.LOGGER.warn("Could not delete temporary file {}", file);
        }
    }
}
