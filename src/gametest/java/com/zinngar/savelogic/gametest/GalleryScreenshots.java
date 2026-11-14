package com.zinngar.savelogic.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import com.zinngar.savelogic.GitHubStorageProvider;
import com.zinngar.savelogic.GitHubStorageProvider.CloudWorld;
import com.zinngar.savelogic.GitHubStorageProvider.Device;
import com.zinngar.savelogic.SaveLogic;
import com.zinngar.savelogic.client.CloudSync;
import com.zinngar.savelogic.client.SelfUpdate;
import com.zinngar.savelogic.client.SetupSync;
import com.zinngar.savelogic.client.SyncGate;
import com.zinngar.savelogic.client.TransferStatus;
import com.zinngar.savelogic.client.screen.ChoiceScreen;
import com.zinngar.savelogic.client.screen.CloudSavesScreen;
import com.zinngar.savelogic.client.screen.ModUpdatesScreen;
import com.zinngar.savelogic.client.screen.SyncedWorldsScreen;
import com.zinngar.savelogic.client.screen.VersionsScreen;
import com.zinngar.savelogic.gametest.FakeModrinth.FakeVersion;
import com.zinngar.savelogic.util.Progress;
import com.zinngar.savelogic.util.ZipUtil;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Takes the screenshots for the Modrinth gallery: a configured game with demo worlds on two computers and no test
 * leftovers. Clears the test game's worlds and mods first, so it only runs when asked for with -Dsavelogic.gallery=true.
 */
public class GalleryScreenshots implements FabricClientGameTest {

    private static final Device MAC = new Device("mac-id", "MacBook");
    private static final Device PC = new Device("pc-id", "Gaming PC");

    @Override
    public void runTest(ClientGameTestContext context) {
        Path gameDir = FabricLoader.getInstance().getGameDir();
        if (!Boolean.getBoolean("savelogic.gallery") || !gameDir.toString().contains("clientGameTest")) {
            return;
        }
        HttpServer releases = null;
        try (FakeGitHub github = new FakeGitHub(); FakeModrinth modrinth = new FakeModrinth()) {
            releases = releaseFeed();
            System.setProperty("savelogic.testApiBase", github.baseUrl());
            System.setProperty("savelogic.testModrinthBase", modrinth.baseUrl());
            System.setProperty("savelogic.testReleasesBase", "http://127.0.0.1:" + releases.getAddress().getPort());
            System.setProperty("savelogic.testSelfUpdate", "true");
            run(context, gameDir, modrinth);
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            if (releases != null) {
                releases.stop(0);
            }
            System.clearProperty("savelogic.testApiBase");
            System.clearProperty("savelogic.testModrinthBase");
            System.clearProperty("savelogic.testReleasesBase");
            System.clearProperty("savelogic.testSelfUpdate");
        }
    }

    private void run(ClientGameTestContext context, Path gameDir, FakeModrinth modrinth) throws Exception {
        context.runOnClient(mc -> {
            SaveLogic.CONFIG.github.repositoryUrl = "o/r";
            SaveLogic.CONFIG.github.personalAccessToken = "good-token";
            SaveLogic.CONFIG.deviceId = MAC.id();
            SaveLogic.CONFIG.deviceName = MAC.name();
            SaveLogic.CONFIG.autoUpload = true;
            SaveLogic.CONFIG.keepVersions = 5;
            SaveLogic.CONFIG.schematicsAutoSync = false;
            mc.options.guiScale().set(2);
            mc.resizeGui();
        });
        SetupSync.cancelPendingInstall();
        SelfUpdate.cancelPending();
        Path saves = gameDir.resolve("saves");
        deleteTree(saves);
        deleteTree(gameDir.resolve("xaero"));
        context.waitTicks(40);

        // Demo worlds: two synced, one local. Copies of one world are enough, as only their folder names show.
        Path made;
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            made = world.getWorldSave().getSaveDirectory();
        }
        context.waitForScreen(TitleScreen.class);
        context.waitTicks(10);
        waitForTransfer(context);
        copyTree(made, saves.resolve("Survival World"));
        // A world of a believable size, last played a few days ago.
        byte[] region = new byte[24 * 1024 * 1024];
        new java.util.Random(1).nextBytes(region);
        Files.createDirectories(saves.resolve("Survival World/region"));
        Files.write(saves.resolve("Survival World/region/r.9.9.mca"), region);
        Files.setLastModifiedTime(saves.resolve("Survival World/level.dat"), java.nio.file.attribute.FileTime.fromMillis(hoursAgo(75)));
        copyTree(made, saves.resolve("Creative Build"));
        copyTree(made, saves.resolve("Test World"));
        deleteTree(made);

        GitHubStorageProvider github = new GitHubStorageProvider("o/r", "good-token");
        upload(context, "Creative Build");
        upload(context, "Survival World");
        Path survival = saves.resolve("Survival World");
        List<Device> computers = List.of(PC, MAC, PC);
        List<Long> played = List.of(hoursAgo(52), hoursAgo(26), hoursAgo(2));
        for (int i = 0; i < computers.size(); i++) {
            Thread.sleep(1100); // Distinct version stamps.
            Path zip = Files.createTempFile("gallery", ".zip");
            ZipUtil.zipWorld(survival, zip);
            github.uploadWorld("Survival World", zip, null, played.get(i), computers.get(i), 5, Progress.NONE).join();
            Files.delete(zip);
        }
        context.runOnClient(mc -> SaveLogic.CONFIG.schematicsAutoSync = true);
        context.setScreen(TitleScreen::new);
        context.waitTicks(20 * 12); // Let the last transfer's result message fade.

