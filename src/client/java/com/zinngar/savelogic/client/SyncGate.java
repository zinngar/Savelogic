package com.zinngar.savelogic.client;

import com.zinngar.savelogic.GitHubStorageProvider;
import com.zinngar.savelogic.GitHubStorageProvider.CloudSave;
import com.zinngar.savelogic.GitHubStorageProvider.CloudWorld;
import com.zinngar.savelogic.GitHubStorageProvider.Lock;
import com.zinngar.savelogic.SaveLogic;
import com.zinngar.savelogic.client.screen.ChoiceScreen;
import com.zinngar.savelogic.client.screen.ChoiceScreen.Choice;
import com.zinngar.savelogic.client.screen.SyncCheckScreen;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Keeps synced worlds (those with a {@link WorldSyncMarker}) in step across computers: before opening one it checks
 * the cloud for a newer version or another computer having it open, and after leaving it uploads it (if auto-upload
 * is on) and releases the lock. Worlds that were never synced open exactly as before.
 */
public final class SyncGate {

    private SyncGate() {
    }

    public static void register() {
        // Runs after the singleplayer world has been saved and closed, so the files are complete.
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (server.isDedicatedServer()) {
                return;
            }
            Path worldDir = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            Optional<WorldSyncMarker> marker = WorldSyncMarker.read(worldDir);
            GitHubStorageProvider provider = CloudStorageManager.getProvider();
            if (marker.isEmpty() || provider == null) {
                return;
            }
            String folder = worldDir.getFileName().toString();
            Minecraft.getInstance().execute(() -> {
                if (SaveLogic.CONFIG.autoUpload) {
                    CloudSync.uploadWorld(folder, true, null); // Also releases this computer's lock.
                } else {
                    provider.setLock(marker.get().cloudName(), CloudSync.device(), false);
                }
            });
        });
    }

    /**
     * Called when the player clicks Play on a world. Returns true if SaveLogic took over (it will open the world
     * itself once the cloud check is done), false to let the game open it normally.
     */
    public static boolean interceptOpen(String levelId, Screen listScreen) {
        GitHubStorageProvider provider = CloudStorageManager.getProvider();
        if (provider == null) {
            return false;
        }
        Optional<WorldSyncMarker> marker = WorldSyncMarker.read(CloudSync.savesDir().resolve(levelId));
        if (marker.isEmpty()) {
            return false;
        }
        if (TransferStatus.INSTANCE.isActive()) {
            // Most likely this world is still being zipped/uploaded after leaving it; opening it now could corrupt that.
            CloudSync.toast("Please wait", "Wait for the cloud upload or download to finish before opening a synced world.");
            return true;
        }
        Minecraft minecraft = Minecraft.getInstance();
        String cloudName = marker.get().cloudName();
        SyncCheckScreen checking = new SyncCheckScreen(cloudName, () -> minecraft.gui.setScreen(listScreen));
        minecraft.gui.setScreen(checking);

        provider.listWorlds().whenComplete((worlds, error) -> minecraft.execute(() -> {
            if (checking.isCancelled()) {
                return;
            }
            if (error != null) {
                CloudSync.toast("Couldn't check the cloud", "Playing your copy of \"" + cloudName + "\".");
                open(levelId, cloudName, listScreen, false);
                return;
            }
            Optional<CloudWorld> world = CloudSync.find(worlds, cloudName);
            if (world.isEmpty()) {
                open(levelId, cloudName, listScreen, false); // Not in the cloud (any more): nothing to compare.
                return;
            }
            checkLock(world.get(), marker.get(), levelId, listScreen);
        }));
        return true;
    }

    private static void checkLock(CloudWorld world, WorldSyncMarker marker, String levelId, Screen listScreen) {
        Lock lock = world.lock();
        if (lock == null || lock.deviceId().equals(CloudSync.device().id())) {
            checkNewer(world, marker, levelId, listScreen);
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.gui.setScreen(new ChoiceScreen(
                Component.literal("\"" + world.worldName() + "\" is open on " + CloudText.device(lock.device())),
                Component.literal("It has been open there since " + CloudText.when(Instant.ofEpochMilli(lock.since()))
                        + ". If you play here at the same time, progress on one of the computers can be lost. "
                        + "If that computer crashed or was switched off while playing, it's safe to continue."),
                List.of(
                        new Choice(Component.literal("Play anyway"), () -> checkNewer(world, marker, levelId, listScreen)),
                        new Choice(Component.translatable("gui.cancel"), () -> minecraft.gui.setScreen(listScreen)))));
    }

    private static void checkNewer(CloudWorld world, WorldSyncMarker marker, String levelId, Screen listScreen) {
        CloudSave newest = world.newest();
        if (newest.stamp().equals(marker.stamp())) {
            open(levelId, world.worldName(), listScreen, true);
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.gui.setScreen(new ChoiceScreen(Component.literal("Newer version in the cloud"),
                Component.literal(CloudText.device(newest.device()) + " saved \"" + world.worldName() + "\" "
                        + CloudText.when(newest.uploaded()) + ", after your copy was last synced. "
                        + "Downloading it backs up your copy first."),
                List.of(
                        new Choice(Component.literal("Download it and play"), () -> {
                            minecraft.gui.setScreen(listScreen);
                            CloudSync.downloadReplacing(world, newest, levelId,
                                    folder -> open(folder, world.worldName(), listScreen, true));
                        }),
                        new Choice(Component.literal("Play my copy"), () -> open(levelId, world.worldName(), listScreen, true)),
                        new Choice(Component.translatable("gui.cancel"), () -> minecraft.gui.setScreen(listScreen)))));
    }

    /** Opens the world the same way the world list does, first marking it as open on this computer. */
    private static void open(String levelId, String cloudName, Screen listScreen, boolean lock) {
        GitHubStorageProvider provider = CloudStorageManager.getProvider();
        if (lock && provider != null) {
            provider.setLock(cloudName, CloudSync.device(), true).exceptionally(error -> {
                SaveLogic.LOGGER.warn("Could not mark {} as open on this computer: {}", cloudName, error.getMessage());
                return null;
            });
        }
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.createWorldOpenFlows().openWorld(levelId, () -> minecraft.gui.setScreen(listScreen));
    }
}
