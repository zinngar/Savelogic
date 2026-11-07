package com.zinngar.savelogic.client;

import com.zinngar.savelogic.SaveLogic;
import com.zinngar.savelogic.setup.SetupInstaller;
import com.zinngar.savelogic.update.SelfUpdater;
import com.zinngar.savelogic.update.SelfUpdater.Release;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * SaveLogic's own updates, from this project's GitHub releases. At most once a day it looks for a newer version and
 * only says so; downloading takes a click in Mod Updates, and {@link SetupInstaller} swaps the jar after Minecraft
 * closes, keeping the old one in savelogic-backups.
 */
public final class SelfUpdate {

    private static final long DAY_MS = 24 * 60 * 60 * 1000L;

    private SelfUpdate() {
    }

    public static void register() {
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            if (!SetupSync.ownJarNames().isEmpty()) {
                checkInBackground();
            }
        });
    }

    public static String currentVersion() {
        return FabricLoader.getInstance().getModContainer(SaveLogic.MOD_ID)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("dev");
    }

    /** False for a development copy, which has no jar to replace. */
    public static boolean canUpdateItself() {
        return !SetupSync.ownJarNames().isEmpty() || Boolean.getBoolean("savelogic.testSelfUpdate");
    }

    /** Looks at most once a day, remembering the answer so that the notice still shows on the next start. */
    public static void checkInBackground() {
        if (!SaveLogic.CONFIG.selfUpdateCheck) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - SaveLogic.CONFIG.lastSelfUpdateCheck < DAY_MS) {
            announce(SaveLogic.CONFIG.selfUpdateVersion);
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        find().whenComplete((release, error) -> minecraft.execute(() -> {
            if (error != null) {
                // Offline, most likely. It isn't worth interrupting anyone over; the next start tries again.
                SaveLogic.LOGGER.info("Couldn't check for a new SaveLogic version: {}", CloudSync.rootMessage(error));
                return;
            }
            SaveLogic.CONFIG.lastSelfUpdateCheck = now;
            SaveLogic.CONFIG.selfUpdateVersion = release.map(Release::version).orElse("");
            SaveLogic.CONFIG.save();
            announce(SaveLogic.CONFIG.selfUpdateVersion);
        }));
    }

    private static void announce(String version) {
        if (!version.isEmpty() && SelfUpdater.compare(version, currentVersion()) > 0 && pendingFileName().isEmpty()) {
            CloudSync.toast("SaveLogic " + version + " is available", "Get it from ☁ > Check for Mod Updates.");
        }
    }

    /** Asks GitHub for the newest version for this Minecraft version, if it is newer than this one. */
    public static CompletableFuture<Optional<Release>> find() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return new SelfUpdater(currentVersion()).newest(currentVersion(), SetupSync.minecraftVersion());
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        });
    }

    /** Downloads the release and leaves it waiting for Minecraft to close. Calls onReady on the render thread. */
    public static void stage(Release release, Runnable onReady) {
        TransferStatus status = TransferStatus.INSTANCE;
        if (!status.begin("Updating SaveLogic to " + release.version())) {
            CloudSync.toast("SaveLogic update not started", "Wait for the current upload or download to finish.");
            return;
        }
        status.phase("Downloading " + release.fileName(), release.size());
        Minecraft minecraft = Minecraft.getInstance();
        CompletableFuture.runAsync(() -> {
            try {
                new SelfUpdater(currentVersion()).stage(release, FabricLoader.getInstance().getGameDir(), status);
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }).whenComplete((ignored, error) -> minecraft.execute(() -> {
            if (error == null) {
                status.succeed("Downloaded. Close Minecraft to finish updating SaveLogic.");
                onReady.run();
            } else {
                SaveLogic.LOGGER.error("SaveLogic update download failed", error);
                status.fail("Failed: " + CloudSync.rootMessage(error));
            }
        }));
    }

    /** The jar waiting to be installed when Minecraft closes. */
    public static Optional<String> pendingFileName() {
        Path pending = FabricLoader.getInstance().getGameDir().resolve(SetupInstaller.PENDING_UPDATE_DIR);
        if (!Files.isDirectory(pending)) {
            return Optional.empty();
        }
        try (Stream<Path> list = Files.list(pending)) {
            return list.map(path -> path.getFileName().toString()).filter(name -> name.endsWith(".jar")).findFirst();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public static void cancelPending() {
        try {
            SetupInstaller.deleteRecursively(FabricLoader.getInstance().getGameDir().resolve(SetupInstaller.PENDING_UPDATE_DIR));
        } catch (Exception e) {
            SaveLogic.LOGGER.warn("Could not cancel the SaveLogic update", e);
        }
    }
}
