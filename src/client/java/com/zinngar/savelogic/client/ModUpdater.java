package com.zinngar.savelogic.client;

import com.zinngar.savelogic.SaveLogic;
import com.zinngar.savelogic.mods.ModUpdatePlanner;
import com.zinngar.savelogic.mods.ModUpdatePlanner.Dependency;
import com.zinngar.savelogic.mods.ModUpdatePlanner.InstalledMod;
import com.zinngar.savelogic.mods.ModUpdatePlanner.ModStatus;
import com.zinngar.savelogic.mods.ModUpdatePlanner.Plan;
import com.zinngar.savelogic.mods.ModrinthClient;
import com.zinngar.savelogic.mods.ModrinthClient.Version;
import com.zinngar.savelogic.setup.SetupInstaller;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Checks the mods folder against Modrinth and stages chosen updates. Updated jars can't replace the ones the running
 * game has open, so they go into the same pending folder as a downloaded setup and {@link SetupInstaller} swaps them
 * in after Minecraft closes, moving the current mods to a backup first.
 */
public final class ModUpdater {

    private ModUpdater() {
    }

    public static ModrinthClient modrinth() {
        String version = FabricLoader.getInstance().getModContainer(SaveLogic.MOD_ID)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("dev");
        return new ModrinthClient(version);
    }

    /** Scans the mods folder and asks Modrinth; calls back on the render thread. */
    public static void check(Consumer<Plan> onResult, Consumer<String> onError) {
        Minecraft minecraft = Minecraft.getInstance();
        CompletableFuture.supplyAsync(() -> {
            try {
                List<InstalledMod> installed = ModUpdatePlanner.scan(modsDir(), SetupSync.ownJarNames(),
                        com.zinngar.savelogic.util.Progress.NONE);
                return ModUpdatePlanner.plan(installed, modrinth(), SetupSync.minecraftVersion(), "fabric");
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }).whenComplete((plan, error) -> minecraft.execute(() -> {
            if (error == null) {
                onResult.accept(plan);
            } else {
                onError.accept(CloudSync.rootMessage(error));
            }
        }));
    }

    /**
     * Downloads the chosen updates (and the libraries they need) into the pending folder, together with copies of
     * every other installed mod, so that installing replaces the mods folder with the updated set. Calls onReady on
     * the render thread once everything is downloaded and checked.
     */
    public static void stage(List<ModStatus> updates, List<Dependency> dependencies, Runnable onReady) {
        TransferStatus status = TransferStatus.INSTANCE;
        if (SetupSync.isInstallPending()) {
            CloudSync.toast("Mod updates not started", "Another install is already waiting for Minecraft to close.");
            return;
        }
        if (!status.begin("Updating " + updates.size() + " mod" + (updates.size() == 1 ? "" : "s"))) {
            CloudSync.toast("Mod updates not started", "Wait for the current upload or download to finish.");
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Path pending = FabricLoader.getInstance().getGameDir().resolve(SetupInstaller.PENDING_DIR);
        Path pendingMods = pending.resolve("mods");

        CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(pendingMods);
                Set<Path> replaced = new HashSet<>();
                updates.forEach(u -> replaced.add(u.installed().jar()));
                Set<String> own = SetupSync.ownJarNames();
                try (Stream<Path> list = Files.list(modsDir())) {
                    for (Path jar : list.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().endsWith(".jar")).toList()) {
                        if (!replaced.contains(jar) && !own.contains(jar.getFileName().toString())) {
                            Files.copy(jar, pendingMods.resolve(jar.getFileName().toString()));
                        }
                    }
                }
                List<Version> downloads = new ArrayList<>();
                List<String> names = new ArrayList<>();
                updates.forEach(u -> {
                    downloads.add(u.latest());
                    names.add(u.title());
                });
                dependencies.forEach(d -> {
                    downloads.add(d.version());
                    names.add(d.title());
                });
                ModrinthClient modrinth = modrinth();
                for (int i = 0; i < downloads.size(); i++) {
                    Version version = downloads.get(i);
                    status.phase("Downloading " + names.get(i) + " (" + (i + 1) + " of " + downloads.size() + ")", version.size());
                    Path target = pendingMods.resolve(version.fileName());
                    Files.deleteIfExists(target);
                    modrinth.download(version, target, status);
                }
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }).whenComplete((ignored, error) -> minecraft.execute(() -> {
            if (error == null) {
                status.succeed("Downloaded. Close Minecraft to finish updating your mods.");
                onReady.run();
            } else {
                SetupSync.cancelPendingInstall();
                SaveLogic.LOGGER.error("Mod update download failed", error);
                status.fail("Failed: " + CloudSync.rootMessage(error));
            }
        }));
    }

    private static Path modsDir() {
        return FabricLoader.getInstance().getGameDir().resolve("mods");
    }
}
