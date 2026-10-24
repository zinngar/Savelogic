package com.zinngar.savelogic.util;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * A fingerprint of every file in a world: size, modification time and SHA-1, keyed by the path inside the world
 * ("region/r.0.0.mca"). Comparing two manifests tells which files changed, so an upload can send only those.
 * Rescanning reuses the previous hash for files whose size and modification time are unchanged, so it only reads
 * files that were actually written since the last sync.
 */
public final class WorldManifest {

    public record Entry(long size, long modified, String sha1) {}

    /** What changed between an old and a new manifest. */
    public record Changes(List<String> changed, List<String> deleted, long changedBytes, long totalBytes) {
        public boolean isEmpty() {
            return changed.isEmpty() && deleted.isEmpty();
        }
    }

    private static final Gson GSON = new Gson();

    private final Map<String, Entry> files;

    public WorldManifest(Map<String, Entry> files) {
        this.files = new TreeMap<>(files);
    }

    public Map<String, Entry> files() {
        return files;
    }

    /** Scans worldDir (leaving out {@link ZipUtil#EXCLUDED_WORLD_FILES}), reusing hashes from previous where possible. */
    public static WorldManifest scan(Path worldDir, WorldManifest previous, Progress progress) throws IOException {
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(worldDir)) {
            paths = walk.filter(Files::isRegularFile)
                    .filter(path -> !ZipUtil.EXCLUDED_WORLD_FILES.contains(path.getFileName().toString()))
                    .toList();
        }
        // Only files that need hashing count towards progress.
        List<Path> toHash = new ArrayList<>();
        Map<String, Entry> result = new TreeMap<>();
        for (Path path : paths) {
            String name = ZipUtil.entryName(worldDir, path);
            long size = Files.size(path);
            long modified = Files.getLastModifiedTime(path).toMillis();
            Entry old = previous == null ? null : previous.files.get(name);
            if (old != null && old.size() == size && old.modified() == modified) {
                result.put(name, old);
            } else {
                toHash.add(path);
            }
        }
        long total = 0;
        for (Path path : toHash) {
            total += Files.size(path);
        }
        progress.phase("Checking what changed", total);
        long done = 0;
        byte[] buffer = new byte[1 << 16];
        for (Path path : toHash) {
            MessageDigest digest = sha1();
            try (InputStream in = Files.newInputStream(path)) {
                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                    done += read;
                    progress.bytes(done);
                }
            }
            result.put(ZipUtil.entryName(worldDir, path), new Entry(Files.size(path),
                    Files.getLastModifiedTime(path).toMillis(), HexFormat.of().formatHex(digest.digest())));
        }
        return new WorldManifest(result);
    }

    /** The files in this manifest that are new or different from base, and the files of base that are gone. */
    public Changes changesSince(WorldManifest base) {
        List<String> changed = new ArrayList<>();
        long changedBytes = 0;
        long totalBytes = 0;
        for (Map.Entry<String, Entry> file : files.entrySet()) {
            totalBytes += file.getValue().size();
            Entry old = base.files.get(file.getKey());
            if (old == null || !old.sha1().equals(file.getValue().sha1())) {
                changed.add(file.getKey());
                changedBytes += file.getValue().size();
            }
        }
        List<String> deleted = base.files.keySet().stream().filter(name -> !files.containsKey(name)).toList();
        return new Changes(changed, deleted, changedBytes, totalBytes);
    }

    /** Same files and contents, with modification times refreshed from disk (after files were written there). */
    public WorldManifest withTimesFrom(Path worldDir) throws IOException {
        Map<String, Entry> result = new TreeMap<>();
        for (Map.Entry<String, Entry> file : files.entrySet()) {
            Path path = worldDir.resolve(file.getKey());
            if (Files.isRegularFile(path)) {
                result.put(file.getKey(), new Entry(file.getValue().size(), Files.getLastModifiedTime(path).toMillis(),
                        file.getValue().sha1()));
            }
        }
        return new WorldManifest(result);
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        files.forEach((name, entry) -> {
            JsonArray value = new JsonArray();
            value.add(entry.size());
            value.add(entry.modified());
            value.add(entry.sha1());
            json.add(name, value);
        });
        return json;
    }

    public static WorldManifest fromJson(JsonObject json) {
        Map<String, Entry> files = new TreeMap<>();
        json.entrySet().forEach(e -> {
            JsonArray value = e.getValue().getAsJsonArray();
            files.put(e.getKey(), new Entry(value.get(0).getAsLong(), value.get(1).getAsLong(), value.get(2).getAsString()));
        });
        return new WorldManifest(files);
    }

    public String toJsonString() {
        return GSON.toJson(toJson());
    }

    public static WorldManifest fromJsonString(String text) {
        return fromJson(GSON.fromJson(text, JsonObject.class));
    }

    private static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
