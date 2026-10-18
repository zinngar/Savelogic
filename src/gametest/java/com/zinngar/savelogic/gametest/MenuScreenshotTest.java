package com.zinngar.savelogic.gametest;

import com.zinngar.savelogic.client.TransferStatus;
import com.zinngar.savelogic.client.SetupSync;
import com.zinngar.savelogic.setup.SetupInstaller;
import net.fabricmc.loader.api.FabricLoader;
import com.zinngar.savelogic.client.screen.CloudSavesScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;

/** Takes screenshots of the title screen button and every state of the transfer progress bar. */
public class MenuScreenshotTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        context.waitForScreen(TitleScreen.class);

        for (int scale : new int[]{2, 3, 4}) {
            context.runOnClient(minecraft -> {
                minecraft.options.guiScale().set(scale);
                minecraft.resizeDisplay();
            });
            context.waitTicks(5);
            context.runOnClient(minecraft -> {
                var screen = minecraft.screen;
                Button cloud = screen.children().stream()
                        .filter(child -> child instanceof Button b && b.getMessage().getString().equals("☁"))
                        .map(Button.class::cast).findFirst()
                        .orElseThrow(() -> new AssertionError("Cloud Saves button missing at GUI scale " + scale));
                boolean onScreen = cloud.getX() >= 0 && cloud.getY() >= 0
                        && cloud.getRight() <= screen.width && cloud.getBottom() <= screen.height;
                if (!onScreen) {
                    throw new AssertionError("Cloud Saves button off screen at GUI scale " + scale + ": " + cloud.getRectangle()
                            + " in " + screen.width + "x" + screen.height);
                }
            });
            context.takeScreenshot("title-gui" + scale);
        }

        context.runOnClient(minecraft -> {
            minecraft.options.guiScale().set(2);
            minecraft.resizeDisplay();
        });
        context.setScreen(() -> new CloudSavesScreen(new TitleScreen()));
        context.waitTicks(5);
        context.takeScreenshot("cloud-saves-idle");

        checkSetupZip();

        TransferStatus status = TransferStatus.INSTANCE;
        status.begin("Saving \"My World\" to cloud");
        status.phase("Zipping world", 0);
        context.waitTicks(5);
        context.takeScreenshot("progress-unknown-size");

        status.phase("Uploading part 2 of 3", 1024L * 1024 * 1024);
        context.waitTicks(30);
        status.bytes(410L * 1024 * 1024);
        context.setScreen(() -> new CloudSavesScreen(new TitleScreen()));
        context.waitTicks(5);
        context.takeScreenshot("progress-uploading");

        context.setScreen(TitleScreen::new);
        context.waitTicks(5);
        context.takeScreenshot("progress-on-title-screen");

        status.succeed("Done! \"My World\" is saved on GitHub.");
        context.waitTicks(5);
        context.takeScreenshot("progress-done");

        status.begin("Saving \"My World\" to cloud");
        status.fail("Failed: GitHub upload failed (HTTP 401): Bad credentials (check your token)");
        context.waitTicks(5);
        context.takeScreenshot("progress-failed");

        // Leave a downloaded setup pending: closing the game at the end of the test must start the installer.
        try {
            Path pendingMods = FabricLoader.getInstance().getGameDir().resolve(SetupInstaller.PENDING_DIR).resolve("mods");
            Files.createDirectories(pendingMods);
            Files.writeString(pendingMods.resolve("installed-by-test.jar"), "new");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        context.setScreen(() -> new CloudSavesScreen(new TitleScreen()));
        context.waitTicks(25); // let the result message fade
        context.takeScreenshot("cloud-saves-install-pending");
        context.setScreen(TitleScreen::new);
        context.waitTicks(2);
    }

    /** The setup zip must contain mods and configs but never SaveLogic's token files. */
    private static void checkSetupZip() {
        try {
            Path gameDir = FabricLoader.getInstance().getGameDir();
            Files.createDirectories(gameDir.resolve("mods"));
            Files.writeString(gameDir.resolve("mods").resolve("fake-mod.jar"), "old");
            Files.createDirectories(gameDir.resolve("config").resolve("fakemod"));
            Files.writeString(gameDir.resolve("config").resolve("fakemod").resolve("settings.toml"), "x = 1");
            Files.writeString(gameDir.resolve("config").resolve("savelogic.key"), "secret");

            Path zip = Files.createTempFile("setup-test", ".zip");
            List<String> mods = SetupSync.zipSetup(zip, TransferStatus.INSTANCE);
            Set<String> names = new HashSet<>();
            try (ZipFile file = new ZipFile(zip.toFile())) {
                file.stream().forEach(entry -> names.add(entry.getName()));
            }
            Files.delete(zip);
            for (String expected : List.of("mods/fake-mod.jar", "config/fakemod/settings.toml", "savelogic-setup.json")) {
                if (!names.contains(expected)) {
                    throw new AssertionError("Setup zip is missing " + expected + ": " + names);
                }
            }
            for (String forbidden : List.of("config/savelogic.json", "config/savelogic.key")) {
                if (names.contains(forbidden)) {
                    throw new AssertionError("Setup zip must not contain " + forbidden);
                }
            }
            if (!mods.equals(List.of("fake-mod.jar"))) {
                throw new AssertionError("Unexpected mod list " + mods);
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
