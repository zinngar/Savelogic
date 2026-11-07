package com.zinngar.savelogic.setup;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Installs a downloaded setup (mods + configs) once Minecraft has closed, because a running game keeps its
 * mod jars open (and locked on Windows). Runs as a separate Java process started by the mod at exit, so it
 * only uses the JDK.
 * <p>
 * Current mods are moved, and replaced configs copied, into {@code savelogic-backups/setup-before-<time>}
 * first, so nothing is lost. Config files that the setup doesn't have are left alone.
 */
public final class SetupInstaller {

    public static final String PENDING_DIR = "savelogic-pending-setup";
    /** Holds a new version of SaveLogic itself, separately because a setup never touches SaveLogic's own jar. */
    public static final String PENDING_UPDATE_DIR = "savelogic-pending-update";
    /** SaveLogic's own files in the config folder, which hold the GitHub token; never synced. */
    public static final Set<String> PRIVATE_CONFIG_FILES = Set.of("savelogic.json", "savelogic.key");

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private SetupInstaller() {
    }

    /** Arguments: game directory, process id of Minecraft to wait for, then file names of jars to keep. */
    public static void main(String[] args) throws Exception {
        Path gameDir = Path.of(args[0]);
        long parentPid = Long.parseLong(args[1]);
        Set<String> keep = Set.copyOf(List.of(args).subList(2, args.length));

        Path log = gameDir.resolve("logs").resolve("savelogic-setup-install.log");
        Files.createDirectories(log.getParent());
        try (PrintStream out = new PrintStream(Files.newOutputStream(log), true)) {
            out.println("Waiting for Minecraft (pid " + parentPid + ") to close...");
            ProcessHandle.of(parentPid).ifPresent(handle -> handle.onExit().join());
            try {
                if (Files.exists(gameDir.resolve(PENDING_DIR))) {
                    Path backup = install(gameDir, keep, out);
                    out.println(backup == null ? "Nothing to install." : "Done. Previous mods and configs are in " + backup);
                }
            } catch (Exception e) {
                out.println("Install failed: " + e);
                e.printStackTrace(out);
            }
            try {
                Path backup = installUpdate(gameDir, keep, out);
                if (backup != null) {
                    out.println("Done. The previous SaveLogic is in " + backup);
                }
            } catch (Exception e) {
                out.println("SaveLogic update failed, so the current version stays. It will be tried again next time: " + e);
                e.printStackTrace(out);
            }
        }
    }

    /**
     * Installs the pending setup in gameDir. Returns the backup folder, or null if no setup was pending.
     * Jars named in keep (SaveLogic itself) are never moved or overwritten.
     */
    public static Path install(Path gameDir, Set<String> keep, PrintStream out) throws IOException, InterruptedException {
        Path pending = gameDir.resolve(PENDING_DIR);
        if (!hasMods(pending)) {
            // Never strip the mods folder for a setup with nothing in it.
            out.println("The pending setup has no mods; nothing was changed.");
            deleteRecursively(pending);
            return null;
        }
        Path mods = gameDir.resolve("mods");
        Path config = gameDir.resolve("config");
        Path backup = gameDir.resolve("savelogic-backups").resolve("setup-before-" + LocalDateTime.now().format(STAMP));
        Files.createDirectories(backup.resolve("mods"));
        Files.createDirectories(mods);

        for (Path jar : jars(mods)) {
            if (!keep.contains(jar.getFileName().toString())) {
                retry(() -> Files.move(jar, backup.resolve("mods").resolve(jar.getFileName())));
                out.println("Backed up mod " + jar.getFileName());
            }
        }
        for (Path jar : jars(pending.resolve("mods"))) {
            String name = jar.getFileName().toString();
            if (!keep.contains(name)) {
                Files.copy(jar, mods.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                out.println("Installed mod " + name);
            }
        }

        Path pendingConfig = pending.resolve("config");
        if (Files.isDirectory(pendingConfig)) {
            List<Path> files;
            try (Stream<Path> walk = Files.walk(pendingConfig)) {
                files = walk.filter(Files::isRegularFile).toList();
            }
            int installed = 0;
            for (Path file : files) {
                Path relative = pendingConfig.relativize(file);
                if (relative.getNameCount() == 1 && PRIVATE_CONFIG_FILES.contains(relative.toString())) {
                    continue;
                }
                installed++;
                Path target = config.resolve(relative.toString());
                if (Files.exists(target)) {
                    Path saved = backup.resolve("config").resolve(relative.toString());
                    Files.createDirectories(saved.getParent());
                    Files.copy(target, saved, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.createDirectories(target.getParent());
                retry(() -> Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING));
            }
            out.println("Installed " + installed + " config file(s)");
        }

        deleteRecursively(pending);
        return backup;
    }

    /**
     * Replaces SaveLogic's own jar with the one waiting in the update folder. Returns the backup folder, or null if no
     * update was waiting. If anything goes wrong the old jar is put back, so SaveLogic is never left missing or twice.
     */
    public static Path installUpdate(Path gameDir, Set<String> ownJars, PrintStream out) throws IOException, InterruptedException {
        Path pending = gameDir.resolve(PENDING_UPDATE_DIR);
        List<Path> waiting = jars(pending);
        if (waiting.isEmpty()) {
            return null;
        }
        Path mods = gameDir.resolve("mods");
        List<Path> current = ownJars.stream().map(mods::resolve).filter(Files::isRegularFile).toList();
        if (current.isEmpty()) {
            // Without the old jar to replace, adding this one could leave two copies of SaveLogic.
            out.println("Couldn't find the current SaveLogic jar in the mods folder; the update wasn't installed.");
            deleteRecursively(pending);
            return null;
        }
        Path update = waiting.get(0);
        String name = update.getFileName().toString();
        Path backup = gameDir.resolve("savelogic-backups").resolve("savelogic-before-" + LocalDateTime.now().format(STAMP));
        Files.createDirectories(backup);
        // Fabric only loads .jar files, so this copy is ignored until it's complete and the old jar is out of the way.
        Path part = mods.resolve(name + ".part");
        Files.copy(update, part, StandardCopyOption.REPLACE_EXISTING);
        List<Path> moved = new ArrayList<>();
        try {
            for (Path jar : current) {
                retry(() -> Files.move(jar, backup.resolve(jar.getFileName())));
                moved.add(jar);
                out.println("Backed up " + jar.getFileName());
            }
            retry(() -> Files.move(part, mods.resolve(name), StandardCopyOption.REPLACE_EXISTING));
        } catch (IOException | InterruptedException e) {
            for (Path jar : moved) {
                Files.move(backup.resolve(jar.getFileName()), jar, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.deleteIfExists(part);
            throw e;
        }
        out.println("Installed " + name);
        deleteRecursively(pending);
        return backup;
    }

    /** True if dir/mods contains at least one jar, i.e. dir holds a usable setup. */
    public static boolean hasMods(Path dir) throws IOException {
        return !jars(dir.resolve("mods")).isEmpty();
    }

    private static List<Path> jars(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> list = Files.list(dir)) {
            return list.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .toList();
        }
    }

    public static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private interface IoAction {
        void run() throws IOException;
    }

    /** Windows can keep a file locked for a moment after the process that used it exits, so retry briefly. */
    private static void retry(IoAction action) throws IOException, InterruptedException {
        for (int attempt = 1; ; attempt++) {
            try {
                action.run();
                return;
            } catch (IOException e) {
                if (attempt >= 20) {
                    throw e;
                }
                Thread.sleep(500);
            }
        }
    }
}
