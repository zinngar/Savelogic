package com.zinngar.savelogic.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.zinngar.savelogic.SaveLogic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * A small file in a world folder recording which cloud world it syncs with and which cloud version it was last in
 * step with. Worlds without it are local-only. It is never uploaded (see ZipUtil.EXCLUDED_WORLD_FILES).
 */
public record WorldSyncMarker(String cloudName, String stamp) {

    public static final String FILE = "savelogic-sync.json";
    private static final Gson GSON = new Gson();

    public static Optional<WorldSyncMarker> read(Path worldDir) {
        Path file = worldDir.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            JsonObject json = GSON.fromJson(Files.readString(file), JsonObject.class);
            return Optional.of(new WorldSyncMarker(json.get("cloudName").getAsString(), json.get("stamp").getAsString()));
        } catch (Exception e) {
            SaveLogic.LOGGER.warn("Ignoring unreadable {} in {}", FILE, worldDir);
            return Optional.empty();
        }
    }

    public void write(Path worldDir) {
        JsonObject json = new JsonObject();
        json.addProperty("cloudName", cloudName);
        json.addProperty("stamp", stamp);
        try {
            Files.writeString(worldDir.resolve(FILE), GSON.toJson(json));
        } catch (Exception e) {
            SaveLogic.LOGGER.error("Could not write {} in {}", FILE, worldDir, e);
        }
    }
}
