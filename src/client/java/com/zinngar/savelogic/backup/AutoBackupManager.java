package com.zinngar.savelogic.backup;

import com.zinngar.savelogic.SaveLogic;
import com.zinngar.savelogic.client.util.WorldSaveUtils;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class AutoBackupManager {

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    public static void backupCurrentWorld() {
        Path worldDir = WorldSaveUtils.getCurrentWorldSavePath();

        if (worldDir == null) {
            SaveLogic.LOGGER.info("No world loaded, skipping backup.");
            return;
        }

        String worldName = worldDir.getFileName().toString();
        String timestamp = LocalDateTime.now().format(FORMAT);

        Path backupDir = Paths.get("savelogic-backups");
        Path zipPath = backupDir.resolve(worldName + "_" + timestamp + ".zip");

        try {
            Files.createDirectories(backupDir);
            zipDirectory(worldDir, zipPath);
            SaveLogic.LOGGER.info("Auto-backup created: {}", zipPath);
        } catch (IOException e) {
            SaveLogic.LOGGER.error("Failed to create backup", e);
        }
    }

    private static void zipDirectory(Path source, Path zipFile) throws IOException {
        try (ZipOutputStream zs = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            Files.walk(source).forEach(path -> {
                try {
                    if (Files.isDirectory(path)) return;

                    if (path.getFileName().toString().equals("session.lock")) return;

                    ZipEntry zipEntry = new ZipEntry(source.relativize(path).toString());
                    zs.putNextEntry(zipEntry);
                    Files.copy(path, zs);
                    zs.closeEntry();
                } catch (IOException e) {
                    SaveLogic.LOGGER.error("Failed to add file to zip: " + path, e);
                }
            });
        }
    }
}
