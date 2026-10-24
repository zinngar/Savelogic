package com.zinngar.savelogic.client;

import com.zinngar.savelogic.GitHubStorageProvider;
import com.zinngar.savelogic.GitHubStorageProvider.Chain;
import com.zinngar.savelogic.GitHubStorageProvider.CloudSave;
import com.zinngar.savelogic.GitHubStorageProvider.CloudWorld;
import com.zinngar.savelogic.SaveLogic;
import com.zinngar.savelogic.client.screen.ChoiceScreen;
import com.zinngar.savelogic.client.screen.ChoiceScreen.Choice;
import com.zinngar.savelogic.setup.SetupInstaller;
import com.zinngar.savelogic.util.WorldManifest;
import com.zinngar.savelogic.util.WorldPackage;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/**
 * Uploads and downloads worlds, checking first whether another computer has saved a newer version, and keeps each
 * world's {@link WorldSyncMarker} up to date. Once a world is synced, uploads send only the files that changed and
 * downloads to a computer that is already in step fetch only the newer changes. Progress shows in
 * {@link TransferStatus}.
 */
public class CloudSync {

    /** A full copy is uploaded instead of changes after this many changes uploads in a row. */
    private static final int MAX_CHANGES_IN_A_ROW = 10;
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

        Optional<WorldSyncMarker> marker = WorldSyncMarker.readLinked(worldDir);
        String cloudName = marker.map(WorldSyncMarker::cloudName).orElse(folder);
        provider.findWorld(cloudName).whenComplete((found, error) -> minecraft.execute(() -> {
            if (error != null) {
                status.fail("Not uploaded: " + rootMessage(error));
                return;
            }
            boolean conflict = found.isPresent()
                    && (marker.isEmpty() || !found.get().newest().stamp().equals(marker.get().stamp()));
            if (!conflict) {
                upload(folder, cloudName, found.orElse(null));
                return;
            }

            CloudSave newest = found.get().newest();
            String who = CloudText.device(newest.device());
            if (automatic) {
                status.fail("Not uploaded: " + who + " saved a newer \"" + cloudName + "\" (played "
                        + CloudText.when(newest.played()) + "). Open the cloud menu to choose which to keep.");
                provider.setLock(cloudName, device(), false);
                return;
            }
            status.cancel();
            String message = (marker.isEmpty()
                    ? "\"" + cloudName + "\" is already in the cloud, and this copy has never been synced with it."
                    : who + " saved \"" + cloudName + "\" after this copy was last synced.")
                    + "\n\nYour copy: last played " + CloudText.when(Instant.ofEpochMilli(WorldSyncMarker.lastPlayed(worldDir)))
                    + ".\nCloud copy: last played " + CloudText.when(newest.played()) + " on " + who + "."
                    + "\n\nIf you upload yours, theirs stays in the version history.";
            minecraft.setScreen(new ChoiceScreen(Component.literal("Newer version in the cloud"), Component.literal(message),
                    List.of(
                            new Choice(Component.literal("Upload mine (keep theirs in history)"), () -> {
                                minecraft.setScreen(returnTo);
                                upload(folder, cloudName, null); // Full copy: theirs isn't what this copy builds on.
                            }),
                            new Choice(Component.literal("Upload as a separate world"), () -> {
                                minecraft.setScreen(returnTo);
                                upload(folder, cloudName + " (" + device().name() + ")", null);
                            }),
                            new Choice(Component.translatable("gui.cancel"), () -> minecraft.setScreen(returnTo)))));
        }));
    }

    /**
     * Zips and uploads the world. Sends only what changed when this copy is in step with the cloud's newest version
     * (world, if known, is that cloud world), otherwise a full copy.
     */
    private static void upload(String folder, String cloudName, CloudWorld world) {
        GitHubStorageProvider provider = CloudStorageManager.getProvider();
        TransferStatus status = TransferStatus.INSTANCE;
        if (!status.isActive() && !status.begin("Saving \"" + folder + "\" to cloud")) {
            return;
        }
        Path worldDir = savesDir().resolve(folder);
        Optional<WorldSyncMarker> marker = WorldSyncMarker.readLinked(worldDir);
        Path tempDir;
        try {
            tempDir = Files.createTempDirectory("savelogic-upload");
        } catch (Exception e) {
            status.fail("Could not create a temporary folder: " + e.getMessage());
            return;
        }
        Path zip = tempDir.resolve(folder + ".zip");
        long lastPlayed = WorldSyncMarker.lastPlayed(worldDir);

        CompletableFuture.supplyAsync(() -> {
            try {
                WorldManifest base = marker.map(WorldSyncMarker::files).orElse(null);
                WorldManifest manifest = WorldManifest.scan(worldDir, base, status);
                String parent = changesParent(world, marker.orElse(null), cloudName);
                if (parent != null) {
                    WorldManifest.Changes changes = manifest.changesSince(base);
                    if (changes.isEmpty()) {
                        return new Prepared(manifest, null, true);
                    }
                    if (changes.changedBytes() * 2 <= changes.totalBytes()) {
                        WorldPackage.writeChanges(worldDir, zip, manifest, changes, parent, status);
                        return new Prepared(manifest, parent, false);
                    }
                }
                WorldPackage.writeFull(worldDir, zip, manifest, status);
                return new Prepared(manifest, null, false);
            } catch (Exception e) {
                throw new RuntimeException("Could not zip world: " + e.getMessage(), e);
            }
        }).thenCompose(prepared -> {
            if (prepared.upToDate()) {
                // Nothing changed since the last sync: nothing to send, but still release this computer's lock.
                return provider.setLock(cloudName, device(), false).thenApply(ignored -> (Uploaded) null);
            }
            return provider.uploadWorld(cloudName, zip, prepared.parent(), lastPlayed, device(), SaveLogic.CONFIG.keepVersions, status)
                    .thenApply(stamp -> new Uploaded(stamp, prepared));
        }).whenComplete((uploaded, error) -> {
            deleteQuietly(zip);
            deleteQuietly(tempDir);
            if (error != null) {
                SaveLogic.LOGGER.error("Cloud upload of {} failed", folder, error);
                status.fail("Failed: " + rootMessage(error));
            } else if (uploaded == null) {
                status.succeed("\"" + cloudName + "\" is already up to date in the cloud.");
            } else {
                new WorldSyncMarker(cloudName, uploaded.stamp(), uploaded.prepared().manifest()).write(worldDir);
                status.succeed("Done! \"" + cloudName + "\" is saved on GitHub"
                        + (uploaded.prepared().parent() != null ? " (only the changes were uploaded)." : "."));
            }
        });
    }

    private record Prepared(WorldManifest manifest, String parent, boolean upToDate) {}

    private record Uploaded(String stamp, Prepared prepared) {}

    /**
     * The version a changes upload can build on: the cloud's newest, if this copy is in step with it and knows its
     * files. Null means a full copy is needed (first upload, unknown files, or many changes uploads in a row).
     */
    private static String changesParent(CloudWorld world, WorldSyncMarker marker, String cloudName) {
        if (world == null || marker == null || marker.files() == null || !marker.cloudName().equals(cloudName)
                || !world.newest().stamp().equals(marker.stamp())) {
            return null;
        }
        int inARow = 0;
        for (CloudSave v : world.versions()) {
            if (v.isFull()) {
                break;
            }
            inARow++;
        }
        return inARow < MAX_CHANGES_IN_A_ROW ? marker.stamp() : null;
    }

    // --- Download ---

    /**
     * Downloads a version of a cloud world. If a local world of that name exists, asks whether to replace it (after
     * backing it up) or keep both. onDone runs with the local folder name once the world is in place.
     */
    public static void downloadWorld(CloudWorld world, CloudSave version, Screen returnTo, Consumer<String> onDone) {
        Minecraft minecraft = Minecraft.getInstance();
        String folder = safeFolderName(world.worldName());
        Path local = savesDir().resolve(folder);
        if (!Files.exists(local)) {
            fetch(world, version, folder, false, onDone);
            return;
        }
        long localPlayed = WorldSyncMarker.lastPlayed(local);
        boolean cloudNewer = version.played().toEpochMilli() > localPlayed;
        minecraft.setScreen(new ChoiceScreen(Component.literal("You already have \"" + folder + "\""),
                Component.literal("Your copy: last played " + CloudText.when(Instant.ofEpochMilli(localPlayed)) + "."
                        + "\nThis cloud version: last played " + CloudText.when(version.played()) + " on "
                        + CloudText.device(version.device()) + "."
                        + "\n\n" + (cloudNewer ? "The cloud version is newer." : "Your copy is newer.")
                        + " Replacing moves your copy to savelogic-backups/worlds first."),
                List.of(
                        new Choice(Component.literal("Replace my copy"), () -> {
                            minecraft.setScreen(returnTo);
                            fetch(world, version, folder, true, onDone);
                        }),
                        new Choice(Component.literal("Keep both (download as a separate world)"), () -> {
                            minecraft.setScreen(returnTo);
                            fetch(world, version, folder, false, onDone);
                        }),
                        new Choice(Component.translatable("gui.cancel"), () -> minecraft.setScreen(returnTo)))));
    }

    /** Downloads over the local copy (backing it up first if it has unsynced changes); used by the open-world check. */
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
        Path local = savesDir.resolve(folder);
        Path incoming = savesDir.resolve(".savelogic-incoming-" + UUID.randomUUID());
        List<Path> zips = new ArrayList<>();

        CompletableFuture.supplyAsync(() -> {
            try {
                // If this copy is exactly a version in the target's history, only the newer changes are needed and can
                // be applied in place: nothing is lost because the cloud already has this copy.
                String have = replace ? unchangedSyncedStamp(local, world, status) : null;
                Chain chain = GitHubStorageProvider.chain(world, version, have);
                for (int i = 0; i < chain.versions().size(); i++) {
                    CloudSave part = chain.versions().get(i);
                    Path zip = Files.createTempFile("savelogic-download", ".zip");
                    zips.add(zip);
                    if (chain.versions().size() > 1) {
                        status.phase("Downloading " + (i + 1) + " of " + chain.versions().size(), 0);
                    }
                    provider.downloadSave(part, zip, status).join();
                }

                Path target = chain.fromHave() ? local : incoming;
                status.phase("Unzipping", 0);
                WorldPackage.Info last = null;
                for (Path zip : zips) {
                    last = WorldPackage.apply(zip, target);
                }
                if (!Files.exists(target.resolve("level.dat"))) {
                    throw new RuntimeException("The downloaded file is not a Minecraft world (no level.dat)");
                }

                String name = folder;
                if (!chain.fromHave()) {
                    if (replace && Files.exists(local)) {
                        Path backups = FabricLoader.getInstance().getGameDir().resolve("savelogic-backups").resolve("worlds");
                        Files.createDirectories(backups);
                        Files.move(local, backups.resolve(folder + "_" + LocalDateTime.now().format(BACKUP_STAMP)));
                    } else {
                        for (int i = 2; Files.exists(savesDir.resolve(name)); i++) {
                            name = folder + " (" + i + ")";
                        }
                    }
                    Files.move(incoming, savesDir.resolve(name));
                }
                Path worldDir = savesDir.resolve(name);
                WorldManifest manifest = last != null && last.manifest() != null
                        ? last.manifest().withTimesFrom(worldDir)
                        : WorldManifest.scan(worldDir, null, status); // Uploaded before manifests existed.
                new WorldSyncMarker(world.worldName(), version.stamp(), manifest).write(worldDir);
                return new Fetched(name, chain.fromHave(), chain.versions().size());
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("Could not unpack world: " + e.getMessage(), e);
            }
        }).whenComplete((fetched, error) -> minecraft.execute(() -> {
            zips.forEach(CloudSync::deleteQuietly);
            try {
                SetupInstaller.deleteRecursively(incoming);
            } catch (Exception ignored) {
            }
            if (error == null) {
                status.succeed(fetched.updatedInPlace()
                        ? "Done! \"" + fetched.folder() + "\" is up to date (downloaded only the changes)."
                        : "Done! \"" + world.worldName() + "\" is in your worlds as \"" + fetched.folder() + "\".");
                onDone.accept(fetched.folder());
            } else {
                SaveLogic.LOGGER.error("Cloud download of {} failed", world.worldName(), error);
                status.fail("Failed: " + rootMessage(error));
            }
        }));
    }

    private record Fetched(String folder, boolean updatedInPlace, int packages) {}

    /**
     * The cloud version this local copy is identical to, or null if it isn't synced with this world, doesn't know
     * its files, or has been played since it was last synced.
     */
    private static String unchangedSyncedStamp(Path local, CloudWorld world, TransferStatus status) throws Exception {
        Optional<WorldSyncMarker> marker = WorldSyncMarker.readLinked(local);
        if (marker.isEmpty() || marker.get().files() == null
                || !GitHubStorageProvider.worldKey(marker.get().cloudName()).equals(world.key())) {
            return null;
        }
        WorldManifest now = WorldManifest.scan(local, marker.get().files(), status);
        return now.changesSince(marker.get().files()).isEmpty() ? marker.get().stamp() : null;
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
        minecraft.execute(() -> SystemToast.addOrUpdate(minecraft.getToastManager(),
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
