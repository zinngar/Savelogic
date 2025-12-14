package com.example.mod;

import com.example.mod.client.screen.CloudSaveSelectionScreen;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;

public class CloudSavesClient implements ClientModInitializer {
    public static boolean isSavingOperation = false;
    public static Screen parentScreen = null;
    public static CloudSaveOperation currentOperation = null;

    @Override
    public void onInitializeClient() {
        CloudSaves.LOGGER.info("CloudSaves client initialized.");
    }

    public static void startOperation(Screen parent, OperationType type) {
        parentScreen = parent;
        currentOperation = new CloudSaveOperation(type, parent);
        MinecraftClient.getInstance().setScreen(new CloudSaveSelectionScreen(currentOperation));
    }
}
