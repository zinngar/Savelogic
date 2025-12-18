package com.zinngar.savelogic.client.util;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;

import java.nio.file.Path;

public class WorldSaveUtils {

    /**
     * Gets the current open world save path on the client.
     *
     * @return A Path to the world's save folder, or null if not in a world.
     */
    public static Path getCurrentWorldSavePath() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            // Not in a world (e.g., at title screen)
            return null;
        }

        ClientWorld world = client.world;

        // This uses the game's save path resolver
        Path worldPath = FabricLoader.getInstance().getGameDir()
                .resolve("saves")
                .resolve(world.getRegistryKey().getValue().getPath());

        return worldPath;
    }
}
