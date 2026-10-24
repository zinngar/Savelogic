package com.zinngar.savelogic.client;

import com.zinngar.savelogic.SaveLogic;
import com.zinngar.savelogic.backup.AutoBackupManager;
import com.zinngar.savelogic.config.Config;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.level.storage.LevelResource;

public class SaveLogicClient implements ClientModInitializer {
    /**
     * The world-select screen opened by "Save to Cloud". Picking a world on this screen uploads it
     * instead of opening it; any other world-select screen behaves normally.
     */
    public static Screen uploadPickerScreen = null;
    public static Screen uploadReturnScreen = null;

    @Override
    public void onInitializeClient() {
        SaveLogic.CONFIG = Config.load();
        TransferStatus.register();
        SetupSync.register();
        SyncGate.register();
        SchematicSync.register();

        // Runs after the singleplayer server has saved and closed the world, so the files are complete.
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (!server.isDedicatedServer() && SaveLogic.CONFIG.isAutomaticBackup()) {
                AutoBackupManager.backupWorld(server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize());
            }
        });
    }
}
