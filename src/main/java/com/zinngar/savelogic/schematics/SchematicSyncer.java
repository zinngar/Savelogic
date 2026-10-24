package com.zinngar.savelogic.schematics;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.zinngar.savelogic.GitHubStorageProvider;
import com.zinngar.savelogic.GitHubStorageProvider.Device;
import com.zinngar.savelogic.GitHubStorageProvider.SchematicCloud;
import com.zinngar.savelogic.GitHubStorageProvider.SchematicFile;
import com.zinngar.savelogic.mods.ModrinthClient;
import com.zinngar.savelogic.util.ZipUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Two-way sync of a schematics folder with the cloud. Each computer remembers the folder as it was at its last sync
 * (the "base"), which tells a file that is new on one side from one that was deleted on the other:
 * <ul>
 *   <li>new or changed on one side only: copied to the other side;</li>
 *   <li>deleted on one side and unchanged on the other: deleted there too (local files are backed up first);</li>
 *   <li>changed on both sides: both are kept, the cloud's copy saved locally as "name (Computer).ext" and then
 *       uploaded like any new file, so both computers end up with both.</li>
 * </ul>
 */
public final class SchematicSyncer {

    /** File types synced from the schematics folder (Litematica and the common formats it can load). */
    public static final Set<String> EXTENSIONS = Set.of("litematic", "schem", "schematic", "nbt");

    public record Summary(int uploaded, int downloaded, int deleted, int keptBoth) {
        public boolean changedAnything() {
            return uploaded + downloaded + deleted + keptBoth > 0;
        }

        public String describe() {
            List<String> parts = new ArrayList<>();
            if (uploaded > 0) parts.add(uploaded + " uploaded");
            if (downloaded > 0) parts.add(downloaded + " downloaded");
            if (deleted > 0) parts.add(deleted + " deleted");
            if (keptBoth > 0) parts.add(keptBoth + " changed on both computers (kept both)");
            return parts.isEmpty() ? "up to date" : String.join(", ", parts);
        }
    }

    private static final Gson GSON = new Gson();
    private static final DateTimeFormatter BACKUP_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final GitHubStorageProvider github;
    private final Path folder;
    private final Path stateFile;
    private final Path backupRoot;
    private final Device device;

    /**
     * folder is the schematics folder, stateFile where this computer remembers the last sync, backupRoot where
     * deleted local schematics are moved to.
     */
    public SchematicSyncer(GitHubStorageProvider github, Path folder, Path stateFile, Path backupRoot, Device device) {
        this.github = github;
        this.folder = folder;
        this.stateFile = stateFile;
        this.backupRoot = backupRoot;
        this.device = device;
    }