        shot(context, "1-cloud-button");

        context.setScreen(() -> new CloudSavesScreen(new TitleScreen()));
        context.waitTicks(5);
        shot(context, "2-cloud-menu");

        context.setScreen(() -> new SyncedWorldsScreen(new TitleScreen()));
        context.waitTicks(5);
        shot(context, "3-synced-worlds");

        TransferStatus status = TransferStatus.INSTANCE;
        status.begin("Saving \"Survival World\" to cloud");
        status.phase("Uploading part 2 of 3", 1024L * 1024 * 1024);
        context.waitTicks(20 * 20); // A believable speed.
        status.bytes(410L * 1024 * 1024);
        context.setScreen(() -> new CloudSavesScreen(new TitleScreen()));
        context.waitTicks(5);
        shot(context, "4-progress-bar");
        status.cancel();

        // This copy was last synced before the PC's newest version.
        SyncGate.rememberCloudWorlds(github.listWorlds().join());
        context.runOnClient(mc -> SyncGate.interceptOpen("Survival World", new SelectWorldScreen(new TitleScreen())));
        context.waitForScreen(ChoiceScreen.class);
        context.waitTicks(5);
        shot(context, "5-newer-version");

        CloudWorld world = github.findWorld("Survival World").join().orElseThrow();
        context.setScreen(() -> new VersionsScreen(new TitleScreen(), world));
        context.waitTicks(5);
        shot(context, "6-version-history");

        modUpdatesFixture(gameDir, modrinth);
        context.setScreen(() -> new ModUpdatesScreen(new TitleScreen()));
        context.waitTicks(60);
        shot(context, "7-mod-updates");

        context.setScreen(TitleScreen::new);
        context.waitTicks(5);
    }

    /** Illustrative mods: an update needing a library, an up-to-date mod, one with no build yet and one not on Modrinth. */
    private static void modUpdatesFixture(Path gameDir, FakeModrinth modrinth) throws Exception {
        String game = SetupSync.minecraftVersion();
        modrinth.project("map", "Example Minimap");
        modrinth.project("speed", "Example Performance Mod");
        modrinth.project("build", "Example Building Mod");
        modrinth.project("lib", "Example Library");
        modrinth.version(new FakeVersion("m1", "map", "4.1", "2026-01-01T00:00:00Z", "example-minimap-4.1.jar", bytes("map 4.1"), List.of(), game));
        modrinth.version(new FakeVersion("m2", "map", "4.2", "2026-02-01T00:00:00Z", "example-minimap-4.2.jar", bytes("map 4.2"), List.of("lib"), game));
        modrinth.version(new FakeVersion("s1", "speed", "2.0", "2026-01-01T00:00:00Z", "example-performance-2.0.jar", bytes("speed 2.0"), List.of(), game));
        modrinth.version(new FakeVersion("b1", "build", "1.3", "2026-01-01T00:00:00Z", "example-building-1.3.jar", bytes("build 1.3"), List.of(), "0.0.1"));
        modrinth.version(new FakeVersion("l1", "lib", "3.1", "2026-01-15T00:00:00Z", "example-library-3.1.jar", bytes("lib 3.1"), List.of(), game));
        Path mods = gameDir.resolve("mods");
        deleteTree(mods);
        Files.createDirectories(mods);
        Files.write(mods.resolve("example-minimap-4.1.jar"), bytes("map 4.1"));
        Files.write(mods.resolve("example-performance-2.0.jar"), bytes("speed 2.0"));
        Files.write(mods.resolve("example-building-1.3.jar"), bytes("build 1.3"));
        Files.write(mods.resolve("my-own-mod.jar"), bytes("not on modrinth"));
    }

    /** A release list with one newer SaveLogic. Nothing is downloaded, so the checksum doesn't have to be real. */
    private static HttpServer releaseFeed() throws Exception {
        String version = "2.0.0-beta.13+" + SetupSync.minecraftVersion();
        JsonObject asset = new JsonObject();
        asset.addProperty("name", "savelogic-" + version + ".jar");
        asset.addProperty("size", 200000);
        asset.addProperty("browser_download_url", "http://127.0.0.1/none");
        asset.addProperty("digest", "sha256:" + "00".repeat(32));
        JsonArray assets = new JsonArray();
        assets.add(asset);
        JsonObject release = new JsonObject();
        release.addProperty("tag_name", "v" + version);
        release.addProperty("prerelease", true);
        release.add("assets", assets);
        JsonArray list = new JsonArray();
        list.add(release);
        byte[] body = list.toString().getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static void shot(ClientGameTestContext context, String name) {
        context.getInput().setCursorPos(1, 1); // No button highlighted by the mouse.
        context.waitTicks(2);
        context.takeScreenshot("gallery-" + name);
    }

    private static void upload(ClientGameTestContext context, String folder) {
        context.runOnClient(mc -> CloudSync.uploadWorld(folder, false, mc.gui.screen()));
        waitForTransfer(context);
    }

    private static void waitForTransfer(ClientGameTestContext context) {
        context.waitTicks(2);
        context.waitFor(mc -> !TransferStatus.INSTANCE.isActive(), 20 * 120);
        context.waitTicks(2);
    }

    private static long hoursAgo(int hours) {
        return System.currentTimeMillis() - hours * 3600_000L;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static void copyTree(Path from, Path to) throws Exception {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path path : walk.toList()) {
                Path target = to.resolve(from.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else if (!path.getFileName().toString().equals("session.lock")) {
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
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
}
