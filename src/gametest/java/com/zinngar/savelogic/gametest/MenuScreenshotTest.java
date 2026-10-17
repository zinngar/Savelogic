package com.zinngar.savelogic.gametest;

import com.zinngar.savelogic.client.TransferStatus;
import com.zinngar.savelogic.client.screen.CloudSavesScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;

/** Takes screenshots of the title screen button and every state of the transfer progress bar. */
public class MenuScreenshotTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        context.waitForScreen(TitleScreen.class);

        for (int scale : new int[]{2, 3, 4}) {
            context.runOnClient(minecraft -> {
                minecraft.options.guiScale().set(scale);
                minecraft.resizeGui();
            });
            context.waitTicks(5);
            context.runOnClient(minecraft -> {
                var screen = minecraft.gui.screen();
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
            minecraft.resizeGui();
        });
        context.setScreen(() -> new CloudSavesScreen(new TitleScreen()));
        context.waitTicks(5);
        context.takeScreenshot("cloud-saves-idle");

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
    }
}
