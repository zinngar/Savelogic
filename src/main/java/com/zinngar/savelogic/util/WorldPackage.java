package com.zinngar.savelogic.util;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * One uploaded version of a world, as a zip: either the whole world ("full"), or only the files that changed since
 * a parent version plus a list of deleted files ("changes"). Every package also carries the manifest of the whole
 * world as it is after this version, so a computer can tell what to send next time without rereading the world.
 */
public final class WorldPackage {

    public static final String MANIFEST_ENTRY = "savelogic-manifest.json";
    public static final String DELTA_ENTRY = "savelogic-delta.json";
    private static final Gson GSON = new Gson();

    /** parent is null for a full package; manifest is null for packages made before manifests existed. */
    public record Info(String parent, List<String> deleted, WorldManifest manifest) {
        public boolean isFull() {
            return parent == null;
        }
    }

    private WorldPackage() {
    }

    /** Writes the whole world. */
    public static void writeFull(Path worldDir, Path zip, WorldManifest manifest, Progress progress) throws IOException {
        write(worldDir, zip, manifest, new ArrayList<>(manifest.files().keySet()), null, List.of(), "Zipping world", progress);
    }

    /** Writes only what changed since parent. */
    public static void writeChanges(Path worldDir, Path zip, WorldManifest manifest, WorldManifest.Changes changes,
                                    String parent, Progress progress) throws IOException {
        write(worldDir, zip, manifest, changes.changed(), parent, changes.deleted(), "Zipping changes", progress);
    }

    private static void write(Path worldDir, Path zip, WorldManifest manifest, List<String> files, String parent,
                              List<String> deleted, String phase, Progress progress) throws IOException {
        Path header = Files.createTempFile("savelogic-header", ".json");
        Path delta = parent == null ? null : Files.createTempFile("savelogic-delta", ".json");
        try {
            Map<String, Path> entries = new LinkedHashMap<>();
            for (String name : files) {
                entries.put(name, worldDir.resolve(name));
            }
            Files.writeString(header, manifest.toJsonString(), StandardCharsets.UTF_8);
            entries.put(MANIFEST_ENTRY, header);
            if (parent != null) {
                JsonObject json = new JsonObject();
                json.addProperty("parent", parent);
                JsonArray gone = new JsonArray();
                deleted.forEach(gone::add);
                json.add("deleted", gone);
                Files.writeString(delta, GSON.toJson(json), StandardCharsets.UTF_8);
                entries.put(DELTA_ENTRY, delta);
            }
            ZipUtil.zipFiles(entries, zip, phase, progress);
        } finally {
            Files.deleteIfExists(header);
            if (delta != null) {
                Files.deleteIfExists(delta);
            }
        }
    }

    /** Reads a package's header without extracting it. */
    public static Info read(Path zip) throws IOException {
        try (ZipFile file = new ZipFile(zip.toFile())) {
            WorldManifest manifest = null;
            ZipEntry manifestEntry = file.getEntry(MANIFEST_ENTRY);
            if (manifestEntry != null) {
                manifest = WorldManifest.fromJsonString(readText(file, manifestEntry));
            }
            ZipEntry deltaEntry = file.getEntry(DELTA_ENTRY);
            if (deltaEntry == null) {
                return new Info(null, List.of(), manifest);
            }
            JsonObject json = GSON.fromJson(readText(file, deltaEntry), JsonObject.class);
            List<String> deleted = new ArrayList<>();
            json.getAsJsonArray("deleted").forEach(e -> deleted.add(e.getAsString()));
            return new Info(json.get("parent").getAsString(), deleted, manifest);
        }
    }

    /**
     * Applies a package onto worldDir: extracts its files (over any existing ones) and, for a changes package,
     * deletes the files it lists as deleted. The package's own header files are not written into the world.
     */
    public static Info apply(Path zip, Path worldDir) throws IOException {
        Info info = read(zip);
        ZipUtil.unzip(zip, worldDir);
        Files.deleteIfExists(worldDir.resolve(MANIFEST_ENTRY));
        Files.deleteIfExists(worldDir.resolve(DELTA_ENTRY));
        Path root = worldDir.toAbsolutePath().normalize();
        for (String name : info.deleted()) {
            Path target = root.resolve(name).normalize();
            if (target.startsWith(root) && !target.equals(root)) {
                Files.deleteIfExists(target);
            }
        }
        return info;
    }

    private static String readText(ZipFile file, ZipEntry entry) throws IOException {
        try (InputStream in = file.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
