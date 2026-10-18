package com.zinngar.savelogic.gametest;

import com.zinngar.savelogic.GitHubStorageProvider;
import com.zinngar.savelogic.GitHubStorageProvider.CloudWorld;
import com.zinngar.savelogic.GitHubStorageProvider.Device;
import com.zinngar.savelogic.SaveLogic;
import com.zinngar.savelogic.client.CloudSync;
import com.zinngar.savelogic.client.SyncGate;
import com.zinngar.savelogic.client.TransferStatus;
import com.zinngar.savelogic.client.WorldSyncMarker;
import com.zinngar.savelogic.client.screen.ChoiceScreen;
import com.zinngar.savelogic.client.screen.SaveLogicSelectionScreen;
import com.zinngar.savelogic.client.screen.SettingsScreen;
import com.zinngar.savelogic.client.screen.VersionsScreen;
import com.zinngar.savelogic.util.ZipUtil;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Two computers sharing a world, run against an in-memory {@link FakeGitHub} so no real account is touched.
 * "This" computer is the MacBook; the Gaming PC is simulated by calling the
 * GitHub client directly with its own device id.
 */
public class WorldSyncFlowTest implements FabricClientGameTest {

    private static final Device PC = new Device("pc-id", "Gaming PC");

    @Override
    public void runTest(ClientGameTestContext context) {
        try (FakeGitHub fake = new FakeGitHub()) {
            System.setProperty("savelogic.testApiBase", fake.baseUrl());
            runFlow(context);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        } finally {
            System.clearProperty("savelogic.testApiBase");
        }
    }

    private void runFlow(ClientGameTestContext context) {
        context.runOnClient(mc -> {
            SaveLogic.CONFIG.github.repositoryUrl = "o/r";
            SaveLogic.CONFIG.github.personalAccessToken = "good-token";
            SaveLogic.CONFIG.deviceId = "mac-id";
            SaveLogic.CONFIG.deviceName = "MacBook";
            SaveLogic.CONFIG.autoUpload = true;
            SaveLogic.CONFIG.keepVersions = 5;
        });
        GitHubStorageProvider github = new GitHubStorageProvider("o/r", "good-token");

        // A world to sync.
        TestWorldSave save;
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            save = world.getWorldSave();
        }
        Path worldDir = save.getSaveDirectory();
        String folder = worldDir.getFileName().toString();
        context.waitForScreen(TitleScreen.class);

        // 1. First upload from this computer.
        context.runOnClient(mc -> CloudSync.uploadWorld(folder, false, mc.gui.screen()));
        waitForTransfer(context);
        String stamp1 = marker(worldDir).stamp();
        check(cloud(github, folder).newest().stamp().equals(stamp1), "first upload: marker matches the cloud version");

        // 2. The PC uploads a newer version; uploading again from here must ask first.
        uploadAsPc(github, folder, worldDir);
        context.runOnClient(mc -> CloudSync.uploadWorld(folder, false, mc.gui.screen()));
        context.waitForScreen(ChoiceScreen.class);
        context.takeScreenshot("sync-conflict-on-upload");
        context.clickScreenButton("Upload mine (keep theirs in history)");
        waitForTransfer(context);
        CloudWorld world = cloud(github, folder);
        check(world.versions().size() == 3 && world.newest().device().equals("MacBook")
                        && world.versions().get(1).device().equals("Gaming PC"),
                "after choosing to upload mine, the PC's version is kept in history: "
                        + world.versions().stream().map(v -> v.stamp() + "/" + v.device()).toList());

        // 3. The PC has the world open: opening it here warns.
        github.setLock(folder, PC, true).join();
        context.runOnClient(mc -> SyncGate.interceptOpen(folder, new SelectWorldScreen(new TitleScreen())));
        context.waitForScreen(ChoiceScreen.class);
        context.takeScreenshot("sync-world-open-elsewhere");
        context.clickScreenButton("Cancel");
        context.waitForScreen(SelectWorldScreen.class);

