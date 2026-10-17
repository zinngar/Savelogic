package com.zinngar.savelogic.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class ZipUtil {

    /** Zips the contents of worldDir (without a top-level folder) into zipFile. */
    public static void zipWorld(Path worldDir, Path zipFile) throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(worldDir)) {
            files = walk.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().equals("session.lock"))
                    .toList();
        }
        try (OutputStream out = Files.newOutputStream(zipFile);
             ZipOutputStream zos = new ZipOutputStream(out)) {
            for (Path path : files) {
                // Zip entries must use '/' so worlds zipped on Windows unzip correctly elsewhere.
                String name = worldDir.relativize(path).toString().replace('\\', '/');
                zos.putNextEntry(new ZipEntry(name));
                Files.copy(path, zos);
                zos.closeEntry();
            }
        }
    }

    /** Extracts zipFile into destDir, refusing entries that would escape it. */
    public static void unzip(Path zipFile, Path destDir) throws IOException {
        Path root = destDir.toAbsolutePath().normalize();
        Files.createDirectories(root);
        try (InputStream in = Files.newInputStream(zipFile);
             ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path target = root.resolve(entry.getName()).normalize();
                if (!target.startsWith(root) || target.equals(root)) {
                    throw new IOException("Zip entry is outside of the target folder: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
