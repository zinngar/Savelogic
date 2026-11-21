package com.zinngar.savelogic.backup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Keeps savelogic-backups from growing forever. Every kind of backup SaveLogic makes is named with the time it was
 * made, so for each world (or each kind, for mods and SaveLogic's own jar) only the newest few are kept:
 * <ul>
 *     <li>{@code <world>_<time>.zip}: local backups of a world, made when leaving it</li>
 *     <li>{@code setup-before-<time>}: the mods (and replaced configs) from before an install or mod update</li>
 *     <li>{@code savelogic-before-<time>}: SaveLogic's jar from before it updated itself</li>
 *     <li>{@code worlds/<world>_<time>}: a world's local copy, replaced by a download</li>
 *     <li>{@code waypoints/<world>_<time>} and {@code schematics/<time>}: files replaced or deleted by a sync</li>
 * </ul>
 * Anything not named like that (for example a folder the player made) is never touched.
 */
public final class BackupPruner {

    public record Result(int removed, long freedBytes) {}

    private static final String TIME = "(\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2})";
    private static final Pattern WORLD_ZIP = Pattern.compile("(.+)_" + TIME + "\\.zip");
    private static final Pattern SETUP = Pattern.compile("()setup-before-" + TIME);
    private static final Pattern SELF = Pattern.compile("()savelogic-before-" + TIME);
    private static final Pattern NAMED = Pattern.compile("(.+)_" + TIME);
    private static final Pattern TIME_ONLY = Pattern.compile("()" + TIME);

    private BackupPruner() {
    }

    /** Deletes all but the newest keep backups of each world and kind; keep 0 or less keeps everything. */
    public static Result prune(Path backups, int keep) {
        if (keep <= 0 || !Files.isDirectory(backups)) {
            return new Result(0, 0);
        }
        Map<String, List<Entry>> groups = new LinkedHashMap<>();
        collect(backups, WORLD_ZIP, "zip:", groups);
        collect(backups, SETUP, "setup", groups);
        collect(backups, SELF, "savelogic", groups);
        collect(backups.resolve("worlds"), NAMED, "world:", groups);
        collect(backups.resolve("waypoints"), NAMED, "waypoints:", groups);
        collect(backups.resolve("schematics"), TIME_ONLY, "schematics", groups);

        int removed = 0;
        long freed = 0;
        for (List<Entry> group : groups.values()) {
            group.sort(Comparator.comparing(Entry::time).reversed()); // The time format sorts as text.
            for (Entry old : group.subList(Math.min(keep, group.size()), group.size())) {
                long size = sizeOf(old.path());
                try {
                    deleteTree(old.path());
                    removed++;
                    freed += size;
                } catch (IOException e) {
                    // In use or locked; try again next time.
                }
            }
        }
        return new Result(removed, freed);
    }

    /** Total size of everything in the folder, in bytes. */
    public static long sizeOf(Path path) {
        if (!Files.exists(path)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    /** For example "850 MB" or "4.2 GB". */
    public static String describe(long bytes) {
        if (bytes >= 1L << 30) {
            return String.format("%.1f GB", bytes / (double) (1L << 30));
        }
        return Math.max(0, bytes >> 20) + " MB";
    }

    private record Entry(Path path, String time) {}

    private static void collect(Path dir, Pattern pattern, String kind, Map<String, List<Entry>> groups) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> list = Files.list(dir)) {
            for (Path path : list.toList()) {
                Matcher m = pattern.matcher(path.getFileName().toString());
                // World zips are files; every other kind is a folder.
                if (m.matches() && (pattern == WORLD_ZIP ? Files.isRegularFile(path) : Files.isDirectory(path))) {
                    groups.computeIfAbsent(kind + m.group(1), k -> new ArrayList<>()).add(new Entry(path, m.group(2)));
                }
            }
        } catch (IOException e) {
            // Nothing to prune here.
        }
    }

    private static void deleteTree(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            List<Path> inside;
            try (Stream<Path> walk = Files.walk(path)) {
                inside = walk.sorted(Comparator.reverseOrder()).toList();
            }
            for (Path p : inside) {
                Files.deleteIfExists(p);
            }
        } else {
            Files.deleteIfExists(path);
        }
    }
}
