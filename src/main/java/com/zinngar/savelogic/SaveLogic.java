package com.zinngar.savelogic;

import com.zinngar.savelogic.config.Config;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SaveLogic implements ModInitializer {
	public static final String MOD_ID = "savelogic";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static Config CONFIG;

	@Override
	public void onInitialize() {
		LOGGER.info("SaveLogic mod initialized!");
	}
}
