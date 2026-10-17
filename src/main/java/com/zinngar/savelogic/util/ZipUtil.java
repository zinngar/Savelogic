package com.zinngar.savelogic.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class ZipUtil {

    /** Zips the contents of worldDir (without a top-level folder) into zipFile. */
    public static void zipWorld(Path worldDir, Path zipFile) throws IOException {
        zipWorld(worldDir, zipFile, Progress.NONE);
    }

    /** Same as {@link #zipWorld(Path, Path)}, reporting how many bytes of world files have been read. */
    public static void zipWorld(Path worldDir, Path zipFile, Progress progress) throws IOException {
        Map<String, Path> entries = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(worldDir)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().equals("session.lock"))
                    .forEach(path -> entries.put(entryName(worldDir, path), path));
        }
        zipFiles(entries, zipFile, "Zipping world", progress);
    }

    /** Zips the given files, keyed by their name inside the zip. */
    public static void zipFiles(Map<String, Path> entries, Path zipFile, String phase, Progress progress) throws IOException {
        long total = 0;
        for (Path path : entries.values()) {
            total += Files.size(path);
        }
        progress.phase(phase, total);
        long done = 0;
        byte[] buffer = new byte[1 << 16];
        try (OutputStream out = Files.newOutputStream(zipFile);
             ZipOutputStream zos = new ZipOutputStream(out)) {
            for (Map.Entry<String, Path> entry : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(entry.getKey()));
                try (InputStream in = Files.newInputStream(entry.getValue())) {
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        zos.write(buffer, 0, read);
                        done += read;
                        progress.bytes(done);
                    }
                }
                zos.closeEntry();
            }
        }
    }

    /** The zip entry name for a file under root. Zip entries must use '/' so Windows zips unzip correctly elsewhere. */
    public static String entryName(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
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
