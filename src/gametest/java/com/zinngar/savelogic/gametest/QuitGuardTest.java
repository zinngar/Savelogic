package com.zinngar.savelogic.gametest;

import com.zinngar.savelogic.GitHubStorageProvider;
import com.zinngar.savelogic.GitHubStorageProvider.CloudWorld;
import com.zinngar.savelogic.SaveLogic;
import com.zinngar.savelogic.client.CloudSync;
import com.zinngar.savelogic.client.QuitGuard;
import com.zinngar.savelogic.client.TransferStatus;
import com.zinngar.savelogic.client.screen.QuitGuardScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.TitleScreen;

import java.nio.file.Path;

/**
 * Closing Minecraft while a cloud save is running, or from inside a synced world: the game waits (or leaves the world
 * and uploads it) before closing. Runs with -Dsavelogic.testNoQuit, which records the quit instead of closing.
 */
public class QuitGuardTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (FakeGitHub fake = new FakeGitHub()) {
            System.setProperty("savelogic.testApiBase", fake.baseUrl());
            System.setProperty("savelogic.testNoQuit", "true");
            run(context);
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            System.clearProperty("savelogic.testApiBase");
            System.clearProperty("savelogic.testNoQuit");
        }
    }

    private void run(ClientGameTestContext context) {
        context.runOnClient(mc -> {
            SaveLogic.CONFIG.github.repositoryUrl = "o/r";
            SaveLogic.CONFIG.github.personalAccessToken = "good-token";
            SaveLogic.CONFIG.deviceId = "mac-id";
            SaveLogic.CONFIG.deviceName = "MacBook";
            SaveLogic.CONFIG.autoUpload = true;
            SaveLogic.CONFIG.schematicsAutoSync = false;
        });
        context.setScreen(TitleScreen::new);
        context.waitTicks(5);
        TransferStatus status = TransferStatus.INSTANCE;

        check(!context.computeOnClient(QuitGuard::holdQuit), "with nothing running, quitting isn't held up");

        // Quit during an upload: wait, with a way out either side.
        status.begin("Saving \"My World\" to cloud");
        status.phase("Uploading", 1024L * 1024 * 300);
        context.waitTicks(40);
        status.bytes(1024L * 1024 * 120);
        context.runOnClient(mc -> mc.stop());
        context.waitTicks(5);
        check(context.computeOnClient(mc -> mc.isRunning() && mc.gui.screen() instanceof QuitGuardScreen), "quitting during an upload waits instead of closing");
        context.takeScreenshot("quit-waiting");
        context.runOnClient(mc -> mc.stop());
        context.waitTicks(5);
        check(context.computeOnClient(mc -> mc.isRunning()) && !QuitGuard.quitRequested, "asking again (as closing the window does) still waits");
        context.clickScreenButton("Keep Playing");
        context.waitTicks(30);
        check(QuitGuard.state() == QuitGuard.State.NONE && status.isActive() && !QuitGuard.quitRequested, "Keep Playing carries on with the upload");

        context.runOnClient(mc -> mc.stop());
        context.waitTicks(5);
        status.succeed("Done! \"My World\" is saved on GitHub.");
        context.waitTicks(5);
        check(QuitGuard.quitRequested, "Minecraft closes once the upload has finished");
        QuitGuard.quitRequested = false;

        status.begin("Saving \"My World\" to cloud");
        context.runOnClient(mc -> mc.stop());
        context.waitTicks(5);
        context.clickScreenButton("Quit Now (lose this upload)");
        context.waitTicks(5);
        check(QuitGuard.quitRequested, "Quit Now closes straight away");
        QuitGuard.quitRequested = false;

        // A failed upload doesn't close the game unseen.
        context.runOnClient(mc -> mc.stop());
        context.waitTicks(5);
        status.fail("Failed: GitHub upload failed (HTTP 401): Bad credentials (check your token)");
        context.waitTicks(125); // Let the result banner fade so the screen's own message shows.
        check(!QuitGuard.quitRequested && QuitGuard.state() == QuitGuard.State.FAILED, "a failed upload keeps Minecraft open");
        context.takeScreenshot("quit-upload-failed");
        context.clickScreenButton("Back to Menu");
        context.waitTicks(5);
        check(QuitGuard.state() == QuitGuard.State.NONE, "Back to Menu returns to the game");

        // Closing the window from inside a world.
        GitHubStorageProvider github = new GitHubStorageProvider("o/r", "good-token");
        Path worldDir;
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            worldDir = world.getWorldSave().getSaveDirectory();
            context.waitTicks(20);
            check(!context.computeOnClient(QuitGuard::holdQuit), "a world that doesn't sync closes as normal");
        }
        context.waitForScreen(TitleScreen.class);
        context.waitTicks(10);
        String folder = worldDir.getFileName().toString();
        context.runOnClient(mc -> CloudSync.uploadWorld(folder, false, mc.gui.screen()));
        waitForTransfer(context);
        check(cloud(github, folder).versions().size() == 1, "the world is synced");

        context.runOnClient(mc -> mc.createWorldOpenFlows().openWorld(folder, () -> {}));
        context.waitFor(mc -> mc.level != null, 20 * 120);
        context.waitTicks(60);
        context.runOnClient(mc -> mc.stop());
        context.waitFor(mc -> QuitGuard.quitRequested, 20 * 90);
        CloudWorld after = cloud(github, folder);
        check(context.computeOnClient(mc -> mc.level == null && mc.getSingleplayerServer() == null), "closing from inside a synced world leaves the world first");
        check(after.versions().size() == 2 && after.lock() == null, "...uploads it and releases its lock before closing (" + after.versions().size() + " versions)");
        QuitGuard.quitRequested = false;

        context.runOnClient(mc -> SaveLogic.CONFIG.schematicsAutoSync = true);
        context.setScreen(TitleScreen::new);
        context.waitTicks(20 * 6);
    }

    private static CloudWorld cloud(GitHubStorageProvider github, String name) {
        return github.findWorld(name).join().orElseThrow(() -> new AssertionError("World not in cloud: " + name));
    }

    private static void waitForTransfer(ClientGameTestContext context) {
        context.waitTicks(2);
        context.waitFor(mc -> !TransferStatus.INSTANCE.isActive(), 20 * 120);
        context.waitTicks(2);
    }

    private static void check(boolean ok, String what) {
        System.out.println("[QuitGuardTest] " + (ok ? "PASS " : "FAIL ") + what);
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
