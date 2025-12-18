package com.zinngar.savelogic.client;

import com.zinngar.savelogic.OperationType;
import com.zinngar.savelogic.SaveLogic;
import com.zinngar.savelogic.backup.AutoBackupManager;
import com.zinngar.savelogic.config.Config;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.gui.screen.Screen;

public class SaveLogicClient implements ClientModInitializer {
    public static boolean isSavingOperation = false;
    public static Screen parentScreen = null;
    public static CloudSaveOperation currentOperation = null;

    @Override
    public void onInitializeClient() {
        SaveLogic.CONFIG = Config.load();

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (SaveLogic.CONFIG.isAutomaticBackup()) {
                AutoBackupManager.backupCurrentWorld();
            }
        });
    }

    public static void startCloudSave(Screen parentScreen, OperationType operationType) {
        SaveLogicClient.parentScreen = parentScreen;
        currentOperation = new CloudSaveOperation(operationType, parentScreen);
        isSavingOperation = true;
    }
}
