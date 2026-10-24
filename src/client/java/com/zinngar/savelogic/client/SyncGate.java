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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Keeps synced worlds (those with a {@link WorldSyncMarker}) in step across computers: before opening one it checks
 * the cloud for a newer version or another computer having it open, and after leaving it uploads it (if auto-upload
 * is on) and releases the lock. A world that isn't synced yet but has the same name as a cloud world gets a one-time
 * question about which copy to use. Other worlds open exactly as before.
 */
public final class SyncGate {

    /** How long the list of cloud world names is trusted before asking GitHub again. */
    private static final long CLOUD_NAMES_TTL_MS = 2 * 60 * 1000;

    private static Set<String> cloudKeys = Set.of();
    private static long cloudKeysFetchedAt = 0;

    private SyncGate() {
    }

    public static void register() {
        // Runs after the singleplayer world has been saved and closed, so the files are complete.
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (server.isDedicatedServer()) {
                return;
            }
            Path worldDir = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            Optional<WorldSyncMarker> marker = WorldSyncMarker.readLinked(worldDir);
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

    /** Remembers which worlds are in the cloud, so opening a local-only world doesn't have to ask GitHub first. */
    public static synchronized void rememberCloudWorlds(List<CloudWorld> worlds) {
        Set<String> keys = new HashSet<>();
        worlds.forEach(world -> keys.add(world.key()));
        cloudKeys = keys;
        cloudKeysFetchedAt = System.currentTimeMillis();
    }

    private static synchronized Boolean knownInCloud(String name) {
        if (System.currentTimeMillis() - cloudKeysFetchedAt > CLOUD_NAMES_TTL_MS) {
            return null;
        }
        return cloudKeys.contains(GitHubStorageProvider.worldKey(name));
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
        Path worldDir = CloudSync.savesDir().resolve(levelId);
        Optional<WorldSyncMarker> marker = WorldSyncMarker.read(worldDir);
        if (marker.isPresent() && marker.get().unlinked()) {
            return false; // The player chose to keep this world separate from the cloud one.
        }
        if (marker.isEmpty() && Boolean.FALSE.equals(knownInCloud(levelId))) {
            return false; // Local-only world, and recently confirmed that the cloud has no world of this name.
        }
        if (TransferStatus.INSTANCE.isActive()) {
            // Most likely a world is still being zipped/uploaded after leaving it; opening one now could corrupt that.
            CloudSync.toast("Please wait", "Wait for the cloud upload or download to finish before opening this world.");
            return true;
        }

        Minecraft minecraft = Minecraft.getInstance();
        String cloudName = marker.map(WorldSyncMarker::cloudName).orElse(levelId);
        SyncCheckScreen checking = new SyncCheckScreen(cloudName, () -> minecraft.gui.setScreen(listScreen));
        minecraft.gui.setScreen(checking);

        if (marker.isPresent()) {
            provider.findWorld(cloudName).whenComplete((found, error) -> minecraft.execute(() -> {
                if (checking.isCancelled()) {
                    return;
                }
                if (error != null) {
                    CloudSync.toast("Couldn't check the cloud", "Playing your copy of \"" + cloudName + "\".");
                    open(levelId, cloudName, listScreen, false);
                } else if (found.isEmpty()) {
                    open(levelId, cloudName, listScreen, false); // Not in the cloud (any more): nothing to compare.
                } else {
                    checkLock(found.get(), marker.get(), levelId, listScreen);
                }
            }));
        } else {
            provider.listWorlds().whenComplete((worlds, error) -> minecraft.execute(() -> {
                if (checking.isCancelled()) {
                    return;
                }
                if (error != null) {
                    open(levelId, cloudName, listScreen, false);
                    return;
                }
                rememberCloudWorlds(worlds);
                Optional<CloudWorld> world = CloudSync.find(worlds, levelId);
                if (world.isEmpty()) {
                    open(levelId, cloudName, listScreen, false);
                } else {
                    askToLink(world.get(), levelId, listScreen);
                }
            }));
        }
        return true;
    }

    /** A local world that has never been synced has the same name as a cloud world: which copy should be used? */
    private static void askToLink(CloudWorld world, String levelId, Screen listScreen) {
        Minecraft minecraft = Minecraft.getInstance();
        Path worldDir = CloudSync.savesDir().resolve(levelId);
        CloudSave newest = world.newest();
        Instant localPlayed = Instant.ofEpochMilli(WorldSyncMarker.lastPlayed(worldDir));
        boolean cloudNewer = newest.played().isAfter(localPlayed);

        Choice useCloud = new Choice(Component.literal(cloudNewer ? "Use the cloud copy (newer)" : "Use the cloud copy"), () -> {
            minecraft.gui.setScreen(listScreen);
            CloudSync.downloadReplacing(world, newest, levelId, folder -> open(folder, world.worldName(), listScreen, true));
        });
        Choice useMine = new Choice(Component.literal(cloudNewer ? "Use my copy" : "Use my copy (newer)"), () -> {
            // Linked at the cloud's newest version, so leaving the world uploads this copy without a conflict.
            new WorldSyncMarker(world.worldName(), newest.stamp(), null).write(worldDir);
            open(levelId, world.worldName(), listScreen, true);
        });
        List<Choice> choices = new ArrayList<>(cloudNewer ? List.of(useCloud, useMine) : List.of(useMine, useCloud));
        choices.add(new Choice(Component.literal("Keep them separate"), () -> {
            WorldSyncMarker.unlinkedMarker().write(worldDir);
            open(levelId, levelId, listScreen, false);
        }));
        choices.add(new Choice(Component.translatable("gui.cancel"), () -> minecraft.gui.setScreen(listScreen)));

        minecraft.gui.setScreen(new ChoiceScreen(Component.literal("\"" + world.worldName() + "\" is also in the cloud"),
                Component.literal("Your copy: last played " + CloudText.when(localPlayed) + " on this computer."
                        + "\nCloud copy: last played " + CloudText.when(newest.played()) + " on "
                        + CloudText.device(newest.device()) + "."
                        + "\n\nPick the copy to keep playing; from then on this world stays in sync. Using the cloud copy"
                        + " backs your copy up first; using yours uploads it when you leave, keeping the cloud one in history."),
                choices));
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
        Instant localPlayed = Instant.ofEpochMilli(WorldSyncMarker.lastPlayed(CloudSync.savesDir().resolve(levelId)));
        minecraft.gui.setScreen(new ChoiceScreen(Component.literal("Newer version in the cloud"),
                Component.literal(CloudText.device(newest.device()) + " saved \"" + world.worldName()
                        + "\" after your copy was last synced."
                        + "\n\nYour copy: last played " + CloudText.when(localPlayed) + "."
                        + "\nCloud copy: last played " + CloudText.when(newest.played()) + " on "
                        + CloudText.device(newest.device()) + "."
                        + "\n\nDownloading it keeps a backup of your copy if you played it since it was synced."),
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
