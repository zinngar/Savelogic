package com.zinngar.savelogic.util;

import com.zinngar.savelogic.setup.SetupInstaller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Lets Xaero's Minimap waypoints travel with a world. Xaero's keeps them outside the world, in
 * xaero/minimap/&lt;world folder&gt;, so before an upload they are copied into the world folder, and after a download
 * they are copied back under this computer's name for the world. That way they get the world's change-only uploads,
 * version history and conflict prompts. Xaero's reads the files when a world is opened and writes them on every edit.
 * <p>
 * A world with no waypoint files is left out, so a computer without Xaero's (or without waypoints for this world)
 * never replaces another computer's waypoints with nothing.
 */
public final class XaeroWaypoints {

    /** Inside the world folder; Minecraft ignores it. */
    public static final String WORLD_FOLDER = "savelogic-xaero-waypoints";

    private static final DateTimeFormatter BACKUP_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private XaeroWaypoints() {
    }

    /** Copies this world's waypoints into the world folder. Does nothing if there are none here. */
    public static void capture(Path gameDir, Path worldDir) throws IOException {
        Path source = xaeroFolder(gameDir, worldDir.getFileName().toString());
        if (!hasWaypoints(source)) {
            return;
        }
        Path copy = worldDir.resolve(WORLD_FOLDER);
        SetupInstaller.deleteRecursively(copy);
        copyFiles(source, copy);
    }

    /**
     * Puts the waypoints from a downloaded world where Xaero's looks for them. Waypoints here that differ are moved to
     * savelogic-backups/waypoints first. A world uploaded without waypoints leaves those on this computer alone.
     */
    public static void restore(Path gameDir, Path worldDir) throws IOException {
        Path copy = worldDir.resolve(WORLD_FOLDER);
        if (!hasWaypoints(copy)) {
            return;
        }
        Path target = xaeroFolder(gameDir, worldDir.getFileName().toString());
        if (Files.isDirectory(target)) {
            if (hasWaypoints(target) && !sameFiles(target, copy)) {
                copyFiles(target, gameDir.resolve("savelogic-backups").resolve("waypoints")
                        .resolve(target.getFileName() + "_" + LocalDateTime.now().format(BACKUP_STAMP)));
            }
            SetupInstaller.deleteRecursively(target);
        }
        copyFiles(copy, target);
    }

    /** Where Xaero's keeps a singleplayer world's waypoints: the name it writes now, or an older one if that exists. */
    static Path xaeroFolder(Path gameDir, String worldFolder) {
        Path minimap = gameDir.resolve("xaero").resolve("minimap");
        String older = worldFolder.replace("_", "%us%").replace("/", "%fs%").replace("\\", "%bs%");
        Path current = minimap.resolve(older.replace("[", "%lb%").replace("]", "%rb%"));
        return !Files.exists(current) && Files.exists(minimap.resolve(older)) ? minimap.resolve(older) : current;
    }

    /** True if the folder has waypoint files, which are in subfolders (dim%0/...). A lone config.txt doesn't count. */
    private static boolean hasWaypoints(Path folder) throws IOException {
        return files(folder).keySet().stream().anyMatch(name -> name.contains("/"));
    }

    private static boolean sameFiles(Path a, Path b) throws IOException {
        Map<String, Path> filesA = files(a);
        Map<String, Path> filesB = files(b);
        if (!filesA.keySet().equals(filesB.keySet())) {
            return false;
        }
        for (Map.Entry<String, Path> file : filesA.entrySet()) {
            if (Files.mismatch(file.getValue(), filesB.get(file.getKey())) != -1) {
                return false;
            }
        }
        return true;
    }

    private static void copyFiles(Path from, Path to) throws IOException {
        for (Map.Entry<String, Path> file : files(from).entrySet()) {
            Path target = to.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            // Keeping the time means an unchanged file isn't read again when the world is next uploaded.
            Files.copy(file.getValue(), target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    /** The folder's files by relative path, without Xaero's own backups and temporary files. */
    private static Map<String, Path> files(Path folder) throws IOException {
        Map<String, Path> result = new TreeMap<>();
        if (!Files.isDirectory(folder)) {
            return result;
        }
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(folder)) {
            paths = walk.filter(Files::isRegularFile).toList();
        }
        for (Path path : paths) {
            String name = ZipUtil.entryName(folder, path);
            if (!name.startsWith("backup") && !name.contains("/backup") && !name.contains("temp_to_add")) {
                result.put(name, path);
            }
        }
        return result;
    }
}
