package com.zinngar.savelogic.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.zinngar.savelogic.SaveLogic;
import com.zinngar.savelogic.util.WorldManifest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * A small file in a world folder recording which cloud world it syncs with, which cloud version it was last in
 * step with, and the fingerprint of every file at that point (so the next upload can send only what changed).
 * Worlds without it are local-only. "unlinked" records that the player chose to keep this world separate from a
 * cloud world of the same name. It is never uploaded (see ZipUtil.EXCLUDED_WORLD_FILES).
 */
public record WorldSyncMarker(String cloudName, String stamp, WorldManifest files, boolean unlinked) {

    public static final String FILE = "savelogic-sync.json";
    private static final Gson GSON = new Gson();

    public WorldSyncMarker(String cloudName, String stamp, WorldManifest files) {
        this(cloudName, stamp, files, false);
    }

    public static WorldSyncMarker unlinkedMarker() {
        return new WorldSyncMarker("", "", null, true);
    }

    /** True if this world syncs with a cloud world (rather than having no marker, or being kept separate). */
    public boolean linked() {
        return !unlinked;
    }

    public static Optional<WorldSyncMarker> read(Path worldDir) {
        Path file = worldDir.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            JsonObject json = GSON.fromJson(Files.readString(file), JsonObject.class);
            boolean unlinked = json.has("unlinked") && json.get("unlinked").getAsBoolean();
            WorldManifest files = json.has("files") && json.get("files").isJsonObject()
                    ? WorldManifest.fromJson(json.getAsJsonObject("files")) : null;
            return Optional.of(new WorldSyncMarker(
                    json.has("cloudName") ? json.get("cloudName").getAsString() : "",
                    json.has("stamp") ? json.get("stamp").getAsString() : "", files, unlinked));
        } catch (Exception e) {
            SaveLogic.LOGGER.warn("Ignoring unreadable {} in {}", FILE, worldDir);
            return Optional.empty();
        }
    }

    /** Reads the marker only if it links the world to a cloud world. */
    public static Optional<WorldSyncMarker> readLinked(Path worldDir) {
        return read(worldDir).filter(WorldSyncMarker::linked);
    }

    public void write(Path worldDir) {
        JsonObject json = new JsonObject();
        json.addProperty("cloudName", cloudName);
        json.addProperty("stamp", stamp);
        if (unlinked) {
            json.addProperty("unlinked", true);
        }
        if (files != null) {
            json.add("files", files.toJson());
        }
        try {
            Files.writeString(worldDir.resolve(FILE), GSON.toJson(json));
        } catch (Exception e) {
            SaveLogic.LOGGER.error("Could not write {} in {}", FILE, worldDir, e);
        }
    }

    /** When the world was last played here: the time its level.dat was last saved (0 if there is none). */
    public static long lastPlayed(Path worldDir) {
        try {
            return Files.getLastModifiedTime(worldDir.resolve("level.dat")).toMillis();
        } catch (Exception e) {
            return 0;
        }
    }
}
