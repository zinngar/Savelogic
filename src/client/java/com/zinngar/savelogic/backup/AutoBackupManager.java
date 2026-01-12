package com.zinngar.savelogic.backup;

import com.zinngar.savelogic.CloudStorageProvider;
import com.zinngar.savelogic.SaveLogic;
import com.zinngar.savelogic.client.CloudStorageManager;
import com.zinngar.savelogic.client.util.WorldSaveUtils;

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
            SaveLogic.LOGGER.info("Auto-backup created locally: {}", zipPath);

            CloudStorageProvider provider = CloudStorageManager.getProvider();
            if (provider != null) {
                SaveLogic.LOGGER.info("Uploading auto-backup to {}...", provider.getProviderName());
                provider.uploadSave(zipPath.toFile())
                    .thenRun(() -> {
                        SaveLogic.LOGGER.info("Successfully uploaded backup to the cloud.");
                        if (!SaveLogic.CONFIG.keepLocalBackups) {
                            try {
                                Files.delete(zipPath);
                                SaveLogic.LOGGER.info("Deleted local backup file: {}", zipPath);
                            } catch (IOException e) {
                                SaveLogic.LOGGER.error("Failed to delete local backup file: " + zipPath, e);
                            }
                        }
                    })
                    .exceptionally(ex -> {
                        SaveLogic.LOGGER.error("Failed to upload backup to the cloud.", ex);
                        return null;
                    });
            } else {
                SaveLogic.LOGGER.warn("No cloud provider configured. Backup will only be stored locally.");
            }
        } catch (IOException e) {
            SaveLogic.LOGGER.error("Failed to create local backup", e);
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
