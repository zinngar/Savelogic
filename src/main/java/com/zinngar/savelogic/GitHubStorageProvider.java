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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 */
public class GitHubStorageProvider {

    private static final String TAG_PREFIX = "savelogic-";
    /** Early 2.0.0 builds kept every world in one shared release with this tag; it is still read and cleaned up. */
    private static final String SHARED_RELEASE_TAG = "savelogic-saves";
    public static final long PART_SIZE = 1024L * 1024 * 1024; // 1 GB, well under GitHub's 2 GB asset limit

    private static final Pattern ASSET_NAME = Pattern.compile("^(.+)\\.(\\d{14})\\.(\\d+)-of-(\\d+)\\.zip$");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Duration TRANSFER_TIMEOUT = Duration.ofHours(2);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private static final Gson GSON = new Gson();

    /** One uploaded copy of a world, made of one or more parts. */
    public record CloudSave(String worldName, String key, String stamp, List<Asset> parts) {
        public long size() {
            return parts.stream().mapToLong(Asset::size).sum();
        }
    }

    /** label is the original world folder name, which GitHub keeps unaltered (asset names get rewritten). */
    public record Asset(long id, String name, long size, String label) {}

    private record Release(long id, String tag, List<Asset> assets) {}

    private final String owner;
    private final String repo;
    private final String token;

    public GitHubStorageProvider(String repositoryUrl, String token) {
        String[] parts = parseRepoUrl(repositoryUrl);
        this.owner = parts[0];
        this.repo = parts[1];
        this.token = token == null ? "" : token.trim();
    }

    public boolean isConfigured() {
        return !owner.isEmpty() && !repo.isEmpty() && !token.isEmpty();
    }

