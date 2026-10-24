package com.zinngar.savelogic.mods;

import com.zinngar.savelogic.mods.ModrinthClient.Version;
import com.zinngar.savelogic.util.Progress;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/** Works out, for every jar in a mods folder, whether Modrinth has a newer build for this Minecraft version. */
public final class ModUpdatePlanner {

    public enum Kind {
        /** A newer build exists for this Minecraft version and loader. */
        UPDATE,
        UP_TO_DATE,
        /** The mod is on Modrinth, but has no build for this Minecraft version and loader. */
        NO_BUILD,
        /** Modrinth doesn't know this jar (e.g. a CurseForge-only or self-built mod), so it can't be checked. */
        UNKNOWN
    }

    public record InstalledMod(Path jar, String fileName, String sha1) {}

    /** One installed jar. current/latest are null when unknown; title falls back to the file name. */
    public record ModStatus(InstalledMod installed, String title, Version current, Version latest, Kind kind) {}

    /** A library that an update needs and that isn't installed; it is added along with the update. */
    public record Dependency(String title, Version version, String neededBy) {}

    public record Plan(List<ModStatus> mods, List<Dependency> dependencies) {
        public List<ModStatus> updates() {
            return mods.stream().filter(m -> m.kind() == Kind.UPDATE).toList();
        }
    }

    private ModUpdatePlanner() {
    }

    /** The jars in modsDir, minus those named in skip (SaveLogic itself), hashed. */
    public static List<InstalledMod> scan(Path modsDir, Set<String> skip, Progress progress) throws IOException {
        List<Path> jars;
        if (!Files.isDirectory(modsDir)) {
            return List.of();
        }
        try (Stream<Path> list = Files.list(modsDir)) {
            jars = list.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .filter(p -> !skip.contains(p.getFileName().toString()))
                    .sorted().toList();
        }
        progress.phase("Reading your mods", 0);
        List<InstalledMod> mods = new ArrayList<>();
        for (Path jar : jars) {
            mods.add(new InstalledMod(jar, jar.getFileName().toString(), ModrinthClient.sha1Of(jar)));
        }
        return mods;
    }

    public static Plan plan(List<InstalledMod> installed, ModrinthClient modrinth, String gameVersion, String loader)
            throws IOException, InterruptedException {
        List<String> hashes = installed.stream().map(InstalledMod::sha1).toList();
        Map<String, Version> current = modrinth.versionsByHash(hashes);
        Map<String, Version> latest = current.isEmpty() ? Map.of()
                : modrinth.latestByHash(new ArrayList<>(current.keySet()), gameVersion, loader);

        Set<String> installedProjects = new HashSet<>();
        current.values().forEach(v -> installedProjects.add(v.projectId()));

        List<ModStatus> statuses = new ArrayList<>();
        Map<String, Dependency> dependencies = new LinkedHashMap<>();
        Set<String> projectIds = new HashSet<>(installedProjects);
        for (InstalledMod mod : installed) {
            Version have = current.get(mod.sha1());
            Version newest = latest.get(mod.sha1());
            Kind kind;
            if (have == null) {
                kind = Kind.UNKNOWN;
            } else if (newest == null) {
                kind = Kind.NO_BUILD;
            } else if (newest.sha1().equals(mod.sha1()) || newest.id().equals(have.id())
                    || !newest.published().isAfter(have.published())) {
                kind = Kind.UP_TO_DATE; // Same build, or Modrinth's pick isn't actually newer than what's installed.
            } else {
                kind = Kind.UPDATE;
            }
            statuses.add(new ModStatus(mod, null, have, kind == Kind.UPDATE ? newest : null, kind));
            if (kind == Kind.UPDATE) {
                for (String required : newest.requiredProjects()) {
                    if (!installedProjects.contains(required) && !dependencies.containsKey(required)) {
                        Optional<Version> library = modrinth.latestForProject(required, gameVersion, loader);
                        if (library.isPresent()) {
                            dependencies.put(required, new Dependency(null, library.get(), have.projectId()));
                            projectIds.add(required);
                        }
                    }
                }
            }
        }

        Map<String, String> titles = modrinth.projectTitles(projectIds);
        List<ModStatus> named = new ArrayList<>();
        for (ModStatus status : statuses) {
            String title = status.current() != null
                    ? titles.getOrDefault(status.current().projectId(), status.installed().fileName())
                    : status.installed().fileName();
            named.add(new ModStatus(status.installed(), title, status.current(), status.latest(), status.kind()));
        }
        named.sort(Comparator.comparing((ModStatus s) -> s.kind().ordinal())
                .thenComparing(s -> s.title(), String.CASE_INSENSITIVE_ORDER));
        List<Dependency> namedDependencies = dependencies.values().stream()
                .map(d -> new Dependency(titles.getOrDefault(d.version().projectId(), d.version().fileName()), d.version(),
                        titles.getOrDefault(d.neededBy(), d.neededBy())))
                .toList();
        return new Plan(named, namedDependencies);
    }
}
