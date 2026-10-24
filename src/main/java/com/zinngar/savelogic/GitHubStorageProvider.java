package com.zinngar.savelogic;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.zinngar.savelogic.util.Progress;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stores each world as its own GitHub release in a private repository: the release is named after the world
 * (tag {@code savelogic-<world key>}) and holds the world zip as release assets.
 * <p>
 * Release assets can be up to 2 GB each and, unlike files committed to the repo, deleting one actually frees
 * the space. Worlds are split into parts of {@link #PART_SIZE} so any size fits. Each upload is named
 * {@code <world key>.<timestamp>.<part>-of-<count>.zip}; the new upload is completed before the previous one
 * is deleted, so a failed upload never removes the existing cloud copy.
 * <p>
 * Worlds keep their last few versions. Which computer uploaded each version, and which computer currently has
 * the world open (the "lock"), are kept in the release notes as hidden metadata ({@link WorldMeta}). Versions are
 * ordered by GitHub's upload time, so computers with different clocks or time zones agree on which is newest.
 * <p>
 * A "setup" (the mods and config folders) is stored the same way in a release tagged
 * {@code savelogicsetup-<Minecraft version>}, which deliberately does not start with the world prefix.
 */
public class GitHubStorageProvider {

    private static final String TAG_PREFIX = "savelogic-";
    /** Early 2.0.0 builds kept every world in one shared release with this tag; it is still read and cleaned up. */
    private static final String SHARED_RELEASE_TAG = "savelogic-saves";
    private static final String SETUP_TAG_PREFIX = "savelogicsetup-";
    public static final long PART_SIZE = 1024L * 1024 * 1024; // 1 GB, well under GitHub's 2 GB asset limit

    // Stamps were whole seconds (14 digits) before versions were kept; now milliseconds (17), so two uploads in the
    // same second (two computers, or a quick re-upload) can't collide.
    // A "changes" upload (only the files that changed since its parent version) adds ".p<parent stamp>" before ".zip",
    // so the chain can always be rebuilt from the asset names alone.
    private static final Pattern ASSET_NAME =
            Pattern.compile("^(.+)\\.(\\d{14}|\\d{17})\\.(\\d+)-of-(\\d+)(?:\\.p(\\d{14}|\\d{17}))?\\.zip$");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final Duration TRANSFER_TIMEOUT = Duration.ofHours(2);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private static final Gson GSON = new Gson();

    /** One uploaded version of a world (or setup), made of one or more parts. device is "" when unknown. */
    /**
     * One uploaded version of a world (or setup), made of one or more parts. device is "" and lastPlayed 0 when
     * unknown. parent is null for a full copy, or the stamp of the version a "changes" upload builds on.
     */
    public record CloudSave(String worldName, String key, String stamp, List<Asset> parts, Instant uploaded, String device,
                            String parent, long lastPlayed) {
        public long size() {
            return parts.stream().mapToLong(Asset::size).sum();
        }

        public boolean isFull() {
            return parent == null;
        }

        /** When the world was last played before this upload, or the upload time if that isn't known. */
        public Instant played() {
            return lastPlayed > 0 ? Instant.ofEpochMilli(lastPlayed) : uploaded;
        }

        CloudSave withMeta(WorldMeta.Version meta) {
            return meta == null ? this
                    : new CloudSave(worldName, key, stamp, parts, uploaded, meta.device(), parent, meta.lastPlayed());
        }
    }

    /** The files to apply, oldest first, to get a version: from its full base, or from a version already present. */
    public record Chain(List<CloudSave> versions, boolean fromHave) {}

    /** A world in the cloud: its versions (newest first) and who has it open, if anyone. */
    public record CloudWorld(String worldName, String key, List<CloudSave> versions, Lock lock) {
        public CloudSave newest() {
            return versions.get(0);
        }
    }

    /** A world being open on a computer. since is epoch milliseconds. */
    public record Lock(String deviceId, String device, long since) {}

    /** This computer: a stable random id plus the name shown to the user. */
    public record Device(String id, String name) {}

    /** label is the original world folder name, which GitHub keeps unaltered (asset names get rewritten). */
    public record Asset(long id, String name, long size, String label, Instant created) {}

    private record Release(long id, String tag, String name, String body, List<Asset> assets) {}

    private final String owner;
    private final String repo;
    private final String token;
    private final String apiBase;
    private final String uploadsBase;

    public GitHubStorageProvider(String repositoryUrl, String token) {
        // The system property only exists for SaveLogic's own tests, which run against a local fake GitHub.
        this(repositoryUrl, token, System.getProperty("savelogic.testApiBase", "https://api.github.com"),
                System.getProperty("savelogic.testApiBase", "https://uploads.github.com"));
    }

    GitHubStorageProvider(String repositoryUrl, String token, String apiBase, String uploadsBase) {
        String[] parts = parseRepoUrl(repositoryUrl);
        this.owner = parts[0];
        this.repo = parts[1];
        this.token = token == null ? "" : token.trim();
        this.apiBase = apiBase;
        this.uploadsBase = uploadsBase;
    }

    public boolean isConfigured() {
        return !owner.isEmpty() && !repo.isEmpty() && !token.isEmpty();
    }

    /**
     * Uploads a world package as its newest version and returns that version's stamp. parent is null for a full
     * copy, or the stamp of the (current newest) version a changes package builds on. Keeps the newest keepVersions
     * versions plus every version they build on, deletes the rest and unfinished uploads, and releases this
     * device's lock on the world.
     */
    public CompletableFuture<String> uploadWorld(String worldName, Path zipFile, String parent, long lastPlayed,
                                                 Device device, int keepVersions, Progress progress) {
        return CompletableFuture.supplyAsync(() -> {
            List<Path> tempParts = new ArrayList<>();
            try {
                String key = worldKey(worldName);
                String tag = TAG_PREFIX + key;
                List<Path> parts = splitIfNeeded(zipFile, tempParts);
                List<Release> releases = listSaveReleases();
                Release existing = releases.stream().filter(r -> r.tag().equals(tag)).findFirst().orElse(null);
                long release = existing != null ? existing.id() : createRelease(tag, worldName, "Minecraft world uploaded by SaveLogic.");

                String stamp = uploadParts(release, key, worldName, parts, parent, List.of(), progress);

                // Retention: keep the newest versions and everything they build on; the rest, and unfinished uploads, go.
                List<Asset> ours = new ArrayList<>(releases.stream().flatMap(r -> r.assets().stream())
                        .filter(asset -> worldKeyOf(asset.name()).equals(key)).toList());
                List<CloudSave> versions = groupVersions(ours).getOrDefault(key, List.of());
                Set<String> keep = new HashSet<>();
                keep.add(stamp);
                versions.stream().limit(Math.max(0, keepVersions - 1)).forEach(v -> {
                    if (existing != null && existing.assets().containsAll(v.parts())) {
                        keep.add(v.stamp()); // Versions still in the old shared release are migrated by dropping them.
                    }
                });
                keep.addAll(ancestors(versions, parent));
                for (CloudSave v : versions) {
                    if (keep.contains(v.stamp())) {
                        keep.addAll(ancestors(versions, v.parent()));
                    }
                }
                List<Asset> toDelete = ours.stream().filter(asset -> !keep.contains(stampOf(asset.name()))).toList();
                for (Asset asset : toDelete) {
                    deleteAsset(asset.id());
                }

                WorldMeta meta = existing != null ? WorldMeta.parse(existing.body()) : new WorldMeta();
                meta.versions.keySet().retainAll(keep);
                meta.versions.put(stamp, new WorldMeta.Version(device.name(), lastPlayed));
                if (meta.lock != null && meta.lock.deviceId().equals(device.id())) {
                    meta.lock = null;
                }
                describeWorld(release, worldName, key, meta);
                removeEmptySharedRelease(releases, toDelete);
                SaveLogic.LOGGER.info("Uploaded {} to GitHub in {} part(s) as version {}", worldName, parts.size(), stamp);
                return stamp;
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e.getMessage(), e);
            } finally {
                deleteAll(tempParts);
            }
        });
    }

    /** Lists every world in the cloud with its complete versions, newest first. */
    public CompletableFuture<List<CloudWorld>> listWorlds() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<Release> releases = listSaveReleases();
                Map<String, List<CloudSave>> byKey = groupVersions(releases.stream().flatMap(r -> r.assets().stream()).toList());
                List<CloudWorld> worlds = new ArrayList<>();
                byKey.forEach((key, versions) -> {
                    Release release = releases.stream().filter(r -> r.tag().equals(TAG_PREFIX + key)).findFirst().orElse(null);
                    WorldMeta meta = release != null ? WorldMeta.parse(release.body()) : new WorldMeta();
                    List<CloudSave> named = versions.stream().map(v -> v.withMeta(meta.versions.get(v.stamp()))).toList();
                    worlds.add(new CloudWorld(named.get(0).worldName(), key, named, meta.lock));
                });
                worlds.sort(Comparator.comparing(CloudWorld::worldName, String.CASE_INSENSITIVE_ORDER));
                return worlds;
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        });
    }

    /**
     * One world's versions and lock, reading only that world's release (and the old shared release if needed), which
     * is much quicker than {@link #listWorlds()} when a repository holds many worlds. Empty if it isn't in the cloud.
     */
    public CompletableFuture<Optional<CloudWorld>> findWorld(String worldName) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String key = worldKey(worldName);
                Release release = findReleaseByTag(TAG_PREFIX + key);
                List<Asset> assets = new ArrayList<>(release != null ? release.assets() : List.of());
                Release shared = findReleaseByTag(SHARED_RELEASE_TAG);
                if (shared != null) {
                    assets.addAll(shared.assets());
                }
                List<CloudSave> versions = groupVersions(assets).get(key);
                if (versions == null) {
                    return Optional.empty();
                }
                WorldMeta meta = release != null ? WorldMeta.parse(release.body()) : new WorldMeta();
                List<CloudSave> named = versions.stream().map(v -> v.withMeta(meta.versions.get(v.stamp()))).toList();
                return Optional.of(new CloudWorld(named.get(0).worldName(), key, named, meta.lock));
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        });
    }

    /** stamp and every version it builds on (empty for null). */
    private static Set<String> ancestors(List<CloudSave> versions, String stamp) {
        Set<String> result = new HashSet<>();
        Map<String, CloudSave> byStamp = new HashMap<>();
        versions.forEach(v -> byStamp.put(v.stamp(), v));
        for (String s = stamp; s != null && result.add(s); ) {
            CloudSave v = byStamp.get(s);
            s = v == null ? null : v.parent();
        }
        return result;
    }

    /**
     * The versions to apply, oldest first, to end up with target: either starting right after have (a version the
     * caller already has, if it is in target's chain) or from target's full base.
     */
    public static Chain chain(CloudWorld world, CloudSave target, String have) {
        Map<String, CloudSave> byStamp = new HashMap<>();
        world.versions().forEach(v -> byStamp.put(v.stamp(), v));
        List<CloudSave> chain = new ArrayList<>();
        boolean fromHave = false;
        for (CloudSave v = target; v != null; ) {
            if (v.stamp().equals(have)) {
                fromHave = true;
                break;
            }
            chain.add(0, v);
            if (v.isFull()) {
                break;
            }
            CloudSave parent = byStamp.get(v.parent());
            if (parent == null) {
                throw new IllegalStateException("The version " + v.stamp() + " builds on " + v.parent()
                        + ", which is no longer in the cloud");
            }
            v = parent;
        }
        return new Chain(chain, fromHave);
    }

    /** Marks a cloud world as open on this device (locked) or not. Does nothing if the world isn't in the cloud. */
    public CompletableFuture<Void> setLock(String worldName, Device device, boolean locked) {
        return CompletableFuture.runAsync(() -> {
            try {
                String key = worldKey(worldName);
                Release release = findReleaseByTag(TAG_PREFIX + key);
                if (release == null) {
                    return;
                }
                WorldMeta meta = WorldMeta.parse(release.body());
                if (locked) {
                    meta.lock = new Lock(device.id(), device.name(), System.currentTimeMillis());
                } else if (meta.lock != null && meta.lock.deviceId().equals(device.id())) {
                    meta.lock = null;
                } else {
                    return; // Not ours to release.
                }
                describeWorld(release.id(), release.name(), key, meta, release.assets());
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        });
    }

    private void describeWorld(long releaseId, String worldName, String key, WorldMeta meta) throws IOException, InterruptedException {
        describeWorld(releaseId, worldName, key, meta, listAssets(releaseId));
    }

    private void describeWorld(long releaseId, String worldName, String key, WorldMeta meta, List<Asset> assets)
            throws IOException, InterruptedException {
        List<CloudSave> versions = groupVersions(assets).getOrDefault(key, List.of());
        describeRelease(releaseId, worldName, WorldMeta.render(worldName, versions, meta));
    }

    // --- Setups (mods and configs) ---

    /** The newest complete setup uploaded for this Minecraft version, if any. */
    public CompletableFuture<Optional<CloudSave>> findSetup(String minecraftVersion) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Release release = findReleaseByTag(setupTag(minecraftVersion));
                if (release == null) {
                    return Optional.empty();
                }
                return groupSaves(release.assets()).stream().findFirst();
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        });
    }

    /** Uploads a setup zip for this Minecraft version, replacing the previous one once the upload succeeds. */
    public CompletableFuture<Void> uploadSetup(String minecraftVersion, Path zipFile, String notes, Progress progress) {
        return CompletableFuture.runAsync(() -> {
            List<Path> tempParts = new ArrayList<>();
            try {
                String tag = setupTag(minecraftVersion);
                String name = setupName(minecraftVersion);
                List<Path> parts = splitIfNeeded(zipFile, tempParts);
                Release existing = findReleaseByTag(tag);
                long release = existing != null ? existing.id() : createRelease(tag, name, "Mods and configs uploaded by SaveLogic.");
                List<Asset> previous = existing != null ? existing.assets() : List.of();

                uploadParts(release, setupKey(minecraftVersion), name, parts, null, previous, progress);
                describeRelease(release, name, "Mods and configs for **Minecraft " + minecraftVersion + "**, uploaded by SaveLogic.\n\n"
                        + lastSavedLine(Files.size(zipFile), parts.size()) + "\n\n" + notes
                        + "\n\nDon't edit or delete the attached files; install this setup from Cloud Saves in Minecraft.");
                SaveLogic.LOGGER.info("Uploaded setup for Minecraft {} in {} part(s)", minecraftVersion, parts.size());
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e.getMessage(), e);
            } finally {
                deleteAll(tempParts);
            }
        });
    }

    public static String setupName(String minecraftVersion) {
        return "Mods & configs (Minecraft " + minecraftVersion + ")";
    }

    private static String setupTag(String minecraftVersion) {
        return SETUP_TAG_PREFIX + safeName(minecraftVersion);
    }

    private static String setupKey(String minecraftVersion) {
        return "setup_" + safeName(minecraftVersion);
    }

    private static String safeName(String value) {
        return value.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    // --- Shared upload steps ---

    /**
     * Uploads the parts as a new copy, then deletes the given previous assets, so a failed upload keeps the old
     * copy. Returns the new copy's stamp.
     */
    private String uploadParts(long release, String key, String label, List<Path> parts, String parent, List<Asset> previous,
                               Progress progress) throws IOException, InterruptedException {
        String stamp = LocalDateTime.now().format(STAMP);
        for (int i = 0; i < parts.size(); i++) {
            Path part = parts.get(i);
            progress.phase(parts.size() > 1 ? "Uploading part " + (i + 1) + " of " + parts.size() : "Uploading", Files.size(part));
            String name = key + "." + stamp + "." + (i + 1) + "-of-" + parts.size() + (parent != null ? ".p" + parent : "") + ".zip";
            uploadAsset(release, name, label, part, progress);
        }
        progress.phase("Finishing up", 0);
        for (Asset asset : previous) {
            deleteAsset(asset.id());
        }
        return stamp;
    }

    private static String lastSavedLine(long size, int partCount) {
        return "Last saved: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + "  \n"
                + "Size: " + String.format("%.1f MB", size / (1024.0 * 1024)) + (partCount > 1 ? " in " + partCount + " parts" : "");
    }

    private static void deleteAll(List<Path> files) {
        for (Path file : files) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
            }
        }
    }

    /** Downloads every part of a save and joins them into a single zip at target. */
    public CompletableFuture<Void> downloadSave(CloudSave save, Path target, Progress progress) {
        return CompletableFuture.runAsync(() -> {
            try (OutputStream out = Files.newOutputStream(target)) {
                int count = save.parts().size();
                byte[] buffer = new byte[1 << 16];
                for (int i = 0; i < count; i++) {
                    Asset part = save.parts().get(i);
                    progress.phase(count > 1 ? "Downloading part " + (i + 1) + " of " + count : "Downloading", part.size());
                    long done = 0;
                    try (InputStream in = openAsset(part.id())) {
                        int read;
                        while ((read = in.read(buffer)) > 0) {
                            out.write(buffer, 0, read);
                            done += read;
                            progress.bytes(done);
                        }
                    }
                    if (done != part.size()) {
                        throw new IOException("Download was incomplete: got " + done + " of " + part.size() + " bytes");
                    }
                }
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        });
    }

    /** Returns null if the token can write to the repository, otherwise a message explaining what is wrong. */
    public CompletableFuture<String> testConnection() {
        return CompletableFuture.supplyAsync(() -> {
            if (!isConfigured()) {
                return "Enter a repository URL and token first.";
            }
            try {
                HttpResponse<String> response = send(api("").GET());
                if (response.statusCode() == 404) {
                    return explainNotFound();
                }
                if (response.statusCode() != 200) {
                    return describeError("connect", response);
                }
                JsonObject repoJson = GSON.fromJson(response.body(), JsonObject.class);
                JsonObject permissions = repoJson.getAsJsonObject("permissions");
                if (permissions != null && !permissions.get("push").getAsBoolean()) {
                    return "Token can read the repository but cannot write to it.";
                }
                if (!repoJson.get("private").getAsBoolean()) {
                    return "Connected, but the repository is PUBLIC. Anyone could download your worlds.";
                }
                return null;
            } catch (IOException | InterruptedException e) {
                return "Could not reach GitHub: " + e.getMessage();
            }
        });
    }

    /**
     * GitHub answers 404 (not 403) when a token isn't allowed to see a private repository, so check whether the
     * token itself works to tell a wrong URL / missing repository access apart from a bad token.
     */
    private String explainNotFound() throws IOException, InterruptedException {
        String repoName = owner + "/" + repo;
        HttpResponse<String> user = send(authorized(HttpRequest.newBuilder(URI.create(apiBase + "/user"))).GET());
        if (user.statusCode() != 200) {
            return describeError("sign in", user);
        }
        String login = GSON.fromJson(user.body(), JsonObject.class).get("login").getAsString();
        return "The token works (signed in as " + login + ") but can't see " + repoName + ". Check the URL, and on GitHub "
                + "edit the token: Repository access > Only select repositories > add " + repoName
                + ", and Permissions > Contents > Read and write.";
    }

    // --- Releases and assets ---

    /** Lists the releases made by SaveLogic, with their assets. */
    private List<Release> listSaveReleases() throws IOException, InterruptedException {
        List<Release> releases = new ArrayList<>();
        for (int page = 1; ; page++) {
            HttpResponse<String> response = send(api("/releases?per_page=100&page=" + page).GET());
            if (response.statusCode() != 200) {
                throw new IOException(describeError("list saves", response));
            }
            JsonArray array = GSON.fromJson(response.body(), JsonArray.class);
            for (JsonElement element : array) {
                JsonObject json = element.getAsJsonObject();
                String tag = json.get("tag_name").getAsString();
                if (tag.startsWith(TAG_PREFIX)) {
                    releases.add(toRelease(json));
                }
            }
            if (array.size() < 100) {
                return releases;
            }
        }
    }

    private Release findReleaseByTag(String tag) throws IOException, InterruptedException {
        HttpResponse<String> response = send(api("/releases/tags/" + encode(tag)).GET());
        if (response.statusCode() == 404) {
            if (send(api("").GET()).statusCode() == 200) {
                return null; // Repository is fine; this release just doesn't exist yet.
            }
            throw new IOException(describeError("open repository", response));
        }
        if (response.statusCode() != 200) {
            throw new IOException(describeError("find release", response));
        }
        return toRelease(GSON.fromJson(response.body(), JsonObject.class));
    }

    private Release toRelease(JsonObject json) throws IOException, InterruptedException {
        long id = json.get("id").getAsLong();
        return new Release(id, json.get("tag_name").getAsString(), stringOrEmpty(json, "name"), stringOrEmpty(json, "body"),
                listAssets(id));
    }

    private static String stringOrEmpty(JsonObject json, String field) {
        JsonElement value = json.get(field);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private long createRelease(String tag, String name, String description) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("tag_name", tag);
        body.addProperty("name", name);
        body.addProperty("body", description);
        body.addProperty("make_latest", "false");

        HttpResponse<String> response = send(api("/releases").POST(jsonBody(body)));
        if (response.statusCode() == 422 && createInitialCommit()) {
            // A release needs a commit to tag, and brand-new repositories have none.
            response = send(api("/releases").POST(jsonBody(body)));
        }
        if (response.statusCode() != 201) {
            throw new IOException(describeError("create a release for " + name, response));
        }
        return GSON.fromJson(response.body(), JsonObject.class).get("id").getAsLong();
    }

    /** Keeps the release's name and notes in sync with the latest upload, for the repository's Releases page. */
    private void describeRelease(long releaseId, String name, String description) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("name", name);
        body.addProperty("body", description);
        HttpResponse<String> response = send(api("/releases/" + releaseId).method("PATCH", jsonBody(body)));
        if (response.statusCode() != 200) {
            SaveLogic.LOGGER.warn("Could not update release notes: {}", describeError("update release", response));
        }
    }

    /** Deletes the old shared release (and its tag) once every world in it has moved to its own release. */
    private void removeEmptySharedRelease(List<Release> releases, List<Asset> deleted) throws IOException, InterruptedException {
        for (Release release : releases) {
            if (release.tag().equals(SHARED_RELEASE_TAG) && deleted.containsAll(release.assets())) {
                send(api("/releases/" + release.id()).DELETE());
                send(api("/git/refs/tags/" + SHARED_RELEASE_TAG).DELETE());
            }
        }
    }

    private boolean createInitialCommit() throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("message", "Set up SaveLogic cloud saves");
        body.addProperty("content", Base64.getEncoder().encodeToString(
                "# Minecraft saves\n\nWorlds uploaded by SaveLogic are stored under Releases.\n"
                        .getBytes(StandardCharsets.UTF_8)));
        return send(api("/contents/README.md").PUT(jsonBody(body))).statusCode() == 201;
    }

    private List<Asset> listAssets(long releaseId) throws IOException, InterruptedException {
        List<Asset> assets = new ArrayList<>();
        for (int page = 1; ; page++) {
            HttpResponse<String> response = send(api("/releases/" + releaseId + "/assets?per_page=100&page=" + page).GET());
            if (response.statusCode() != 200) {
                throw new IOException(describeError("list saves", response));
            }
            JsonArray array = GSON.fromJson(response.body(), JsonArray.class);
            for (JsonElement element : array) {
                JsonObject json = element.getAsJsonObject();
                assets.add(new Asset(json.get("id").getAsLong(), json.get("name").getAsString(), json.get("size").getAsLong(),
                        stringOrEmpty(json, "label"), Instant.parse(json.get("created_at").getAsString())));
            }
            if (array.size() < 100) {
                return assets;
            }
        }
    }

    private void uploadAsset(long releaseId, String name, String label, Path file, Progress progress) throws IOException, InterruptedException {
        URI uri = URI.create(uploadsBase + "/repos/" + encode(owner) + "/" + encode(repo)
                + "/releases/" + releaseId + "/assets?name=" + encode(name) + "&label=" + encode(label));
        HttpRequest.Builder request = authorized(HttpRequest.newBuilder(uri))
                .timeout(TRANSFER_TIMEOUT)
                .header("Content-Type", "application/zip")
                .POST(counting(HttpRequest.BodyPublishers.ofFile(file), progress));
        HttpResponse<String> response = send(request);
        if (response.statusCode() != 201) {
            throw new IOException(describeError("upload", response));
        }
    }

    private void deleteAsset(long assetId) throws IOException, InterruptedException {
        HttpResponse<String> response = send(api("/releases/assets/" + assetId).DELETE());
        if (response.statusCode() != 204 && response.statusCode() != 404) {
            SaveLogic.LOGGER.warn("Could not delete old cloud save part {}: {}", assetId, describeError("delete", response));
        }
    }

    private InputStream openAsset(long assetId) throws IOException, InterruptedException {
        HttpRequest request = api("/releases/assets/" + assetId)
                // setHeader, not header: the default Accept would otherwise also be sent, and GitHub then
                // returns the asset's JSON description instead of the file.
                .setHeader("Accept", "application/octet-stream")
                .timeout(TRANSFER_TIMEOUT)
                .GET().build();
        HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        // GitHub answers with a redirect to a pre-signed storage URL, which must be fetched without our token.
        if (response.statusCode() / 100 == 3) {
            response.body().close();
            String location = response.headers().firstValue("Location")
                    .orElseThrow(() -> new IOException("GitHub download redirect had no location"));
            response = HTTP.send(HttpRequest.newBuilder(URI.create(location)).timeout(TRANSFER_TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
        }
        if (response.statusCode() != 200) {
            String body;
            try (InputStream in = response.body()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            throw new IOException("GitHub download failed (HTTP " + response.statusCode() + "): " + body);
        }
        return response.body();
    }

    // --- Naming ---

    /** Builds a stable, asset-safe key for a world folder name (GitHub rewrites spaces and symbols in asset names). */
    public static String worldKey(String worldName) {
        String safe = worldName.replaceAll("[^A-Za-z0-9_-]", "_");
        if (safe.length() > 60) {
            safe = safe.substring(0, 60);
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(worldName.getBytes(StandardCharsets.UTF_8));
            return safe + "-" + HexFormat.of().formatHex(hash, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String worldKeyOf(String assetName) {
        Matcher matcher = ASSET_NAME.matcher(assetName);
        return matcher.matches() ? matcher.group(1) : "";
    }

    private static String stampOf(String assetName) {
        Matcher matcher = ASSET_NAME.matcher(assetName);
        return matcher.matches() ? matcher.group(2) : "";
    }

    /** The newest complete upload for each key (used for setups). */
    static List<CloudSave> groupSaves(List<Asset> assets) {
        List<CloudSave> saves = new ArrayList<>();
        groupVersions(assets).values().forEach(versions -> saves.add(versions.get(0)));
        saves.sort(Comparator.comparing(CloudSave::worldName, String.CASE_INSENSITIVE_ORDER));
        return saves;
    }

    /** Every complete upload for each key, newest first by GitHub upload time. Unfinished uploads are left out. */
    static Map<String, List<CloudSave>> groupVersions(List<Asset> assets) {
        // key -> stamp -> parts (indexed by part number); stamp -> parent stamp for changes uploads
        Map<String, Map<String, Asset[]>> uploads = new LinkedHashMap<>();
        Map<String, String> parents = new HashMap<>();
        for (Asset asset : assets) {
            Matcher matcher = ASSET_NAME.matcher(asset.name());
            if (!matcher.matches()) {
                continue;
            }
            int part = Integer.parseInt(matcher.group(3));
            int count = Integer.parseInt(matcher.group(4));
            if (part < 1 || part > count) {
                continue;
            }
            Asset[] parts = uploads.computeIfAbsent(matcher.group(1), k -> new LinkedHashMap<>())
                    .computeIfAbsent(matcher.group(2), stamp -> new Asset[count]);
            if (parts.length != count) {
                continue; // Mismatched part counts under one timestamp; ignore the stray asset.
            }
            parts[part - 1] = asset;
            if (matcher.group(5) != null) {
                parents.put(matcher.group(2), matcher.group(5));
            }
        }

        Map<String, List<CloudSave>> result = new LinkedHashMap<>();
        uploads.forEach((key, byStamp) -> {
            List<CloudSave> versions = new ArrayList<>();
            byStamp.forEach((stamp, parts) -> {
                if (Arrays.stream(parts).allMatch(Objects::nonNull)) {
                    String label = parts[0].label().isEmpty() ? key : parts[0].label();
                    Instant uploaded = Arrays.stream(parts).map(Asset::created).max(Comparator.naturalOrder()).orElseThrow();
                    versions.add(new CloudSave(label, key, stamp, List.of(parts), uploaded, "", parents.get(stamp), 0));
                }
            });
            if (!versions.isEmpty()) {
                versions.sort(Comparator.comparing(CloudSave::uploaded).thenComparing(CloudSave::stamp).reversed());
                result.put(key, versions);
            }
        });
        return result;
    }

    private static List<Path> splitIfNeeded(Path zipFile, List<Path> tempParts) throws IOException {
        long size = Files.size(zipFile);
        if (size <= PART_SIZE) {
            return List.of(zipFile);
        }
        List<Path> parts = new ArrayList<>();
        try (InputStream in = Files.newInputStream(zipFile)) {
            for (long offset = 0; offset < size; offset += PART_SIZE) {
                Path part = Files.createTempFile("savelogic-part", ".zip");
                tempParts.add(part);
                try (OutputStream out = Files.newOutputStream(part)) {
                    copy(in, out, Math.min(PART_SIZE, size - offset));
                }
                parts.add(part);
            }
        }
        return parts;
    }

    private static void copy(InputStream in, OutputStream out, long length) throws IOException {
        byte[] buffer = new byte[1 << 16];
        long remaining = length;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                throw new IOException("Unexpected end of file while splitting world");
            }
            out.write(buffer, 0, read);
            remaining -= read;
        }
    }

    // --- HTTP helpers ---

    private HttpRequest.Builder api(String path) {
        URI uri = URI.create(apiBase + "/repos/" + encode(owner) + "/" + encode(repo) + path);
        return authorized(HttpRequest.newBuilder(uri)).timeout(Duration.ofMinutes(1));
    }

    private HttpRequest.Builder authorized(HttpRequest.Builder builder) {
        return builder
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "SaveLogic-Minecraft-Mod");
    }

    /** Wraps a body publisher so progress sees each chunk as the HTTP client takes it to send. */
    private static HttpRequest.BodyPublisher counting(HttpRequest.BodyPublisher inner, Progress progress) {
        return new HttpRequest.BodyPublisher() {
            @Override
            public long contentLength() {
                return inner.contentLength();
            }

            @Override
            public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
                AtomicLong sent = new AtomicLong();
                inner.subscribe(new Flow.Subscriber<ByteBuffer>() {
                    @Override public void onSubscribe(Flow.Subscription subscription) { subscriber.onSubscribe(subscription); }
                    @Override public void onNext(ByteBuffer item) {
                        progress.bytes(sent.addAndGet(item.remaining()));
                        subscriber.onNext(item);
                    }
                    @Override public void onError(Throwable throwable) { subscriber.onError(throwable); }
                    @Override public void onComplete() { subscriber.onComplete(); }
                });
            }
        };
    }

    private static HttpRequest.BodyPublisher jsonBody(JsonObject json) {
        return HttpRequest.BodyPublishers.ofString(GSON.toJson(json));
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) throws IOException, InterruptedException {
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String describeError(String action, HttpResponse<String> response) {
        String message;
        try {
            JsonObject json = GSON.fromJson(response.body(), JsonObject.class);
            message = json != null && json.has("message") ? json.get("message").getAsString() : response.body();
        } catch (RuntimeException e) {
            message = response.body();
        }
        String hint = switch (response.statusCode()) {
            case 401 -> " (check your token)";
            case 403, 404 -> " (check the repository URL and that the token has Contents read/write access to it)";
            default -> "";
        };
        return "GitHub " + action + " failed (HTTP " + response.statusCode() + "): " + message + hint;
    }

    /**
     * Accepts "https://github.com/owner/repo" (with or without ".git", a trailing slash or a "/tree/..." suffix),
     * the SSH form "git@github.com:owner/repo.git", or just "owner/repo".
     */
    static String[] parseRepoUrl(String url) {
        if (url == null) {
            return new String[]{"", ""};
        }
        String path = url.trim()
                .replaceFirst("^https?://", "")
                .replaceFirst("^(ssh://)?git@github\\.com[:/]", "")
                .replaceFirst("^(www\\.)?github\\.com/", "")
                .replaceFirst("\\.git$", "")
                .replaceAll("/+$", "");
        String[] parts = path.split("/");
        if (parts.length < 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            return new String[]{"", ""};
        }
        return new String[]{parts[0], parts[1]};
    }
}