    /** Uploads a zipped world, replacing any previous cloud copy of the same world once the upload succeeds. */
    public CompletableFuture<Void> uploadSave(String worldName, Path zipFile, Progress progress) {
        return CompletableFuture.runAsync(() -> {
            List<Path> tempParts = new ArrayList<>();
            try {
                String key = worldKey(worldName);
                String tag = TAG_PREFIX + key;
                String stamp = LocalDateTime.now().format(STAMP);

                List<Path> parts = splitIfNeeded(zipFile, tempParts);
                List<Release> releases = listSaveReleases();
                List<Asset> previous = releases.stream()
                        .flatMap(r -> r.assets().stream())
                        .filter(asset -> worldKeyOf(asset.name()).equals(key))
                        .toList();
                Long existing = releases.stream().filter(r -> r.tag().equals(tag)).findFirst().map(Release::id).orElse(null);
                long release = existing != null ? existing : createRelease(tag, worldName);

                for (int i = 0; i < parts.size(); i++) {
                    Path part = parts.get(i);
                    progress.phase(parts.size() > 1 ? "Uploading part " + (i + 1) + " of " + parts.size() : "Uploading", Files.size(part));
                    String name = key + "." + stamp + "." + (i + 1) + "-of-" + parts.size() + ".zip";
                    uploadAsset(release, name, worldName, part, progress);
                }
                progress.phase("Finishing up", 0);

                for (Asset asset : previous) {
                    deleteAsset(asset.id());
                }
                describeRelease(release, worldName, Files.size(zipFile), parts.size());
                removeEmptySharedRelease(releases, previous);
                SaveLogic.LOGGER.info("Uploaded {} to GitHub in {} part(s)", worldName, parts.size());
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e.getMessage(), e);
            } finally {
                for (Path part : tempParts) {
                    try {
                        Files.deleteIfExists(part);
                    } catch (IOException ignored) {
                    }
                }
            }
        });
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
                }
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        });
    }

    /** Lists the newest complete upload of each world. */
    public CompletableFuture<List<CloudSave>> listSaves() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return groupSaves(listSaveReleases().stream().flatMap(r -> r.assets().stream()).toList());
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
        HttpResponse<String> user = send(authorized(HttpRequest.newBuilder(URI.create("https://api.github.com/user"))).GET());
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
                    long id = json.get("id").getAsLong();
                    releases.add(new Release(id, tag, listAssets(id)));
                }
            }
            if (array.size() < 100) {
                return releases;
            }
        }
    }

    private long createRelease(String tag, String worldName) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("tag_name", tag);
        body.addProperty("name", worldName);
        body.addProperty("body", "Minecraft world uploaded by SaveLogic.");
        body.addProperty("make_latest", "false");

        HttpResponse<String> response = send(api("/releases").POST(jsonBody(body)));
        if (response.statusCode() == 422 && createInitialCommit()) {
            // A release needs a commit to tag, and brand-new repositories have none.
            response = send(api("/releases").POST(jsonBody(body)));
        }
        if (response.statusCode() != 201) {
            throw new IOException(describeError("create a release for " + worldName, response));
        }
        return GSON.fromJson(response.body(), JsonObject.class).get("id").getAsLong();
    }

    /** Keeps the release's name and notes in sync with the latest upload, for the repository's Releases page. */
    private void describeRelease(long releaseId, String worldName, long size, int partCount) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("name", worldName);
        body.addProperty("body", "Minecraft world **" + worldName + "**, uploaded by SaveLogic.\n\n"
                + "Last saved: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + "  \n"
                + "Size: " + String.format("%.1f MB", size / (1024.0 * 1024)) + (partCount > 1 ? " in " + partCount + " parts" : "")
                + "\n\nDon't edit or delete the attached files; load this world from Cloud Saves in Minecraft.");
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
                JsonElement label = json.get("label");
                assets.add(new Asset(json.get("id").getAsLong(), json.get("name").getAsString(), json.get("size").getAsLong(),
                        label == null || label.isJsonNull() ? "" : label.getAsString()));
            }
            if (array.size() < 100) {
                return assets;
            }
        }
    }

    private void uploadAsset(long releaseId, String name, String label, Path file, Progress progress) throws IOException, InterruptedException {
        URI uri = URI.create("https://uploads.github.com/repos/" + encode(owner) + "/" + encode(repo)
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
                .header("Accept", "application/octet-stream")
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
    static String worldKey(String worldName) {
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

    static List<CloudSave> groupSaves(List<Asset> assets) {
        // key -> stamp -> parts (indexed by part number)
        Map<String, Map<String, Asset[]>> uploads = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (Asset asset : assets) {
            Matcher matcher = ASSET_NAME.matcher(asset.name());
            if (!matcher.matches()) {
                continue;
            }
            String key = matcher.group(1);
            int part = Integer.parseInt(matcher.group(3));
            int count = Integer.parseInt(matcher.group(4));
            if (part < 1 || part > count) {
                continue;
            }
            Asset[] parts = uploads.computeIfAbsent(key, k -> new LinkedHashMap<>())
                    .computeIfAbsent(matcher.group(2), stamp -> new Asset[count]);
            if (parts.length != count) {
                continue; // Mismatched part counts under one timestamp; ignore the stray asset.
            }
            parts[part - 1] = asset;
            names.putIfAbsent(key, asset.label().isEmpty() ? key : asset.label());
        }

        List<CloudSave> saves = new ArrayList<>();
        uploads.forEach((key, byStamp) -> byStamp.entrySet().stream()
                .filter(entry -> Arrays.stream(entry.getValue()).allMatch(Objects::nonNull))
                .max(Map.Entry.comparingByKey())
                .ifPresent(newest -> saves.add(new CloudSave(names.get(key), key, newest.getKey(), List.of(newest.getValue())))));
        saves.sort(Comparator.comparing(CloudSave::worldName, String.CASE_INSENSITIVE_ORDER));
        return saves;
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
        URI uri = URI.create("https://api.github.com/repos/" + encode(owner) + "/" + encode(repo) + path);
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
