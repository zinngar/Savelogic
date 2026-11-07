package com.zinngar.savelogic.gametest;

import com.zinngar.savelogic.GitHubStorageProvider;
import com.zinngar.savelogic.GitHubStorageProvider.CloudWorld;
import com.zinngar.savelogic.SaveLogic;
import com.zinngar.savelogic.client.CloudSync;
import com.zinngar.savelogic.client.TransferStatus;
import com.zinngar.savelogic.client.screen.ChoiceScreen;
import com.zinngar.savelogic.util.XaeroWaypoints;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.TitleScreen;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Xaero's Minimap waypoints travelling with a world, against an in-memory {@link FakeGitHub}. If the real mod is
 * installed in the test game it also checks that Xaero's loads a restored waypoint when the world is opened.
 */
public class XaeroWaypointsTest implements FabricClientGameTest {

    private static final String FILE = "dim%0/waypoints.txt"; // A singleplayer world's overworld waypoints.

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
            SaveLogic.CONFIG.deviceId = "mac-id";
            SaveLogic.CONFIG.deviceName = "MacBook";
            SaveLogic.CONFIG.autoUpload = true;
            SaveLogic.CONFIG.keepVersions = 5;
            SaveLogic.CONFIG.schematicsAutoSync = false;
        });
        GitHubStorageProvider github = new GitHubStorageProvider("o/r", "good-token");
        Path gameDir = FabricLoader.getInstance().getGameDir();
        Path minimap = gameDir.resolve("xaero").resolve("minimap");

        Path worldDir;
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            worldDir = world.getWorldSave().getSaveDirectory();
        }
        context.waitForScreen(TitleScreen.class);
        context.waitTicks(10);
        waitForTransfer(context);
        String folder = worldDir.getFileName().toString();

        // A world with no waypoints uploads nothing for them, even if Xaero's has written its config.
        deleteTree(minimap.resolve(folder));
        write(minimap.resolve(folder).resolve("config.txt"), "usingMultiworldDetection:false");
        upload(context, folder);
        check(!Files.exists(worldDir.resolve(XaeroWaypoints.WORLD_FOLDER)), "a world without waypoints uploads none");

        // Waypoints go up with the world, as a change-only upload once the world is in the cloud.
        write(minimap.resolve(folder).resolve(FILE), waypoints("Home", 10));
        upload(context, folder);
        CloudWorld cloud = cloud(github, folder);
        check(cloud.versions().size() == 2 && !cloud.newest().isFull(), "a new waypoint uploads as changes");
        check(read(worldDir.resolve(XaeroWaypoints.WORLD_FOLDER).resolve(FILE)).equals(waypoints("Home", 10)), "waypoints are stored with the world");

        upload(context, folder);
        check(cloud(github, folder).versions().size() == 2, "unchanged waypoints upload nothing");

        // Another copy of the world gets them under its own folder name.
        context.runOnClient(mc -> CloudSync.downloadWorld(cloud(github, folder), cloud(github, folder).newest(), mc.screen, f -> {}));
        context.waitForScreen(ChoiceScreen.class);
        context.clickScreenButton("Keep both (download as a separate world)");
        waitForTransfer(context);
        String copy = folder + " (2)";
        check(read(minimap.resolve(copy).resolve(FILE)).equals(waypoints("Home", 10)), "a downloaded world's waypoints are put where Xaero's looks");

        // Moving to another version replaces the waypoints, keeping different local ones in savelogic-backups.
        String withHome = cloud(github, folder).newest().stamp();
        write(minimap.resolve(folder).resolve(FILE), waypoints("Base", 99));
        upload(context, folder);
        check(!cloud(github, folder).newest().stamp().equals(withHome), "an edited waypoint uploads a new version");
        write(minimap.resolve(folder).resolve(FILE), waypoints("Unsaved", 5));
        CloudWorld history = cloud(github, folder);
        var older = history.versions().stream().filter(v -> v.stamp().equals(withHome)).findFirst().orElseThrow();
        context.runOnClient(mc -> CloudSync.downloadWorld(history, older, mc.screen, f -> {}));
        context.waitForScreen(ChoiceScreen.class);
        context.clickScreenButton("Replace my copy");
        waitForTransfer(context);
        check(read(minimap.resolve(folder).resolve(FILE)).equals(waypoints("Home", 10)), "restoring a version restores its waypoints");
        check(backupHas(gameDir, folder, waypoints("Unsaved", 5)), "waypoints that weren't uploaded are backed up first");

        // A version with no waypoints leaves this computer's alone, and so does a computer with none uploading.
        Path scratch = Files.createTempDirectory("savelogic-xaero");
        Path plainWorld = scratch.resolve("saves").resolve("Plain_World[1]");
        Files.createDirectories(plainWorld);
        Path scratchFile = scratch.resolve("xaero/minimap/Plain%us%World%lb%1%rb%").resolve(FILE);
        write(scratchFile, waypoints("Mine", 1));
        XaeroWaypoints.restore(scratch, plainWorld);
        check(read(scratchFile).equals(waypoints("Mine", 1)), "a world without waypoints leaves local ones alone");
        XaeroWaypoints.capture(scratch, plainWorld);
        check(read(plainWorld.resolve(XaeroWaypoints.WORLD_FOLDER).resolve(FILE)).equals(waypoints("Mine", 1)),
                "world names with special characters use Xaero's folder name");
        deleteTree(scratch);

        if (FabricLoader.getInstance().isModLoaded("xaerominimap")) {
            checkRealXaero(context, copy);
        }
        context.runOnClient(mc -> SaveLogic.CONFIG.schematicsAutoSync = true);
        context.setScreen(TitleScreen::new);
        context.waitTicks(5);
    }

    /** Opens the downloaded copy and asks the real mod which waypoints it loaded. */
    private void checkRealXaero(ClientGameTestContext context, String folder) throws Exception {
        context.runOnClient(mc -> mc.createWorldOpenFlows().openWorld(folder, () -> {}));
        context.waitFor(mc -> mc.level != null, 20 * 120);
        context.waitTicks(100);
        String names = context.computeOnClient(mc -> {
            try {
                Object module = Class.forName("xaero.hud.minimap.BuiltInHudModules").getField("MINIMAP").get(null);
                Object session = call(module, "getCurrentSession");
                Object world = call(call(session, "getWorldManager"), "getCurrentWorld");
                StringBuilder result = new StringBuilder();
                for (Object waypoint : (Iterable<?>) call(call(world, "getCurrentWaypointSet"), "getWaypoints")) {
                    result.append(call(waypoint, "getName")).append('@').append(call(waypoint, "getX")).append(' ');
                }
                return result.toString().trim();
            } catch (Exception e) {
                return "error: " + e;
            }
        });
        check(names.equals("Home@10"), "real Xaero's loaded the restored waypoint: " + names);

        // What the real mod itself saves is what travels: its own format and file names, not just this test's.
        String saved = context.computeOnClient(mc -> {
            try {
                Object module = Class.forName("xaero.hud.minimap.BuiltInHudModules").getField("MINIMAP").get(null);
                Object session = call(module, "getCurrentSession");
                Object world = call(call(session, "getWorldManager"), "getCurrentWorld");
                Class<?> waypointClass = Class.forName("xaero.common.minimap.waypoints.Waypoint");
                Object waypoint = waypointClass.getConstructor(int.class, int.class, int.class, String.class, String.class, int.class)
                        .newInstance(77, 70, 88, "Saved", "S", 2);
                call(world, "getCurrentWaypointSet").getClass().getMethod("add", waypointClass).invoke(call(world, "getCurrentWaypointSet"), waypoint);
                Object io = call(session, "getWorldManagerIO");
                io.getClass().getMethod("saveWorld", world.getClass()).invoke(io, world);
                return "saved";
            } catch (Exception e) {
                return "error: " + e + " / " + e.getCause();
            }
        });
        check(saved.equals("saved"), "real Xaero's saved a new waypoint: " + saved);
        // Xaero's writes on every edit, so what it just saved can be carried without leaving the world first.
        Path worldDir = context.computeOnClient(mc -> CloudSync.savesDir().resolve(folder));
        XaeroWaypoints.capture(FabricLoader.getInstance().getGameDir(), worldDir);
        String captured = read(worldDir.resolve(XaeroWaypoints.WORLD_FOLDER).resolve(FILE));
        check(captured.contains("Saved") && captured.contains("Home"), "the waypoints real Xaero's wrote are carried with the world");

        // Only runs with the real mod added by hand. The test framework deadlocks if the world is left with it loaded
        // (a thread dump shows only the framework's own locks), so the run is read from its log and stopped here.
        context.waitTicks(Integer.MAX_VALUE);
    }

    private static Object call(Object target, String method) throws Exception {
        Method m = target.getClass().getMethod(method);
        m.setAccessible(true);
        return m.invoke(target);
    }

    private static String waypoints(String name, int x) {
        return "sets:gui.xaero_default\n#\n#waypoint:name:initials:x:y:z:color:disabled:type:set:rotate_on_tp:tp_yaw:visibility_type:destination\n#\n"
                + "waypoint:" + name + ":" + name.charAt(0) + ":" + x + ":64:20:1:false:0:gui.xaero_default:false:0:0:false\n";
    }

    private static boolean backupHas(Path gameDir, String folder, String content) throws Exception {
        Path backups = gameDir.resolve("savelogic-backups").resolve("waypoints");
        if (!Files.isDirectory(backups)) {
            return false;
        }
        try (Stream<Path> dirs = Files.list(backups)) {
            return dirs.filter(d -> d.getFileName().toString().startsWith(folder + "_")).anyMatch(d -> {
                try {
                    return read(d.resolve(FILE)).equals(content);
                } catch (Exception e) {
                    return false;
                }
            });
        }
    }

    private static CloudWorld cloud(GitHubStorageProvider github, String name) {
        return github.findWorld(name).join().orElseThrow(() -> new AssertionError("World not in cloud: " + name));
    }

    private static void upload(ClientGameTestContext context, String folder) {
        context.runOnClient(mc -> CloudSync.uploadWorld(folder, false, mc.screen));
        waitForTransfer(context);
    }

    private static void waitForTransfer(ClientGameTestContext context) {
        context.waitTicks(2);
        context.waitFor(mc -> !TransferStatus.INSTANCE.isActive(), 20 * 120);
        context.waitTicks(2);
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
        System.out.println("[XaeroWaypointsTest] " + (ok ? "PASS " : "FAIL ") + what);
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
