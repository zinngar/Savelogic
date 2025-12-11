package com.example.mod;

import net.fabricmc.api.ModInitializer;
import net.minecraft.client.gui.screen.Screen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CloudSaves implements ModInitializer {
	public static final String MOD_ID = "cloudsaves";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static Config CONFIG;
    public static boolean isSavingOperation = false;
    public static Screen parentScreen = null;
    public static CloudSaveOperation currentOperation = null;

	@Override
	public void onInitialize() {
		LOGGER.info("CloudSaves mod initialized!");
		CONFIG = Config.load();
	}

    public static void startCloudSave(Screen parentScreen, OperationType operationType) {
        CloudSaves.parentScreen = parentScreen;
        currentOperation = new CloudSaveOperation(operationType, parentScreen);
        isSavingOperation = true;
    }
}
