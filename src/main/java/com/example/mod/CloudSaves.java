package com.example.mod;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CloudSaves implements ModInitializer {
	public static final String MOD_ID = "cloudsaves";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("CloudSaves mod initialized!");
	}
}
