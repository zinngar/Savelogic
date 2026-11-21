package com.zinngar.savelogic.backup;

import com.zinngar.savelogic.SaveLogic;
import com.zinngar.savelogic.util.ZipUtil;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class AutoBackupManager {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    /** Zips the world into .minecraft/savelogic-backups/<world>_<timestamp>.zip. */
    public static void backupWorld(Path worldDir) {
        String worldName = worldDir.getFileName().toString();
        Path backupDir = backupDir();
        Path zipPath = backupDir.resolve(worldName + "_" + LocalDateTime.now().format(FORMAT) + ".zip");

        try {
            Files.createDirectories(backupDir);
            ZipUtil.zipWorld(worldDir, zipPath);
            SaveLogic.LOGGER.info("Auto-backup created: {}", zipPath);
        } catch (IOException e) {
            SaveLogic.LOGGER.error("Failed to create backup of {}", worldDir, e);
        }
        prune();
    }

    /** Removes backups beyond the number kept (see {@link BackupPruner}), off the render thread. */
    public static void pruneInBackground() {
        Thread thread = new Thread(AutoBackupManager::prune, "SaveLogic backup cleanup");
        thread.setDaemon(true);
        thread.start();
    }

    private static synchronized void prune() {
        BackupPruner.Result result = BackupPruner.prune(backupDir(), SaveLogic.CONFIG.keepBackups);
        if (result.removed() > 0) {
            SaveLogic.LOGGER.info("Removed {} old backup(s) from savelogic-backups, freeing {}", result.removed(),
                    BackupPruner.describe(result.freedBytes()));
        }
    }

    public static Path backupDir() {
        return FabricLoader.getInstance().getGameDir().resolve("savelogic-backups");
    }
}
