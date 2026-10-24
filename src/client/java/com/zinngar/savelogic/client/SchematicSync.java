package com.zinngar.savelogic.client;

import com.zinngar.savelogic.GitHubStorageProvider;
import com.zinngar.savelogic.SaveLogic;
import com.zinngar.savelogic.schematics.SchematicSyncer;
import com.zinngar.savelogic.schematics.SchematicSyncer.Summary;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Keeps the Litematica schematics folder in sync with the cloud: automatically when Minecraft starts and when
 * leaving a world (if enabled in Settings), and from the cloud menu's button. Runs in the background and reports
 * with a toast, so it never blocks a world upload or download.
 */
public final class SchematicSync {

    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    private SchematicSync() {
    }

    public static void register() {
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> syncAutomatically());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (!server.isDedicatedServer()) {
                syncAutomatically();
            }
        });
    }

    private static void syncAutomatically() {
        if (SaveLogic.CONFIG.schematicsAutoSync) {
            sync(false, summary -> {});
        }
    }

    public static boolean isRunning() {
        return RUNNING.get();
    }

    /** Syncs now. A manual sync always reports its result; an automatic one only when something changed or failed. */
    public static void sync(boolean manual, Consumer<Summary> onDone) {
        GitHubStorageProvider provider = CloudStorageManager.getProvider();
        if (provider == null) {
            if (manual) {
                CloudSync.toast("Schematics not synced", "Set up your GitHub repository in SaveLogic settings.");
            }
            return;
        }
        if (!RUNNING.compareAndSet(false, true)) {
            if (manual) {
                CloudSync.toast("Schematics", "A schematics sync is already running.");
            }
            return;
        }
        Path gameDir = FabricLoader.getInstance().getGameDir();
        SchematicSyncer syncer = new SchematicSyncer(provider, gameDir.resolve("schematics"),
                // Not under config/: that folder is shared between computers by "Mods & configs".
                gameDir.resolve(".savelogic").resolve("schematics-state.json"),
                gameDir.resolve("savelogic-backups").resolve("schematics"), CloudSync.device());
        CompletableFuture.supplyAsync(() -> {
            try {
                return syncer.sync();
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }).whenComplete((summary, error) -> {
            RUNNING.set(false);
            if (error != null) {
                SaveLogic.LOGGER.error("Schematics sync failed", error);
                CloudSync.toast("Schematics sync failed", CloudSync.rootMessage(error));
                return;
            }
            SaveLogic.LOGGER.info("Schematics sync: {}", summary.describe());
            if (manual || summary.changedAnything()) {
                CloudSync.toast("Schematics synced", summary.describe().substring(0, 1).toUpperCase() + summary.describe().substring(1) + ".");
            }
            net.minecraft.client.Minecraft.getInstance().execute(() -> onDone.accept(summary));
        });
    }
}