        // 4. The PC saves a newer version and closes the world: opening here offers to download it.
        String pcStamp = uploadAsPc(github, folder, worldDir);
        check(cloud(github, folder).lock() == null, "the PC's upload released its lock");
        context.runOnClient(mc -> SyncGate.interceptOpen(folder, new SelectWorldScreen(new TitleScreen())));
        context.waitForScreen(ChoiceScreen.class);
        context.takeScreenshot("sync-newer-on-open");
        context.clickScreenButton("Cancel");
        context.waitForScreen(SelectWorldScreen.class);
        context.setScreen(TitleScreen::new);

        // Simulate "Download it and play" without leaving the test's control of the world: replace, then open.
        context.runOnClient(mc -> CloudSync.downloadWorld(cloud(github, folder), cloud(github, folder).newest(), mc.gui.screen(), f -> {}));
        context.waitForScreen(ChoiceScreen.class);
        context.takeScreenshot("sync-download-existing");
        context.clickScreenButton("Replace my copy");
        waitForTransfer(context);
        check(marker(worldDir).stamp().equals(pcStamp), "after replacing, this copy is in step with the PC's version");
        check(hasBackup(folder), "the replaced copy was backed up first");

        // 5. Playing and leaving the world uploads it automatically.
        try (TestSingleplayerContext ignored = save.open()) {
            context.waitTicks(20);
        }
        context.waitForScreen(TitleScreen.class);
        waitForTransfer(context);
        world = cloud(github, folder);
        check(world.newest().device().equals("MacBook") && world.newest().stamp().equals(marker(worldDir).stamp()),
                "leaving the world auto-uploaded it");
        check(world.versions().size() == 5, "history kept at the 5-version limit (had " + world.versions().size() + ")");

        // 6. The menus.
        context.setScreen(() -> new SaveLogicSelectionScreen(new TitleScreen()));
        context.waitTicks(40);
        context.takeScreenshot("sync-cloud-world-list");
        CloudWorld finalWorld = world;
        context.setScreen(() -> new VersionsScreen(new TitleScreen(), finalWorld));
        context.waitTicks(5);
        context.takeScreenshot("sync-versions");
        context.setScreen(() -> new SettingsScreen(new TitleScreen()));
        context.waitTicks(5);
        context.takeScreenshot("sync-settings");
        context.setScreen(TitleScreen::new);
        context.waitTicks(5);
    }

    private static String uploadAsPc(GitHubStorageProvider github, String name, Path worldDir) {
        try {
            Thread.sleep(1100); // Distinct version stamps.
            Path zip = Files.createTempFile("pc-upload", ".zip");
            ZipUtil.zipWorld(worldDir, zip);
            return github.uploadWorld(name, zip, PC, 5, com.zinngar.savelogic.util.Progress.NONE).join();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static CloudWorld cloud(GitHubStorageProvider github, String name) {
        List<CloudWorld> worlds = github.listWorlds().join();
        return worlds.stream().filter(w -> w.worldName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("World not in cloud: " + name));
    }

    private static WorldSyncMarker marker(Path worldDir) {
        return WorldSyncMarker.read(worldDir).orElseThrow(() -> new AssertionError("No sync marker in " + worldDir));
    }

    private static boolean hasBackup(String folder) {
        Path backups = FabricLoader.getInstance().getGameDir().resolve("savelogic-backups").resolve("worlds");
        try (var list = Files.list(backups)) {
            return list.anyMatch(p -> p.getFileName().toString().startsWith(folder + "_"));
        } catch (Exception e) {
            return false;
        }
    }

    private static void waitForTransfer(ClientGameTestContext context) {
        context.waitTicks(2);
        context.waitFor(mc -> !TransferStatus.INSTANCE.isActive(), 20 * 60);
        context.waitTicks(2);
    }

    private static void check(boolean ok, String what) {
        System.out.println("[WorldSyncFlowTest] " + (ok ? "PASS " : "FAIL ") + what);
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
