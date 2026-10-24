package com.zinngar.savelogic.gametest;

import com.zinngar.savelogic.client.SetupSync;
import com.zinngar.savelogic.client.TransferStatus;
import com.zinngar.savelogic.client.screen.ModUpdatesScreen;
import com.zinngar.savelogic.gametest.FakeModrinth.FakeVersion;
import com.zinngar.savelogic.setup.SetupInstaller;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.TitleScreen;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Check for Mod Updates against an in-memory {@link FakeModrinth}: an update that needs a new library, an up-to-date
 * mod, a mod with no build for this Minecraft version and a jar Modrinth doesn't know. Leaves the staged update in
 * place so the installer swaps it in when the test game closes (checked from outside the game).
 */
public class ModUpdatesTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        String game = SetupSync.minecraftVersion();
        try (FakeModrinth modrinth = new FakeModrinth()) {
            System.setProperty("savelogic.testModrinthBase", modrinth.baseUrl());
            modrinth.project("alpha", "Alpha Mod");
            modrinth.project("beta", "Beta Mod");
            modrinth.project("delta", "Delta Mod");
            modrinth.project("lib", "Some Library");
            modrinth.version(new FakeVersion("a1", "alpha", "1.0", "2026-01-01T00:00:00Z", "alpha-1.0.jar", bytes("alpha 1.0"), List.of(), game));
            modrinth.version(new FakeVersion("a2", "alpha", "2.0", "2026-02-01T00:00:00Z", "alpha-2.0.jar", bytes("alpha 2.0"), List.of("lib"), game));
            modrinth.version(new FakeVersion("b1", "beta", "1.0", "2026-01-01T00:00:00Z", "beta-1.0.jar", bytes("beta 1.0"), List.of(), game));
            modrinth.version(new FakeVersion("d1", "delta", "1.0", "2026-01-01T00:00:00Z", "delta-1.0.jar", bytes("delta 1.0"), List.of(), "0.0.1"));
            modrinth.version(new FakeVersion("l1", "lib", "3.1", "2026-01-15T00:00:00Z", "some-library-3.1.jar", bytes("lib 3.1"), List.of(), game));

            Path mods = FabricLoader.getInstance().getGameDir().resolve("mods");
            SetupSync.cancelPendingInstall(); // An earlier test leaves a setup install pending.
            Files.createDirectories(mods);
            try (Stream<Path> old = Files.list(mods)) {
                for (Path p : old.toList()) {
                    Files.deleteIfExists(p);
                }
            }
            Files.write(mods.resolve("alpha-1.0.jar"), bytes("alpha 1.0"));
            Files.write(mods.resolve("beta-1.0.jar"), bytes("beta 1.0"));
            Files.write(mods.resolve("delta-1.0.jar"), bytes("delta 1.0"));
            Files.write(mods.resolve("homemade.jar"), bytes("not on modrinth"));

            context.setScreen(() -> new ModUpdatesScreen(new TitleScreen()));
            context.waitFor(mc -> mc.gui.screen() instanceof ModUpdatesScreen s && s.children().size() >= 6, 20 * 30);
            context.waitTicks(5);
            context.takeScreenshot("mod-updates-list");

            context.clickScreenButton("Update 1 Mod");
            context.waitFor(mc -> !TransferStatus.INSTANCE.isActive(), 20 * 30);
            context.waitForScreen(ConfirmScreen.class);
            context.takeScreenshot("mod-updates-downloaded");

            Path pendingMods = FabricLoader.getInstance().getGameDir().resolve(SetupInstaller.PENDING_DIR).resolve("mods");
            Set<String> staged = new TreeSet<>();
            try (Stream<Path> list = Files.list(pendingMods)) {
                list.forEach(p -> staged.add(p.getFileName().toString()));
            }
            check(staged.equals(Set.of("alpha-2.0.jar", "some-library-3.1.jar", "beta-1.0.jar", "delta-1.0.jar", "homemade.jar")),
                    "staged the update, the library it needs and every other mod, without the old version: " + staged);
            check(new String(Files.readAllBytes(pendingMods.resolve("alpha-2.0.jar")), StandardCharsets.UTF_8).equals("alpha 2.0"),
                    "the downloaded update is the real file (fingerprint checked)");
            check(Files.exists(mods.resolve("alpha-1.0.jar")), "the running game's mods are untouched until Minecraft closes");

            context.clickScreenButton("Later");
            context.waitTicks(5);
            context.setScreen(TitleScreen::new);
            context.waitTicks(5);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        } finally {
            System.clearProperty("savelogic.testModrinthBase");
        }
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static void check(boolean ok, String what) {
        System.out.println("[ModUpdatesTest] " + (ok ? "PASS " : "FAIL ") + what);
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
