package com.zinngar.savelogic.gametest;

import com.zinngar.savelogic.client.SetupSync;
import com.zinngar.savelogic.client.TransferStatus;
import com.zinngar.savelogic.client.screen.ModUpdatesScreen;
import com.zinngar.savelogic.gametest.FakeModrinth.FakeVersion;
import com.zinngar.savelogic.mods.ModCompatibility;
import com.zinngar.savelogic.setup.SetupInstaller;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.TitleScreen;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * An update that Modrinth offers but that doesn't work with another installed mod (as a renderer update once did with
 * a shader mod) is left out, while the other updates still install.
 */
public class ModCompatibilityTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        String game = SetupSync.minecraftVersion();
        try (FakeModrinth modrinth = new FakeModrinth()) {
            System.setProperty("savelogic.testModrinthBase", modrinth.baseUrl());
            byte[] render1 = jar("render", "Renderer", "0.9.3-beta.1", "", "");
            byte[] render5 = jar("render", "Renderer", "0.9.3-beta.5", "", "\"shade\": \"<=1.12.0\"");
            byte[] shade = jar("shade", "Shaders", "1.11.7", "\"render\": \"0.9.x\"", "");
            byte[] tool1 = jar("tool", "Tool", "1.0", "\"lib\": [\">=2.0\", \"1.5\"]", "");
            byte[] tool2 = jar("tool", "Tool", "1.1", "\"lib\": \">=2.0\"", "");
            byte[] lib = jar("lib", "Library", "2.3", "", "");
            modrinth.project("render", "Renderer");
            modrinth.project("shade", "Shaders");
            modrinth.project("tool", "Tool");
            modrinth.project("lib", "Library");
            modrinth.version(new FakeVersion("r1", "render", "0.9.3-beta.1", "2026-01-01T00:00:00Z", "render-beta.1.jar", render1, List.of(), game));
            modrinth.version(new FakeVersion("r5", "render", "0.9.3-beta.5", "2026-02-01T00:00:00Z", "render-beta.5.jar", render5, List.of(), game));
            modrinth.version(new FakeVersion("s1", "shade", "1.11.7", "2026-01-01T00:00:00Z", "shade-1.11.7.jar", shade, List.of(), game));
            modrinth.version(new FakeVersion("t1", "tool", "1.0", "2026-01-01T00:00:00Z", "tool-1.0.jar", tool1, List.of(), game));
            modrinth.version(new FakeVersion("t2", "tool", "1.1", "2026-02-01T00:00:00Z", "tool-1.1.jar", tool2, List.of(), game));
            modrinth.version(new FakeVersion("l1", "lib", "2.3", "2026-01-01T00:00:00Z", "lib-2.3.jar", lib, List.of(), game));

            Path gameDir = FabricLoader.getInstance().getGameDir();
            Path mods = gameDir.resolve("mods");
            Path pendingMods = gameDir.resolve(SetupInstaller.PENDING_DIR).resolve("mods");
            SetupSync.cancelPendingInstall();
            Files.createDirectories(mods);
            try (Stream<Path> old = Files.list(mods)) {
                for (Path p : old.toList()) {
                    Files.deleteIfExists(p);
                }
            }
            Files.write(mods.resolve("render-beta.1.jar"), render1);
            Files.write(mods.resolve("shade-1.11.7.jar"), shade);
            Files.write(mods.resolve("tool-1.0.jar"), tool1);
            Files.write(mods.resolve("lib-2.3.jar"), lib);
            Files.write(mods.resolve("not-a-mod.jar"), "junk".getBytes(StandardCharsets.UTF_8));

            check(ModCompatibility.read(mods).size() == 4, "reads the mods' own declarations, skipping files that aren't mods");
            check(ModCompatibility.conflicts(ModCompatibility.read(mods)).isEmpty(), "the installed mods work together (a list of ranges counts if any matches)");

            // Both updates are offered; only the one that works is installed.
            context.setScreen(() -> new ModUpdatesScreen(new TitleScreen()));
            context.waitFor(mc -> mc.screen instanceof ModUpdatesScreen s && s.children().size() >= 6, 20 * 30);
            context.waitTicks(5);
            context.clickScreenButton("Update 2 Mods");
            context.waitFor(mc -> !TransferStatus.INSTANCE.isActive(), 20 * 30);
            context.waitForScreen(ConfirmScreen.class);
            context.waitTicks(5);
            context.takeScreenshot("mod-updates-one-left-out");
            Set<String> staged = names(pendingMods);
            check(staged.equals(Set.of("render-beta.1.jar", "shade-1.11.7.jar", "tool-1.1.jar", "lib-2.3.jar", "not-a-mod.jar")),
                    "the update that breaks another mod is left out and the other one is staged: " + staged);
            check(ModCompatibility.conflicts(ModCompatibility.read(pendingMods)).isEmpty(), "what will be installed works together");
            context.clickScreenButton("Later");
            context.waitTicks(5);

            // When nothing is left to install, nothing is staged and the reason is shown.
            SetupSync.cancelPendingInstall();
            Files.delete(mods.resolve("tool-1.0.jar"));
            Files.write(mods.resolve("tool-1.1.jar"), tool2);
            context.setScreen(() -> new ModUpdatesScreen(new TitleScreen()));
            context.waitFor(mc -> mc.screen instanceof ModUpdatesScreen s && s.children().size() >= 6, 20 * 30);
            context.waitTicks(5);
            context.clickScreenButton("Update 1 Mod");
            context.waitFor(mc -> !TransferStatus.INSTANCE.isActive(), 20 * 30);
            context.waitTicks(5);
            String result = TransferStatus.INSTANCE.lastResult();
            check(TransferStatus.INSTANCE.lastFailed() && result.contains("Renderer 0.9.3-beta.5 doesn't work with Shaders 1.11.7"),
                    "says why nothing was updated: " + result);
            check(!SetupSync.isInstallPending(), "and leaves nothing waiting to install");
            context.takeScreenshot("mod-updates-none-left");
            context.setScreen(TitleScreen::new);
            context.waitTicks(20 * 6);
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            System.clearProperty("savelogic.testModrinthBase");
        }
    }

    private static Set<String> names(Path dir) throws Exception {
        Set<String> names = new TreeSet<>();
        try (Stream<Path> list = Files.list(dir)) {
            list.forEach(p -> names.add(p.getFileName().toString()));
        }
        return names;
    }

    private static byte[] jar(String id, String name, String version, String depends, String breaks) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));
            zip.write(("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"version\":\"" + version
                    + "\",\"depends\":{" + depends + "},\"breaks\":{" + breaks + "}}").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static void check(boolean ok, String what) {
        System.out.println("[ModCompatibilityTest] " + (ok ? "PASS " : "FAIL ") + what);
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