    public Summary sync() throws IOException, InterruptedException {
        Files.createDirectories(folder);
        Map<String, String> local = scanLocal();
        Map<String, String> base = readState();
        SchematicCloud cloud = github.listSchematics();
        Map<String, SchematicFile> remote = new HashMap<>();
        cloud.files().forEach(f -> remote.put(f.path(), f));
        Map<String, String> devices = new LinkedHashMap<>(cloud.devices());

        Long release = cloud.releaseId();
        Map<String, String> newBase = new TreeMap<>();
        int uploaded = 0, downloaded = 0, deleted = 0, keptBoth = 0;
        Path backup = null;

        Set<String> paths = new TreeSet<>();
        paths.addAll(local.keySet());
        paths.addAll(remote.keySet());
        paths.addAll(base.keySet());
        List<String> uploads = new ArrayList<>();

        for (String path : paths) {
            String l = local.get(path);
            SchematicFile r = remote.get(path);
            String c = r == null ? null : r.sha1();
            String b = base.get(path);

            if (l != null && l.equals(c)) {
                newBase.put(path, l); // Same on both sides.
            } else if (l != null && c == null) {
                if (l.equals(b)) {
                    backup = moveToBackup(path, backup); // Deleted on the other computer, unchanged here.
                    devices.remove(path);
                    deleted++;
                } else {
                    uploads.add(path); // New here, or changed here after the other computer deleted it.
                }
            } else if (l == null && c != null) {
                if (c.equals(b)) {
                    github.deleteSchematic(r); // Deleted here, unchanged in the cloud.
                    devices.remove(path);
                    deleted++;
                } else {
                    github.downloadSchematic(r, folder.resolve(path)); // New in the cloud (or changed there).
                    newBase.put(path, c);
                    downloaded++;
                }
            } else if (l != null) {
                if (l.equals(b)) {
                    github.downloadSchematic(r, folder.resolve(path)); // Only the cloud's copy changed.
                    newBase.put(path, c);
                    downloaded++;
                } else if (c.equals(b)) {
                    uploads.add(path); // Only this computer's copy changed.
                } else {
                    // Changed on both computers: keep both. The cloud's copy gets the other computer's name.
                    String copy = keptBothName(path, devices.getOrDefault(path, "other computer"), local.keySet());
                    github.downloadSchematic(r, folder.resolve(copy));
                    newBase.put(path, c); // Treated as "cloud = base", so this computer's copy is uploaded over it...
                    uploads.add(path);
                    uploads.add(copy); // ...and the kept copy goes up as a new file.
                    local.put(copy, sha1(folder.resolve(copy)));
                    keptBoth++;
                }
            }
        }

        if (!uploads.isEmpty()) {
            if (release == null) {
                release = github.ensureSchematicsRelease(cloud);
            }
            for (String path : uploads) {
                String sha = local.get(path);
                SchematicFile old = remote.get(path);
                if (old == null || !old.sha1().equals(sha)) {
                    github.uploadSchematic(release, path, sha, folder.resolve(path));
                    if (old != null) {
                        github.deleteSchematic(old); // Only after the new copy is safely up.
                    }
                    devices.put(path, device.name());
                    uploaded++;
                }
                newBase.put(path, sha);
            }
        }
        if (release != null && (uploaded + deleted + keptBoth > 0)) {
            github.describeSchematics(release, new ArrayList<>(newBase.keySet()), devices);
        }
        writeState(newBase);
        return new Summary(uploaded, downloaded, deleted, keptBoth);
    }

    /** "house.litematic" -> "house (Gaming PC).litematic", numbered if that exists too. */
    static String keptBothName(String path, String computer, Set<String> taken) {
        int slash = path.lastIndexOf('/');
        String dir = slash >= 0 ? path.substring(0, slash + 1) : "";
        String name = path.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        String who = computer.replaceAll("[\\\\/:*?\"<>|]", "_");
        String candidate = dir + stem + " (" + who + ")" + ext;
        for (int i = 2; taken.contains(candidate); i++) {
            candidate = dir + stem + " (" + who + " " + i + ")" + ext;
        }
        return candidate;
    }

    private Map<String, String> scanLocal() throws IOException {
        Map<String, String> result = new TreeMap<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(folder)) {
            files = walk.filter(Files::isRegularFile).filter(SchematicSyncer::isSchematic).toList();
        }
        for (Path file : files) {
            result.put(ZipUtil.entryName(folder, file), sha1(file));
        }
        return result;
    }

    private static boolean isSchematic(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 && EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private Path moveToBackup(String path, Path backup) throws IOException {
        if (backup == null) {
            backup = backupRoot.resolve(LocalDateTime.now().format(BACKUP_STAMP));
        }
        Path target = backup.resolve(path);
        Files.createDirectories(target.getParent());
        Files.move(folder.resolve(path), target, StandardCopyOption.REPLACE_EXISTING);
        return backup;
    }

    private Map<String, String> readState() {
        Map<String, String> state = new TreeMap<>();
        try {
            if (Files.isRegularFile(stateFile)) {
                JsonObject json = GSON.fromJson(Files.readString(stateFile), JsonObject.class);
                json.entrySet().forEach(e -> state.put(e.getKey(), e.getValue().getAsString()));
            }
        } catch (Exception e) {
            // A missing or unreadable state just means "first sync": nothing is deleted, everything is merged.
        }
        return state;
    }

    private void writeState(Map<String, String> state) throws IOException {
        JsonObject json = new JsonObject();
        state.forEach(json::addProperty);
        Files.createDirectories(stateFile.getParent());
        Files.writeString(stateFile, GSON.toJson(json), StandardCharsets.UTF_8);
    }

    private static String sha1(Path file) throws IOException {
        return ModrinthClient.sha1Of(file);
    }
}
