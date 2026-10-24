package com.zinngar.savelogic.gametest;

import com.zinngar.savelogic.GitHubStorageProvider;
import com.zinngar.savelogic.GitHubStorageProvider.Device;
import com.zinngar.savelogic.SaveLogic;
import com.zinngar.savelogic.client.SchematicSync;
import com.zinngar.savelogic.schematics.SchematicSyncer;
import com.zinngar.savelogic.schematics.SchematicSyncer.Summary;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Two-way schematics sync between two computers, against an in-memory {@link FakeGitHub}: new and changed files
 * travel both ways, deletions follow (local ones backed up first), a file changed on both computers ends up as
 * both copies on both, and the menu's Sync Schematics path syncs the game's own schematics folder.
 */
public class SchematicsSyncTest implements FabricClientGameTest {

    private static final Device MAC = new Device("mac-id", "MacBook");
    private static final Device PC = new Device("pc-id", "Gaming PC");

    @Override
    public void runTest(ClientGameTestContext context) {
        try (FakeGitHub fake = new FakeGitHub()) {
            System.setProperty("savelogic.testApiBase", fake.baseUrl());
            run(context);
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            System.clearProperty("savelogic.testApiBase");
        }
    }

    private void run(ClientGameTestContext context) throws Exception {
        context.runOnClient(mc -> {
            SaveLogic.CONFIG.github.repositoryUrl = "o/r";
            SaveLogic.CONFIG.github.personalAccessToken = "good-token";
            SaveLogic.CONFIG.deviceId = MAC.id();
            SaveLogic.CONFIG.deviceName = MAC.name();
            SaveLogic.CONFIG.schematicsAutoSync = false; // Only the syncs below.
        });
        GitHubStorageProvider github = new GitHubStorageProvider("o/r", "good-token");

        Path gameDir = FabricLoader.getInstance().getGameDir();
        Path mac = gameDir.resolve("schematics");
        Path macBackups = gameDir.resolve("savelogic-backups").resolve("schematics");
        deleteTree(mac);
        deleteTree(macBackups);
        deleteTree(gameDir.resolve(".savelogic"));
        Path pcRoot = Files.createTempDirectory("savelogic-pc");
        Path pc = pcRoot.resolve("schematics");
        SchematicSyncer macSync = new SchematicSyncer(github, mac, gameDir.resolve(".savelogic").resolve("schematics-state.json"), macBackups, MAC);
        SchematicSyncer pcSync = new SchematicSyncer(github, pc, pcRoot.resolve("state.json"), pcRoot.resolve("backups"), PC);

        // New files go up; other files in the folder are left alone.
        write(mac.resolve("house.litematic"), "house v1");
        write(mac.resolve("builds/tower.schem"), "tower v1");
        write(mac.resolve("readme.txt"), "not a schematic");
        expect(macSync.sync(), 2, 0, 0, 0, "first sync uploads both schematics");
        check(github.listSchematics().files().size() == 2, "the cloud holds 2 schematics (readme.txt skipped)");

        expect(pcSync.sync(), 0, 2, 0, 0, "the other computer downloads both");
        check(read(pc.resolve("builds/tower.schem")).equals("tower v1"), "folders inside schematics are kept");

        // Changes made on one computer reach the other.
        write(pc.resolve("house.litematic"), "house v2 from pc");
        write(pc.resolve("bridge.litematic"), "bridge");
        expect(pcSync.sync(), 2, 0, 0, 0, "a changed and a new file upload");
        check(github.listSchematics().files().size() == 3, "the changed file replaced its old cloud copy");
        expect(macSync.sync(), 0, 2, 0, 0, "and download on the first computer");
        check(read(mac.resolve("house.litematic")).equals("house v2 from pc"), "the first computer has the change");

        // Deletions follow, and a schematic deleted locally is backed up, not lost.
        Files.delete(pc.resolve("builds/tower.schem"));
        expect(pcSync.sync(), 0, 0, 1, 0, "a deleted file is removed from the cloud");
        expect(macSync.sync(), 0, 0, 1, 0, "and from the first computer");
        check(!Files.exists(mac.resolve("builds/tower.schem")), "tower.schem is gone");
        check(backupContains(macBackups, "builds/tower.schem", "tower v1"), "tower.schem was moved to savelogic-backups");

        // Changed on both computers before syncing: both computers end up with both copies.
        write(mac.resolve("house.litematic"), "house v3 from mac");
        write(pc.resolve("house.litematic"), "house v3 from pc");
        expect(macSync.sync(), 1, 0, 0, 0, "the first to sync uploads its copy");
        expect(pcSync.sync(), 2, 0, 0, 1, "the second keeps both");
        check(read(pc.resolve("house.litematic")).equals("house v3 from pc"), "the second computer keeps its own copy");
        check(read(pc.resolve("house (MacBook).litematic")).equals("house v3 from mac"),
                "and gets the other one as \"house (MacBook).litematic\"");
        expect(macSync.sync(), 0, 2, 0, 0, "the first computer then gets both");
        check(read(mac.resolve("house.litematic")).equals("house v3 from pc")
                && read(mac.resolve("house (MacBook).litematic")).equals("house v3 from mac"), "both computers have both copies");

        expect(pcSync.sync(), 0, 0, 0, 0, "nothing to do when both are in step");
        check(github.listSchematics().files().size() == 3, "the cloud holds house, its kept copy and bridge");

        // The menu's button syncs the game's own folder (the first computer here).
        write(mac.resolve("castle.litematic"), "castle");
        AtomicReference<Summary> done = new AtomicReference<>();
        context.runOnClient(mc -> SchematicSync.sync(true, done::set));
        context.waitFor(mc -> done.get() != null, 20 * 60);
        expect(done.get(), 1, 0, 0, 0, "Sync Schematics uploads the new file");
        check(!SchematicSync.isRunning(), "the sync has finished");
        expect(pcSync.sync(), 0, 1, 0, 0, "and the other computer gets it");

        context.runOnClient(mc -> SaveLogic.CONFIG.schematicsAutoSync = true);
        deleteTree(pcRoot);
    }

    private static void expect(Summary s, int up, int down, int deleted, int keptBoth, String what) {
        check(s.uploaded() == up && s.downloaded() == down && s.deleted() == deleted && s.keptBoth() == keptBoth,
                what + " (" + s.describe() + ")");
    }

    private static boolean backupContains(Path backups, String path, String content) throws Exception {
        if (!Files.isDirectory(backups)) {
            return false;
        }
        try (Stream<Path> stamps = Files.list(backups)) {
            return stamps.map(stamp -> stamp.resolve(path)).anyMatch(file -> {
                try {
                    return Files.isRegularFile(file) && read(file).equals(content);
                } catch (Exception e) {
                    return false;
                }
            });
        }
    }

    private static void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static String read(Path file) throws Exception {
        return Files.isRegularFile(file) ? Files.readString(file) : "<missing>";
    }

    private static void deleteTree(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    private static void check(boolean ok, String what) {
        System.out.println("[SchematicsSyncTest] " + (ok ? "PASS " : "FAIL ") + what);
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
