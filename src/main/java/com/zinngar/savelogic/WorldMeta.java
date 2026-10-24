package com.zinngar.savelogic;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.zinngar.savelogic.GitHubStorageProvider.CloudSave;
import com.zinngar.savelogic.GitHubStorageProvider.Lock;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per-world data kept in the release notes: which computer uploaded each version, and which computer has the world
 * open. It is stored Base64-encoded in an HTML comment (invisible on GitHub) below a readable summary.
 */
final class WorldMeta {

    private static final Pattern HIDDEN = Pattern.compile("<!-- savelogic-meta:([A-Za-z0-9+/=]+) -->");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final Gson GSON = new Gson();

    /** Who uploaded a version, and when the world had last been played before that upload (0 if unknown). */
    record Version(String device, long lastPlayed) {}

    /** Version stamp -> details. */
    final Map<String, Version> versions = new LinkedHashMap<>();
    Lock lock;

    static WorldMeta parse(String body) {
        WorldMeta meta = new WorldMeta();
        Matcher matcher = HIDDEN.matcher(body == null ? "" : body);
        if (!matcher.find()) {
            return meta;
        }
        try {
            JsonObject json = GSON.fromJson(new String(Base64.getDecoder().decode(matcher.group(1)), StandardCharsets.UTF_8), JsonObject.class);
            if (json.has("versions")) {
                json.getAsJsonObject("versions").entrySet().forEach(e -> {
                    if (e.getValue().isJsonPrimitive()) {
                        meta.versions.put(e.getKey(), new Version(e.getValue().getAsString(), 0)); // Beta 5/6 format.
                    } else {
                        JsonObject v = e.getValue().getAsJsonObject();
                        meta.versions.put(e.getKey(), new Version(v.get("device").getAsString(),
                                v.has("lastPlayed") ? v.get("lastPlayed").getAsLong() : 0));
                    }
                });
            }
            if (json.has("lock") && json.get("lock").isJsonObject()) {
                JsonObject lock = json.getAsJsonObject("lock");
                meta.lock = new Lock(lock.get("deviceId").getAsString(), lock.get("device").getAsString(), lock.get("since").getAsLong());
            }
        } catch (RuntimeException e) {
            SaveLogic.LOGGER.warn("Ignoring unreadable SaveLogic data in release notes: {}", e.getMessage());
        }
        return meta;
    }

    /** The release notes: a readable summary for GitHub's Releases page, then the hidden data. */
    static String render(String worldName, List<CloudSave> versions, WorldMeta meta) {
        StringBuilder text = new StringBuilder("Minecraft world **").append(worldName).append("**, uploaded by SaveLogic.\n\n");
        if (meta.lock != null) {
            text.append("**In use on ").append(meta.lock.device()).append("** since ")
                    .append(WHEN.format(Instant.ofEpochMilli(meta.lock.since()))).append(".\n\n");
        }
        text.append("| Version | Last played | Uploaded | Computer | Upload |\n|---|---|---|---|---|\n");
        for (int i = 0; i < versions.size(); i++) {
            CloudSave version = versions.get(i);
            Version details = meta.versions.get(version.stamp());
            text.append("| ").append(i == 0 ? "Latest" : "Older")
                    .append(" | ").append(details != null && details.lastPlayed() > 0
                            ? WHEN.format(Instant.ofEpochMilli(details.lastPlayed())) : "unknown")
                    .append(" | ").append(WHEN.format(version.uploaded()))
                    .append(" | ").append(details != null ? details.device() : "unknown")
                    .append(" | ").append(String.format("%.1f MB", version.size() / (1024.0 * 1024)))
                    .append(version.isFull() ? " (full copy)" : " (changes only)").append(" |\n");
        }
        text.append("\nDon't edit or delete the attached files; load this world from Cloud Saves in Minecraft.\n\n");

        JsonObject json = new JsonObject();
        JsonObject versionJson = new JsonObject();
        meta.versions.forEach((stamp, v) -> {
            JsonObject item = new JsonObject();
            item.addProperty("device", v.device());
            item.addProperty("lastPlayed", v.lastPlayed());
            versionJson.add(stamp, item);
        });
        json.add("versions", versionJson);
        if (meta.lock != null) {
            JsonObject lock = new JsonObject();
            lock.addProperty("deviceId", meta.lock.deviceId());
            lock.addProperty("device", meta.lock.device());
            lock.addProperty("since", meta.lock.since());
            json.add("lock", lock);
        }
        text.append("<!-- savelogic-meta:")
                .append(Base64.getEncoder().encodeToString(GSON.toJson(json).getBytes(StandardCharsets.UTF_8)))
                .append(" -->");
        return text.toString();
    }
}
