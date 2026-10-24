package com.zinngar.savelogic.gametest;

import com.zinngar.savelogic.GitHubStorageProvider;
import com.zinngar.savelogic.GitHubStorageProvider.CloudSave;
import com.zinngar.savelogic.GitHubStorageProvider.CloudWorld;
import com.zinngar.savelogic.GitHubStorageProvider.Device;
import com.zinngar.savelogic.SaveLogic;
import com.zinngar.savelogic.client.CloudSync;
import com.zinngar.savelogic.client.SyncGate;
import com.zinngar.savelogic.client.TransferStatus;
import com.zinngar.savelogic.client.WorldSyncMarker;
import com.zinngar.savelogic.client.screen.ChoiceScreen;
import com.zinngar.savelogic.client.screen.SyncedWorldsScreen;
import com.zinngar.savelogic.setup.SetupInstaller;
import com.zinngar.savelogic.util.Progress;
import com.zinngar.savelogic.util.WorldManifest;
import com.zinngar.savelogic.util.WorldPackage;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Changes-only syncing, against an in-memory {@link FakeGitHub}: uploads after the first send only what changed,
 * downloads rebuild a world from its full copy plus changes (or apply just the newer changes in place), history
 * cleanup keeps what the kept versions need, and an unsynced copy with a cloud world's name gets asked once.
 */
public class ChangesSyncTest implements FabricClientGameTest {

    private static final Device PC = new Device("pc-id", "Gaming PC");

    @Override
    public void runTest(ClientGameTestContext context) {
        try (FakeGitHub fake = new FakeGitHub()) {
            System.setProperty("savelogic.testApiBase", fake.baseUrl());
            run(context);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        } finally {
            System.clearProperty("savelogic.testApiBase");
        }
    }

    private void run(ClientGameTestContext context) {
        context.runOnClient(mc -> {
            SaveLogic.CONFIG.github.repositoryUrl = "o/r";
            SaveLogic.CONFIG.github.personalAccessToken = "good-token";
            SaveLogic.CONFIG.deviceId = "mac-id";
            SaveLogic.CONFIG.deviceName = "MacBook";
            SaveLogic.CONFIG.autoUpload = true;
            SaveLogic.CONFIG.keepVersions = 5;
        });
        GitHubStorageProvider github = new GitHubStorageProvider("o/r", "good-token");

        Path worldDir;
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            worldDir = world.getWorldSave().getSaveDirectory();
        }
        context.waitForScreen(TitleScreen.class);
        String folder = worldDir.getFileName().toString();
        // Real worlds are mostly regions nobody visits in a session; stand in for those so a short play session
        // changes only a small part of the world, as it does in practice.
        write(worldDir.resolve("region/r.99.99.mca"), 8 * 1024 * 1024);

        // 1. First upload is a full copy; the next ones send only what changed.
        upload(context, folder);
        CloudWorld cloud = cloud(github, folder);
        long fullSize = cloud.newest().size();
        check(cloud.versions().size() == 1 && cloud.newest().isFull(), "first upload is a full copy (" + fullSize + " bytes)");
        check(marker(worldDir).files() != null, "the sync marker remembers every file's fingerprint");

        write(worldDir.resolve("data/savelogic-test.dat"), 1000);
        upload(context, folder);
        cloud = cloud(github, folder);
        check(cloud.versions().size() == 2 && !cloud.newest().isFull()
                        && cloud.newest().parent().equals(cloud.versions().get(1).stamp()),
                "second upload is changes only, building on the first");
        check(cloud.newest().size() * 10 < fullSize, "the changes upload is small: " + cloud.newest().size() + " of " + fullSize + " bytes");
        check(lastResultMentions("only the changes"), "the result says only changes were uploaded");

        delete(worldDir.resolve("data/savelogic-test.dat"));
        upload(context, folder);
        check(cloud(github, folder).versions().size() == 3 && !cloud(github, folder).newest().isFull(), "a deleted file is a changes upload too");

        upload(context, folder);
        check(cloud(github, folder).versions().size() == 3, "nothing changed: nothing uploaded");

        // 2. Rebuilding from the full copy plus every changes package gives exactly the same world.
        context.runOnClient(mc -> CloudSync.downloadWorld(cloud(github, folder), cloud(github, folder).newest(), mc.gui.screen(), f -> {}));
        context.waitForScreen(ChoiceScreen.class);
        context.takeScreenshot("changes-download-existing");
        context.clickScreenButton("Keep both (download as a separate world)");
        waitForTransfer(context);
        Path copy = worldDir.resolveSibling(folder + " (2)");
        check(sameFiles(worldDir, copy), "a copy rebuilt from full + changes matches the original file for file");
        check(marker(copy).stamp().equals(cloud(github, folder).newest().stamp()), "the rebuilt copy is marked as in step");

