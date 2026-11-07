package com.zinngar.savelogic.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import com.zinngar.savelogic.SaveLogic;
import com.zinngar.savelogic.client.SelfUpdate;
import com.zinngar.savelogic.client.SetupSync;
import com.zinngar.savelogic.client.TransferStatus;
import com.zinngar.savelogic.client.screen.CloudSavesScreen;
import com.zinngar.savelogic.client.screen.ModUpdatesScreen;
import com.zinngar.savelogic.setup.SetupInstaller;
import com.zinngar.savelogic.update.SelfUpdater;
import com.zinngar.savelogic.update.SelfUpdater.Release;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.TitleScreen;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * SaveLogic updating itself from a fake GitHub release list: which release is offered, which downloads are refused,
 * swapping the jar (and putting the old one back if that fails), and the Mod Updates row and daily notice.
 */
public class SelfUpdateTest implements FabricClientGameTest {

    private static final String BETA_9 = "2.0.0-beta.9+26.3";

    private final Map<String, byte[]> files = new HashMap<>();
    private final AtomicInteger listRequests = new AtomicInteger();
    private JsonArray releases = new JsonArray();
    private String base;

    @Override
    public void runTest(ClientGameTestContext context) {
        HttpServer server = null;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                byte[] body;
                synchronized (this) {
                    if (path.endsWith("/releases")) {
                        listRequests.incrementAndGet();
                        body = releases.toString().getBytes(StandardCharsets.UTF_8);
                    } else {
                        body = files.get(path);
                    }
                }
                if (body == null) {
                    exchange.sendResponseHeaders(404, -1);
                } else {
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
                exchange.close();
            });
            server.start();
            base = "http://127.0.0.1:" + server.getAddress().getPort();
            System.setProperty("savelogic.testReleasesBase", base);
            versions();
            choosing();
            downloading();
            installing();
            inGame(context);
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            if (server != null) {
                server.stop(0);
            }
            System.clearProperty("savelogic.testReleasesBase");
            System.clearProperty("savelogic.testSelfUpdate");
            System.clearProperty("savelogic.testModrinthBase");
        }
    }

    private void versions() {
        check(SelfUpdater.compare("2.0.0-beta.9+26.3", "2.0.0-beta.10+26.3") < 0, "beta 10 is newer than beta 9 (not sorted as text)");
        check(SelfUpdater.compare("2.0.0-beta.11+26.3", "2.0.0+26.3") < 0, "a release is newer than its betas");
        check(SelfUpdater.compare("2.0.0-beta.11+26.3", "2.0.0-beta.11+1.21.11") == 0, "the Minecraft version isn't part of the order");
        check(SelfUpdater.compare("2.1.0-beta.1+26.3", "2.0.0+26.3") > 0, "a higher version number wins over beta status");
    }

    private void choosing() throws Exception {
        release("2.0.0-beta.10+26.3", true, false, true, jar("savelogic", "2.0.0-beta.10+26.3"));
        release("2.0.0-beta.12+26.3", true, false, true, jar("savelogic", "2.0.0-beta.12+26.3"));
        release("2.0.0-beta.11+26.3", true, false, true, jar("savelogic", "2.0.0-beta.11+26.3"));
        release("2.0.0-beta.99+1.21.11", true, false, true, jar("savelogic", "2.0.0-beta.99+1.21.11"));
        release("2.0.0-beta.50+26.3", true, true, true, jar("savelogic", "2.0.0-beta.50+26.3"));
        release("2.0.0-beta.60+26.3", true, false, false, jar("savelogic", "2.0.0-beta.60+26.3"));
        SelfUpdater updater = new SelfUpdater(BETA_9);
        Release best = updater.newest(BETA_9, "26.3").orElseThrow(() -> new AssertionError("nothing offered"));
        check(best.version().equals("2.0.0-beta.12+26.3"), "offers the newest for this Minecraft, skipping the other version's, drafts and jars with no checksum: " + best.version());
        check(updater.newest("2.0.0-beta.12+26.3", "26.3").isEmpty(), "nothing is offered to the newest version");
        check(updater.newest("2.0.0+26.3", "26.3").isEmpty(), "a stable version isn't offered betas");
        check(updater.newest(BETA_9, "1.21.11").map(Release::version).orElse("").equals("2.0.0-beta.99+1.21.11"), "1.21.11 gets the 1.21.11 jar");
    }

    private void downloading() throws Exception {
        SelfUpdater updater = new SelfUpdater(BETA_9);
        Release good = updater.newest(BETA_9, "26.3").orElseThrow();
        Path game = Files.createTempDirectory("savelogic-update");
        Path pending = game.resolve(SetupInstaller.PENDING_UPDATE_DIR);

        updater.stage(good, game, com.zinngar.savelogic.util.Progress.NONE);
        check(Files.isRegularFile(pending.resolve(good.fileName())), "a good download waits in the pending folder");

        Release tampered = new Release(good.version(), good.fileName(), good.url(), good.size(), "00".repeat(32));
        check(refused(updater, tampered, game, "checksum"), "a download with the wrong checksum is refused");
        check(!Files.exists(pending), "...and nothing is left waiting");

        byte[] impostor = jar("some-other-mod", good.version());
        files.put("/download/impostor.jar", impostor);
        Release other = new Release(good.version(), good.fileName(), base + "/download/impostor.jar", impostor.length, sha256(impostor));
        check(refused(updater, other, game, "isn't SaveLogic"), "a jar that isn't SaveLogic is refused");

        byte[] wrongVersion = jar("savelogic", "2.0.0-beta.1+26.3");
        files.put("/download/old.jar", wrongVersion);
        Release old = new Release(good.version(), good.fileName(), base + "/download/old.jar", wrongVersion.length, sha256(wrongVersion));
        check(refused(updater, old, game, "isn't SaveLogic"), "a jar of a different version than offered is refused");

        files.put("/download/junk.jar", "not a zip".getBytes(StandardCharsets.UTF_8));
        Release junk = new Release(good.version(), good.fileName(), base + "/download/junk.jar", 9, sha256("not a zip".getBytes(StandardCharsets.UTF_8)));
        check(refused(updater, junk, game, ""), "a file that isn't a jar is refused");
        check(!Files.exists(pending), "...and nothing is left waiting");
        SetupInstaller.deleteRecursively(game);
    }

    private void installing() throws Exception {
        PrintStream log = new PrintStream(new ByteArrayOutputStream());
        byte[] newJar = jar("savelogic", "2.0.0-beta.12+26.3");

        // The normal swap: only SaveLogic changes.
        Path game = gameWithUpdate(newJar);
        Path backup = SetupInstaller.installUpdate(game, Set.of("savelogic-old.jar"), log);
        Path mods = game.resolve("mods");
        check(Files.isRegularFile(mods.resolve("savelogic-new.jar")) && !Files.exists(mods.resolve("savelogic-old.jar")), "the new jar replaces the old one");
        check(Files.isRegularFile(backup.resolve("savelogic-old.jar")), "the old jar is kept in savelogic-backups");
        check(Files.readString(mods.resolve("other-mod.jar")).equals("other"), "other mods are left alone");
        try (Stream<Path> list = Files.list(mods)) {
            check(list.count() == 2, "there is exactly one SaveLogic in the mods folder");
        }
        check(!Files.exists(game.resolve(SetupInstaller.PENDING_UPDATE_DIR)), "nothing is left waiting");
        check(SetupInstaller.installUpdate(game, Set.of("savelogic-new.jar"), log) == null, "with nothing waiting, nothing happens");
        SetupInstaller.deleteRecursively(game);

        // If the current jar can't be found, adding the new one could leave two copies of SaveLogic.
        game = gameWithUpdate(newJar);
        check(SetupInstaller.installUpdate(game, Set.of("renamed.jar"), log) == null
                && !Files.exists(game.resolve("mods/savelogic-new.jar")), "without the current jar to replace, nothing is installed");
        SetupInstaller.deleteRecursively(game);

        // A failure after the old jar was moved puts it back. A folder in the way makes the last step fail.
        game = gameWithUpdate(newJar);
        Files.createDirectories(game.resolve("mods/savelogic-new.jar/blocker"));
        boolean failed = false;
        try {
            SetupInstaller.installUpdate(game, Set.of("savelogic-old.jar"), log);
        } catch (java.io.IOException e) {
            failed = true;
        }
        check(failed && Files.isRegularFile(game.resolve("mods/savelogic-old.jar")) && !Files.exists(game.resolve("mods/savelogic-new.jar.part")),
                "a failed swap puts the old jar back");
        check(Files.isRegularFile(game.resolve(SetupInstaller.PENDING_UPDATE_DIR).resolve("savelogic-new.jar")), "...and the update is tried again next time");
        SetupInstaller.deleteRecursively(game);
    }

    private void inGame(ClientGameTestContext context) throws Exception {
        try (FakeModrinth modrinth = new FakeModrinth()) {
            System.setProperty("savelogic.testModrinthBase", modrinth.baseUrl());
            System.setProperty("savelogic.testSelfUpdate", "true");
            String game = SetupSync.minecraftVersion();
            String version = "2.0.0-beta.999+" + game;
            synchronized (this) {
                releases = new JsonArray();
            }
            release(version, true, false, true, jar("savelogic", version));
            SelfUpdate.cancelPending();
            context.runOnClient(mc -> {
                SaveLogic.CONFIG.selfUpdateCheck = true;
                SaveLogic.CONFIG.lastSelfUpdateCheck = 0;
                SaveLogic.CONFIG.selfUpdateVersion = "";
            });

            // The daily check: it says so, doesn't download, and doesn't ask again the same day.
            int before = listRequests.get();
            context.runOnClient(mc -> SelfUpdate.checkInBackground());
            context.waitFor(mc -> !SaveLogic.CONFIG.selfUpdateVersion.isEmpty(), 20 * 30);
            context.waitTicks(10);
            context.takeScreenshot("self-update-notice");
            check(SaveLogic.CONFIG.selfUpdateVersion.equals(version), "the daily check remembers the new version");
            check(SelfUpdate.pendingFileName().isEmpty(), "the daily check downloads nothing");
            context.runOnClient(mc -> SelfUpdate.checkInBackground());
            context.waitTicks(20);
            check(listRequests.get() == before + 1, "it doesn't ask GitHub again the same day");
            context.runOnClient(mc -> {
                SaveLogic.CONFIG.selfUpdateCheck = false;
                SaveLogic.CONFIG.lastSelfUpdateCheck = 0;
                SelfUpdate.checkInBackground();
            });
            context.waitTicks(20);
            check(listRequests.get() == before + 1, "turning it off in Settings stops the check");
            context.runOnClient(mc -> SaveLogic.CONFIG.selfUpdateCheck = true);

            // Installing takes a click in Mod Updates.
            context.setScreen(() -> new ModUpdatesScreen(new TitleScreen()));
            context.waitFor(mc -> listRequests.get() > before + 1, 20 * 30);
            context.waitTicks(20);
            context.takeScreenshot("self-update-row");
            context.clickScreenButton("Update SaveLogic");
            context.waitForScreen(ConfirmScreen.class);
            context.waitTicks(5);
            context.takeScreenshot("self-update-downloaded");
            check(SelfUpdate.pendingFileName().isPresent(), "Update downloads it and leaves it waiting for Minecraft to close");
            check(!TransferStatus.INSTANCE.isActive(), "the download has finished");
            context.clickScreenButton("Later");
            context.waitTicks(5);

            context.setScreen(() -> new CloudSavesScreen(new TitleScreen()));
            context.waitTicks(5);
            context.takeScreenshot("self-update-pending");

            // The row now offers to cancel, and cancelling removes it.
            context.setScreen(() -> new ModUpdatesScreen(new TitleScreen()));
            context.waitTicks(40);
            context.takeScreenshot("self-update-waiting-row");
            context.clickScreenButton("SaveLogic update waiting: click to cancel");
            context.waitTicks(5);
            check(SelfUpdate.pendingFileName().isEmpty(), "the waiting update can be cancelled");
            context.runOnClient(mc -> {
                SaveLogic.CONFIG.lastSelfUpdateCheck = 0;
                SaveLogic.CONFIG.selfUpdateVersion = "";
            });
            context.setScreen(TitleScreen::new);
            context.waitTicks(5);
        }
    }

    private Path gameWithUpdate(byte[] newJar) throws Exception {
        Path game = Files.createTempDirectory("savelogic-update");
        Files.createDirectories(game.resolve("mods"));
        Files.writeString(game.resolve("mods/savelogic-old.jar"), "old");
        Files.writeString(game.resolve("mods/other-mod.jar"), "other");
        Path pending = game.resolve(SetupInstaller.PENDING_UPDATE_DIR);
        Files.createDirectories(pending);
        Files.write(pending.resolve("savelogic-new.jar"), newJar);
        return game;
    }

    private static boolean refused(SelfUpdater updater, Release release, Path game, String message) throws Exception {
        try {
            updater.stage(release, game, com.zinngar.savelogic.util.Progress.NONE);
            return false;
        } catch (java.io.IOException e) {
            return String.valueOf(e.getMessage()).contains(message) && !Files.exists(game.resolve(SetupInstaller.PENDING_UPDATE_DIR));
        }
    }

    private synchronized void release(String version, boolean prerelease, boolean draft, boolean withDigest, byte[] jar) throws Exception {
        String name = "savelogic-" + version + ".jar";
        String path = "/download/" + version + "/" + name;
        files.put(path, jar);
        JsonObject asset = new JsonObject();
        asset.addProperty("name", name);
        asset.addProperty("size", jar.length);
        asset.addProperty("browser_download_url", base + path);
        if (withDigest) {
            asset.addProperty("digest", "sha256:" + sha256(jar));
        }
        JsonObject sources = new JsonObject();
        sources.addProperty("name", "savelogic-" + version + "-sources.jar");
        sources.addProperty("size", 1);
        sources.addProperty("browser_download_url", base + "/download/none");
        sources.addProperty("digest", "sha256:" + "00".repeat(32));
        JsonArray assets = new JsonArray();
        assets.add(sources); // Listed first: it must be skipped for the real jar.
        assets.add(asset);
        JsonObject release = new JsonObject();
        release.addProperty("tag_name", "v" + version);
        release.addProperty("prerelease", prerelease);
        release.addProperty("draft", draft);
        release.add("assets", assets);
        releases.add(release);
    }

    private static byte[] jar(String id, String version) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));
            zip.write(("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"" + version + "\"}").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void check(boolean ok, String what) {
        System.out.println("[SelfUpdateTest] " + (ok ? "PASS " : "FAIL ") + what);
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
