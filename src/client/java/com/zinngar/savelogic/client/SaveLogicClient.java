package com.zinngar.savelogic.client;

import com.zinngar.savelogic.OperationType;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.gui.screen.Screen;

public class SaveLogicClient implements ClientModInitializer {
    public static boolean isSavingOperation = false;
    public static Screen parentScreen = null;
    public static CloudSaveOperation currentOperation = null;

    @Override
    public void onInitializeClient() {
    }

    public static void startCloudSave(Screen parentScreen, OperationType operationType) {
        SaveLogicClient.parentScreen = parentScreen;
        currentOperation = new CloudSaveOperation(operationType, parentScreen);
        isSavingOperation = true;
    }
}
