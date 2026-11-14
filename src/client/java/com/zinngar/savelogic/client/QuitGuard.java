package com.zinngar.savelogic.client;

import com.zinngar.savelogic.SaveLogic;
import com.zinngar.savelogic.client.screen.QuitGuardScreen;
import com.zinngar.savelogic.mixin.client.WindowAccessor;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Keeps a cloud save from being lost when Minecraft is closed. Quitting (the Quit Game button or closing the window)
 * while an upload or download is running waits for it and then closes; closing the window from inside a synced world
 * first leaves the world, so that it uploads like it does after Save and Quit. A screen offers to quit right away
 * or to keep playing instead.
 */
public final class QuitGuard {

    public enum State { NONE, LEAVE_WORLD, WAIT_FOR_UPLOAD, WAIT_FOR_TRANSFER, FAILED }

    /** How long after the world has closed to wait for its upload to start before giving up on it. */
    private static final int UPLOAD_START_TICKS = 100;

    private static State state = State.NONE;
    private static boolean quitting;
    private static int waited;
    /** Set instead of closing the game when SaveLogic's own tests run (-Dsavelogic.testNoQuit=true). */
    public static boolean quitRequested;

    private QuitGuard() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(QuitGuard::tick);
    }

    public static State state() {
        return state;
    }

    /** Called when Minecraft is asked to close. Returns true if SaveLogic is holding it open for now. */
    public static boolean holdQuit(Minecraft minecraft) {
        if (quitting) {
            return false;
        }
        if (state == State.NONE) {
            if (TransferStatus.INSTANCE.isActive()) {
                state = State.WAIT_FOR_TRANSFER;
                minecraft.gui.setScreen(new QuitGuardScreen());
            } else if (inSyncedWorld(minecraft)) {
                state = State.LEAVE_WORLD; // Left on the next tick, not in the middle of this one.
            } else {
                return false;
            }
        }
        // Closing the window keeps asking until the request is cleared.
        ((WindowAccessor) (Object) minecraft.getWindow()).savelogic$setShouldClose(false);
        return true;
    }

    /** True in a singleplayer world that uploads when it is left. */
    private static boolean inSyncedWorld(Minecraft minecraft) {
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (minecraft.level == null || server == null || !SaveLogic.CONFIG.autoUpload || CloudStorageManager.getProvider() == null) {
            return false;
        }
        return WorldSyncMarker.readLinked(server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize()).isPresent();
    }

    private static void tick(Minecraft minecraft) {
        switch (state) {
            case LEAVE_WORLD -> {
                state = State.WAIT_FOR_UPLOAD;
                waited = 0;
                minecraft.disconnectWithSavingScreen();
            }
            case WAIT_FOR_UPLOAD -> {
                if (minecraft.level == null && !(minecraft.gui.screen() instanceof QuitGuardScreen)) {
                    minecraft.gui.setScreen(new QuitGuardScreen());
                }
                if (TransferStatus.INSTANCE.isActive()) {
                    state = State.WAIT_FOR_TRANSFER;
                } else if (minecraft.getSingleplayerServer() == null && ++waited > UPLOAD_START_TICKS) {
                    quit(minecraft); // The world closed and nothing needed uploading.
                }
            }
            case WAIT_FOR_TRANSFER -> {
                if (!TransferStatus.INSTANCE.isActive()) {
                    if (TransferStatus.INSTANCE.lastFailed()) {
                        state = State.FAILED; // Don't close on a failure nobody has seen.
                        minecraft.gui.setScreen(new QuitGuardScreen());
                    } else {
                        quit(minecraft);
                    }
                }
            }
            default -> {
            }
        }
    }

    /** Closes Minecraft now, whatever is still running. */
    public static void quit(Minecraft minecraft) {
        state = State.NONE;
        if (Boolean.getBoolean("savelogic.testNoQuit")) {
            quitRequested = true;
            minecraft.gui.setScreen(new TitleScreen());
            return;
        }
        quitting = true;
        minecraft.stop();
    }

    /** Stops waiting to close; the transfer carries on. */
    public static void keepPlaying(Minecraft minecraft) {
        state = State.NONE;
        minecraft.gui.setScreen(new TitleScreen());
    }
}
