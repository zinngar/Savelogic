package com.zinngar.savelogic.client;

import com.zinngar.savelogic.GitHubStorageProvider;
import com.zinngar.savelogic.GitHubStorageProvider.CloudSave;
import com.zinngar.savelogic.GitHubStorageProvider.CloudWorld;
import com.zinngar.savelogic.SaveLogic;
import com.zinngar.savelogic.client.screen.ChoiceScreen;
import com.zinngar.savelogic.client.screen.ChoiceScreen.Choice;
import com.zinngar.savelogic.setup.SetupInstaller;
import com.zinngar.savelogic.util.ZipUtil;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/**
 * Uploads and downloads worlds, checking first whether another computer has saved a newer version, and keeps each
 * world's {@link WorldSyncMarker} up to date. Progress shows in {@link TransferStatus}.
 */
public class CloudSync {

    private static final DateTimeFormatter BACKUP_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    // --- Upload ---

    /**
     * Uploads a local world. If another computer saved a newer version since this copy was last synced, a manual
     * upload asks what to do (returning to returnTo afterwards); an automatic one doesn't overwrite and says so.
     */
    public static void uploadWorld(String folder, boolean automatic, Screen returnTo) {
        GitHubStorageProvider provider = CloudStorageManager.getProvider();
        if (provider == null) {
            toast("Cloud save failed", "Set up your GitHub repository in SaveLogic settings.");
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Path worldDir = savesDir().resolve(folder);
        if (!Files.isDirectory(worldDir)) {
            toast("Cloud save failed", "World folder not found: " + folder);
            return;
        }
        TransferStatus status = TransferStatus.INSTANCE;
        if (!status.begin("Saving \"" + folder + "\" to cloud")) {
            toast("Cloud save not started", "Wait for the current upload or download to finish.");
            return;
        }
        status.phase("Checking the cloud", 0);

        Optional<WorldSyncMarker> marker = WorldSyncMarker.read(worldDir);
        String cloudName = marker.map(WorldSyncMarker::cloudName).orElse(folder);
        provider.listWorlds().whenComplete((worlds, error) -> minecraft.execute(() -> {
            if (error != null) {
                status.fail("Not uploaded: " + rootMessage(error));
                return;
            }
            Optional<CloudWorld> world = find(worlds, cloudName);
            boolean conflict = world.isPresent()
                    && (marker.isEmpty() || !world.get().newest().stamp().equals(marker.get().stamp()));
            if (!conflict) {
                upload(folder, cloudName);
                return;
            }

            CloudSave newest = world.get().newest();
            String who = CloudText.device(newest.device());
            String when = CloudText.when(newest.uploaded());
            if (automatic) {
                status.fail("Not uploaded: " + who + " saved \"" + cloudName + "\" " + when
                        + " after this copy was synced. Open the cloud menu to choose what to keep.");
                provider.setLock(cloudName, device(), false);
                return;
            }
            status.cancel();
            String message = marker.isEmpty()
                    ? "\"" + cloudName + "\" is already in the cloud (saved " + when + " on " + who
                            + "), and this copy has never been synced with it."
                    : who + " saved \"" + cloudName + "\" " + when + ", after this copy was last synced.";
            minecraft.gui.setScreen(new ChoiceScreen(Component.literal("Newer version in the cloud"),
                    Component.literal(message + " If you upload yours, theirs stays in the version history."),
                    List.of(
                            new Choice(Component.literal("Upload mine (keep theirs in history)"), () -> {
                                minecraft.gui.setScreen(returnTo);
                                upload(folder, cloudName);
                            }),
                            new Choice(Component.literal("Upload as a separate world"), () -> {
                                minecraft.gui.setScreen(returnTo);
                                upload(folder, cloudName + " (" + device().name() + ")");
                            }),
                            new Choice(Component.translatable("gui.cancel"), () -> minecraft.gui.setScreen(returnTo)))));
        }));
    }

    private static void upload(String folder, String cloudName) {
        GitHubStorageProvider provider = CloudStorageManager.getProvider();
        TransferStatus status = TransferStatus.INSTANCE;
        if (!status.isActive() && !status.begin("Saving \"" + folder + "\" to cloud")) {
            return;
        }
        Path worldDir = savesDir().resolve(folder);
        Path tempDir;
        try {
            tempDir = Files.createTempDirectory("savelogic-upload");
        } catch (Exception e) {
            status.fail("Could not create a temporary folder: " + e.getMessage());
            return;
        }
        Path zip = tempDir.resolve(folder + ".zip");
        CompletableFuture.runAsync(() -> {
            try {
                ZipUtil.zipWorld(worldDir, zip, status);
            } catch (Exception e) {
                throw new RuntimeException("Could not zip world: " + e.getMessage(), e);
            }
        }).thenCompose(ignored -> provider.uploadWorld(cloudName, zip, device(), SaveLogic.CONFIG.keepVersions, status))
          .whenComplete((stamp, error) -> {
              deleteQuietly(zip);
              deleteQuietly(tempDir);
              if (error == null) {
                  new WorldSyncMarker(cloudName, stamp).write(worldDir);
                  status.succeed("Done! \"" + cloudName + "\" is saved on GitHub.");
              } else {
                  SaveLogic.LOGGER.error("Cloud upload of {} failed", folder, error);
                  status.fail("Failed: " + rootMessage(error));
              }
          });
    }

    // --- Download ---

    /**
     * Downloads a version of a cloud world. If a local world of that name exists, asks whether to replace it (after
     * backing it up) or keep both. onDone runs with the local folder name once the world is in place.
     */
    public static void downloadWorld(CloudWorld world, CloudSave version, Screen returnTo, Consumer<String> onDone) {
        Minecraft minecraft = Minecraft.getInstance();
        String folder = safeFolderName(world.worldName());
        if (!Files.exists(savesDir().resolve(folder))) {
            fetch(world, version, folder, false, onDone);
            return;
        }
        minecraft.gui.setScreen(new ChoiceScreen(Component.literal("You already have \"" + folder + "\""),
                Component.literal("Replace your copy with the version saved " + CloudText.when(version.uploaded()) + " on "
                        + CloudText.device(version.device()) + "? Your copy is backed up to savelogic-backups/worlds first."),
                List.of(
                        new Choice(Component.literal("Replace my copy"), () -> {
                            minecraft.gui.setScreen(returnTo);
                            fetch(world, version, folder, true, onDone);
                        }),
                        new Choice(Component.literal("Keep both (download as a new world)"), () -> {
                            minecraft.gui.setScreen(returnTo);
                            fetch(world, version, folder, false, onDone);
                        }),
                        new Choice(Component.translatable("gui.cancel"), () -> minecraft.gui.setScreen(returnTo)))));
    }

    /** Downloads straight over the local copy (backing it up first); used by the open-world check. */
    static void downloadReplacing(CloudWorld world, CloudSave version, String folder, Consumer<String> onDone) {
        fetch(world, version, folder, true, onDone);
    }

    private static void fetch(CloudWorld world, CloudSave version, String folder, boolean replace, Consumer<String> onDone) {
        GitHubStorageProvider provider = CloudStorageManager.getProvider();
        TransferStatus status = TransferStatus.INSTANCE;
        if (provider == null || !status.begin("Loading \"" + world.worldName() + "\" from cloud")) {
            toast("Download not started", "Wait for the current upload or download to finish.");
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Path savesDir = savesDir();
        Path incoming = savesDir.resolve(".savelogic-incoming-" + UUID.randomUUID());
        Path zip;
        try {
            zip = Files.createTempFile("savelogic-download", ".zip");
        } catch (Exception e) {
            status.fail("Could not create a temporary file: " + e.getMessage());
            return;
        }

        provider.downloadSave(version, zip, status).thenApply(ignored -> {
            status.phase("Unzipping", 0);
            try {
                ZipUtil.unzip(zip, incoming);
                if (!Files.exists(incoming.resolve("level.dat"))) {
                    throw new RuntimeException("The downloaded file is not a Minecraft world (no level.dat)");
                }
                String target = folder;
                if (replace && Files.exists(savesDir.resolve(folder))) {
                    Path backups = FabricLoader.getInstance().getGameDir().resolve("savelogic-backups").resolve("worlds");
                    Files.createDirectories(backups);
                    Files.move(savesDir.resolve(folder), backups.resolve(folder + "_" + LocalDateTime.now().format(BACKUP_STAMP)));
                } else {
                    for (int i = 2; Files.exists(savesDir.resolve(target)); i++) {
                        target = folder + " (" + i + ")";
                    }
                }
                Files.move(incoming, savesDir.resolve(target));
                new WorldSyncMarker(world.worldName(), version.stamp()).write(savesDir.resolve(target));
                return target;
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("Could not unpack world: " + e.getMessage(), e);
            }
        }).whenComplete((target, error) -> minecraft.execute(() -> {
            deleteQuietly(zip);
            try {
                SetupInstaller.deleteRecursively(incoming);
            } catch (Exception ignored) {
            }
            if (error == null) {
                status.succeed("Done! \"" + world.worldName() + "\" is in your worlds as \"" + target + "\".");
                onDone.accept(target);
            } else {
                SaveLogic.LOGGER.error("Cloud download of {} failed", world.worldName(), error);
                status.fail("Failed: " + rootMessage(error));
            }
        }));
    }

    // --- Helpers ---

    public static GitHubStorageProvider.Device device() {
        return SaveLogic.CONFIG.device();
    }

    static Path savesDir() {
        return Minecraft.getInstance().getLevelSource().getBaseDir();
    }

    static Optional<CloudWorld> find(List<CloudWorld> worlds, String cloudName) {
        String key = GitHubStorageProvider.worldKey(cloudName);
        return worlds.stream().filter(world -> world.key().equals(key)).findFirst();
    }

    /** The cloud name comes from the repository, so strip anything that could leave the saves folder. */
    static String safeFolderName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").replaceFirst("^\\.+", "_");
    }

    public static void toast(String title, String message) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> SystemToast.addOrUpdate(minecraft.gui.toastManager(),
                SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.literal(title), Component.literal(message)));
    }

    static String rootMessage(Throwable error) {
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
