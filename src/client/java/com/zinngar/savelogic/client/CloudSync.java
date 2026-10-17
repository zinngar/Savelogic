package com.zinngar.savelogic.client;

import com.zinngar.savelogic.GitHubStorageProvider;
import com.zinngar.savelogic.SaveLogic;
import com.zinngar.savelogic.setup.SetupInstaller;
import com.zinngar.savelogic.util.ZipUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/** Zips/uploads and downloads/unzips worlds, showing progress with {@link TransferStatus} and the result with a toast. */
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
        TransferStatus status = TransferStatus.INSTANCE;
        if (!status.begin("Saving \"" + worldFolder + "\" to cloud")) {
            toast("Cloud save not started", "Wait for the current upload or download to finish.");
            return;
        }

        Path tempDir;
        try {
            tempDir = Files.createTempDirectory("savelogic-upload");
        } catch (Exception e) {
            status.fail("Could not create a temporary folder: " + e.getMessage());
            return;
        }
        Path zip = tempDir.resolve(worldFolder + ".zip");
        CompletableFuture.runAsync(() -> {
            try {
                ZipUtil.zipWorld(worldDir, zip, status);
            } catch (Exception e) {
                throw new RuntimeException("Could not zip world: " + e.getMessage(), e);
            }
        }).thenCompose(ignored -> provider.uploadSave(worldFolder, zip, status))
          .whenComplete((ignored, error) -> {
              deleteQuietly(zip);
              deleteQuietly(tempDir);
              if (error == null) {
                  status.succeed("Done! \"" + worldFolder + "\" is saved on GitHub.");
                  toast("Saved to cloud", worldFolder + " was uploaded to GitHub.");
              } else {
                  SaveLogic.LOGGER.error("Cloud upload of {} failed", worldFolder, error);
                  status.fail("Failed: " + rootMessage(error));
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
        TransferStatus status = TransferStatus.INSTANCE;
        if (!status.begin("Loading \"" + baseName + "\" from cloud")) {
            onError.accept("Wait for the current upload or download to finish.");
            return;
        }
        Path zip;
        try {
            zip = Files.createTempFile("savelogic-download", ".zip");
        } catch (Exception e) {
            status.fail("Could not create a temporary file: " + e.getMessage());
            onError.accept("Could not create a temporary file: " + e.getMessage());
            return;
        }

        provider.downloadSave(save, zip, status).thenApply(ignored -> {
            status.phase("Unzipping", 0);
            try {
                String folder = baseName;
                for (int i = 2; Files.exists(savesDir.resolve(folder)); i++) {
                    folder = baseName + " (" + i + ")";
                }
                Path worldDir = savesDir.resolve(folder);
                ZipUtil.unzip(zip, worldDir);
                if (!Files.exists(worldDir.resolve("level.dat"))) {
                    SetupInstaller.deleteRecursively(worldDir);
                    throw new RuntimeException("The downloaded file is not a Minecraft world (no level.dat)");
                }
                return folder;
            } catch (Exception e) {
                throw new RuntimeException("Could not unzip world: " + e.getMessage(), e);
            }
        }).whenComplete((folder, error) -> minecraft.execute(() -> {
            deleteQuietly(zip);
            if (error == null) {
                status.succeed("Done! Downloaded as world \"" + folder + "\".");
                toast("Loaded from cloud", "Downloaded as world folder \"" + folder + "\".");
                onSuccess.run();
            } else {
                SaveLogic.LOGGER.error("Cloud download of {} failed", baseName, error);
                status.fail("Failed: " + rootMessage(error));
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