        // 3. The PC uploads a changes package; replacing our in-step copy applies only those changes, in place.
        String pcStamp = uploadChangesAsPc(github, folder, worldDir, "data/from-pc.dat");
        context.runOnClient(mc -> CloudSync.downloadWorld(cloud(github, folder), cloud(github, folder).newest(), mc.gui.screen(), f -> {}));
        context.waitForScreen(ChoiceScreen.class);
        context.clickScreenButton("Replace my copy");
        waitForTransfer(context);
        check(Files.exists(worldDir.resolve("data/from-pc.dat")) && marker(worldDir).stamp().equals(pcStamp),
                "replacing an in-step copy applied the PC's changes");
        check(lastResultMentions("only the changes") && !hasBackup(folder), "...downloading only the changes, with no backup needed");

        // 4. Opening the world when the PC saved newer: download it and play, then leaving uploads only changes.
        String pcStamp2 = uploadChangesAsPc(github, folder, worldDir, "data/from-pc-2.dat");
        context.runOnClient(mc -> SyncGate.interceptOpen(folder, new SelectWorldScreen(new TitleScreen())));
        context.waitForScreen(ChoiceScreen.class);
        context.takeScreenshot("changes-newer-on-open");
        context.clickScreenButton("Download it and play");
        context.waitFor(mc -> mc.level != null, 20 * 120);
        check(Files.exists(worldDir.resolve("data/from-pc-2.dat")), "opening fetched the PC's newer changes first");
        check(cloud(github, folder).lock() != null, "the world is marked as open on this computer");
        context.waitTicks(40);
        context.runOnClient(mc -> mc.disconnectWithSavingScreen());
        context.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null, 20 * 60);
        context.waitTicks(10);
        waitForTransfer(context);
        context.setScreen(TitleScreen::new);
        CloudWorld afterPlay = cloud(github, folder);
        check(afterPlay.newest().device().equals("MacBook") && !afterPlay.newest().isFull()
                        && afterPlay.newest().parent().equals(pcStamp2) && afterPlay.lock() == null,
                "leaving the world auto-uploaded only the changes and released the lock: device=" + afterPlay.newest().device()
                        + " full=" + afterPlay.newest().isFull() + " parent=" + afterPlay.newest().parent() + " (expected " + pcStamp2
                        + ") lock=" + afterPlay.lock() + " size=" + afterPlay.newest().size() + " result=" + lastResult);

        // 5. History cleanup keeps everything the kept versions build on.
        context.runOnClient(mc -> SaveLogic.CONFIG.keepVersions = 1);
        write(worldDir.resolve("data/savelogic-test-2.dat"), 500);
        upload(context, folder);
        CloudWorld trimmed = cloud(github, folder);
        GitHubStorageProvider.Chain chain = GitHubStorageProvider.chain(trimmed, trimmed.newest(), null);
        check(chain.versions().get(0).isFull(), "with keep=1 the full copy the latest version builds on is still there ("
                + trimmed.versions().size() + " versions kept for a chain of " + chain.versions().size() + ")");
        context.runOnClient(mc -> SaveLogic.CONFIG.keepVersions = 5);

        // 6. An unsynced copy that has a cloud world's name gets asked which copy to use.
        delete(worldDir.resolve(WorldSyncMarker.FILE));
        SyncGate.rememberCloudWorlds(github.listWorlds().join()); // As opening the Load menu would.
        context.runOnClient(mc -> SyncGate.interceptOpen(folder, new SelectWorldScreen(new TitleScreen())));
        context.waitForScreen(ChoiceScreen.class);
        context.takeScreenshot("changes-link-by-name");
        context.clickScreenButton("Cancel");
        context.waitForScreen(SelectWorldScreen.class);
        boolean[] intercepted = new boolean[1];
        context.runOnClient(mc -> intercepted[0] = SyncGate.interceptOpen("no such cloud world", new SelectWorldScreen(new TitleScreen())));
        check(!intercepted[0], "a local-only world opens straight away (cloud names were remembered)");
        context.setScreen(TitleScreen::new);
        context.waitTicks(5);

        // 7. Choosing which worlds sync: new worlds stay local, Stop Syncing keeps a world local, Sync links one.
        int cloudWorlds = github.listWorlds().join().size();
        Path testWorld;
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            testWorld = world.getWorldSave().getSaveDirectory();
        }
        context.waitForScreen(TitleScreen.class);
        context.waitTicks(10);
        waitForTransfer(context);
        String testFolder = testWorld.getFileName().toString();
        check(github.listWorlds().join().size() == cloudWorlds, "leaving a new world doesn't upload it");
        check(!local(context, testFolder).synced() && !local(context, folder).synced(), "new and kept-local worlds are listed as this computer only");
        check(local(context, folder + " (2)").synced() && local(context, folder + " (2)").cloudName().equals(folder),
                "a downloaded copy is listed as synced with its cloud world");
        context.setScreen(() -> new SyncedWorldsScreen(new TitleScreen()));
        context.waitTicks(5);
        context.takeScreenshot("synced-worlds");

        context.runOnClient(mc -> SyncedWorldsScreen.stopSyncing(folder + " (2)"));
        check(!local(context, folder + " (2)").synced(), "Stop Syncing marks the world as this computer only");
        context.runOnClient(mc -> intercepted[0] = SyncGate.interceptOpen(folder + " (2)", new SelectWorldScreen(new TitleScreen())));
        check(!intercepted[0], "a world that stopped syncing opens without checking the cloud");

        context.runOnClient(mc -> CloudSync.uploadWorld(testFolder, false, mc.gui.screen())); // What its Sync button does.
        waitForTransfer(context);
        check(local(context, testFolder).synced() && cloud(github, testFolder) != null, "Sync uploads the world and from then on it syncs");
        context.setScreen(TitleScreen::new);
        context.waitTicks(5);
    }

    private static SyncedWorldsScreen.LocalWorld local(ClientGameTestContext context, String folder) {
        return context.computeOnClient(mc -> SyncedWorldsScreen.localWorlds()).stream().filter(w -> w.folder().equals(folder)).findFirst()
                .orElseThrow(() -> new AssertionError("world not listed: " + folder));
    }

    // --- Helpers ---

    private static void upload(ClientGameTestContext context, String folder) {
        context.runOnClient(mc -> CloudSync.uploadWorld(folder, false, mc.gui.screen()));
        waitForTransfer(context);
    }

    private static String lastResult;

    private static boolean lastResultMentions(String text) {
        return lastResult != null && lastResult.contains(text);
    }

    /** Simulates the other computer: copies this world, adds a file, and uploads just that change. */
    private static String uploadChangesAsPc(GitHubStorageProvider github, String name, Path worldDir, String newFile) {
        try {
            Thread.sleep(5);
            Path pcCopy = Files.createTempDirectory("pc-copy");
            copyTree(worldDir, pcCopy);
            write(pcCopy.resolve(newFile), 2000);
            WorldManifest base = WorldSyncMarker.read(worldDir).orElseThrow().files();
            WorldManifest now = WorldManifest.scan(pcCopy, base, Progress.NONE);
            Path zip = Files.createTempFile("pc-changes", ".zip");
            String parent = github.findWorld(name).join().orElseThrow().newest().stamp();
            WorldPackage.writeChanges(pcCopy, zip, now, now.changesSince(base), parent, Progress.NONE);
            String stamp = github.uploadWorld(name, zip, parent, System.currentTimeMillis(), PC, 5, Progress.NONE).join();
            SetupInstaller.deleteRecursively(pcCopy);
            return stamp;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static boolean sameFiles(Path a, Path b) {
        try {
            WorldManifest ma = WorldManifest.scan(a, null, Progress.NONE);
            WorldManifest mb = WorldManifest.scan(b, null, Progress.NONE);
            return ma.changesSince(mb).isEmpty() && mb.changesSince(ma).isEmpty();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static CloudWorld cloud(GitHubStorageProvider github, String name) {
        return github.findWorld(name).join().orElseThrow(() -> new AssertionError("World not in cloud: " + name));
    }

    private static WorldSyncMarker marker(Path worldDir) {
        return WorldSyncMarker.read(worldDir).orElseThrow(() -> new AssertionError("No sync marker in " + worldDir));
    }

    private static boolean hasBackup(String folder) {
        Path backups = FabricLoader.getInstance().getGameDir().resolve("savelogic-backups").resolve("worlds");
        try (Stream<Path> list = Files.list(backups)) {
            return list.anyMatch(p -> p.getFileName().toString().startsWith(folder + "_"));
        } catch (Exception e) {
            return false;
        }
    }

    private static void write(Path file, int bytes) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, new byte[bytes]);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void copyTree(Path from, Path to) throws Exception {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path path : walk.sorted(Comparator.naturalOrder()).toList()) {
                Path target = to.resolve(from.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target);
                }
            }
        }
    }

    private static void waitForTransfer(ClientGameTestContext context) {
        context.waitTicks(2);
        context.waitFor(mc -> !TransferStatus.INSTANCE.isActive(), 20 * 120);
        lastResult = TransferStatus.INSTANCE.lastResult();
        context.waitTicks(2);
    }

    private static void check(boolean ok, String what) {
        System.out.println("[ChangesSyncTest] " + (ok ? "PASS " : "FAIL ") + what);
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
