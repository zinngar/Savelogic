package com.example.mod;

import com.example.mod.config.Config;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CloudSaves implements ModInitializer {
	public static final String MOD_ID = "savelogic";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static Config CONFIG;

	@Override
	public void onInitialize() {
		LOGGER.info("CloudSaves mod initialized!");
		CONFIG = Config.load();
	}
}
