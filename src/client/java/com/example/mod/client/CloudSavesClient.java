package com.example.mod.client;

import com.example.mod.CloudSaveOperation;
import com.example.mod.OperationType;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.gui.screen.Screen;

public class CloudSavesClient implements ClientModInitializer {
    public static boolean isSavingOperation = false;
    public static Screen parentScreen = null;
    public static CloudSaveOperation currentOperation = null;

    @Override
    public void onInitializeClient() {
        // Client-specific initialization, if any.
    }

    public static void startCloudSave(Screen parentScreen, OperationType operationType) {
        CloudSavesClient.parentScreen = parentScreen;
        currentOperation = new CloudSaveOperation(operationType, parentScreen);
        isSavingOperation = true;
    }
}
