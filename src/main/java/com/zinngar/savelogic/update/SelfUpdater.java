package com.zinngar.savelogic.update;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.zinngar.savelogic.setup.SetupInstaller;
import com.zinngar.savelogic.util.Progress;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Finds newer SaveLogic versions on this project's public GitHub releases (no token needed) and downloads one into
 * the pending folder, for {@link SetupInstaller} to swap in after Minecraft closes. A download is only accepted if it
 * matches GitHub's checksum and really is that version of SaveLogic.
 */
public final class SelfUpdater {

    /** Asset names are used as file names, so anything but a plain SaveLogic jar name is ignored. */
    private static final Pattern JAR_NAME = Pattern.compile("savelogic-[A-Za-z0-9.+_-]+\\.jar");
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public record Release(String version, String fileName, String url, long size, String sha256) {}

    private final String base;
    private final String userAgent;

    public SelfUpdater(String currentVersion) {
        // The system property only exists for SaveLogic's own tests, which use a local fake GitHub.
        this.base = System.getProperty("savelogic.testReleasesBase", "https://api.github.com");
        this.userAgent = "zinngar/Savelogic/" + currentVersion + " (github.com/zinngar/Savelogic)";
    }

    /** The newest release for this Minecraft version that is newer than currentVersion, if there is one. */
    public Optional<Release> newest(String currentVersion, String minecraftVersion) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/repos/zinngar/Savelogic/releases?per_page=100"))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", userAgent)
                .header("Accept", "application/vnd.github+json")
                .GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        // GitHub's API has short spells of server errors, which are usually over a moment later.
        for (int retry = 0; retry < 2 && response.statusCode() >= 500; retry++) {
            Thread.sleep(2000);
            response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        }
        if (response.statusCode() != 200) {
            throw new IOException("GitHub's list of SaveLogic releases isn't available right now (HTTP " + response.statusCode() + ")");
        }
        return pick(GSON.fromJson(response.body(), JsonArray.class), currentVersion, minecraftVersion);
    }

    /**
     * Compares version numbers rather than trusting the list's order. Betas are only offered to someone already on a
     * beta, so a stable version never moves to a test build.
     */
    static Optional<Release> pick(JsonArray releases, String currentVersion, String minecraftVersion) {
        boolean acceptBetas = currentVersion.contains("-");
        Release best = null;
        for (JsonElement element : releases) {
            JsonObject release = element.getAsJsonObject();
            String tag = release.get("tag_name").getAsString();
            if (!tag.startsWith("v") || !tag.endsWith("+" + minecraftVersion)
                    || flag(release, "draft") || (flag(release, "prerelease") && !acceptBetas)) {
                continue;
            }
            String version = tag.substring(1);
            if (compare(version, currentVersion) <= 0 || (best != null && compare(version, best.version()) <= 0)) {
                continue;
            }
            for (JsonElement assetElement : release.getAsJsonArray("assets")) {
                JsonObject asset = assetElement.getAsJsonObject();
                String name = asset.get("name").getAsString();
                String digest = asset.has("digest") && !asset.get("digest").isJsonNull() ? asset.get("digest").getAsString() : "";
                if (JAR_NAME.matcher(name).matches() && !name.contains("-sources") && digest.startsWith("sha256:")) {
                    best = new Release(version, name, asset.get("browser_download_url").getAsString(),
                            asset.get("size").getAsLong(), digest.substring("sha256:".length()));
                    break;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean flag(JsonObject release, String name) {
        return release.has(name) && release.get(name).getAsBoolean();
    }

    /** Orders versions like 2.0.0-beta.9+26.3 &lt; 2.0.0-beta.10+26.3 &lt; 2.0.0+26.3. The +part is ignored. */
    public static int compare(String a, String b) {
        String[] partsA = stripBuild(a).split("-", 2);
        String[] partsB = stripBuild(b).split("-", 2);
        int core = compareDotted(partsA[0], partsB[0]);
        if (core != 0) {
            return core;
        }
        if (partsA.length == 1 || partsB.length == 1) {
            return Integer.compare(partsA.length == 1 ? 1 : 0, partsB.length == 1 ? 1 : 0); // A release beats its betas.
        }
        return compareDotted(partsA[1], partsB[1]);
    }

    private static String stripBuild(String version) {
        int plus = version.indexOf('+');
        return plus >= 0 ? version.substring(0, plus) : version;
    }

    private static int compareDotted(String a, String b) {
        String[] partsA = a.split("\\.");
        String[] partsB = b.split("\\.");
        for (int i = 0; i < Math.min(partsA.length, partsB.length); i++) {
            boolean numericA = partsA[i].matches("\\d{1,9}");
            boolean numericB = partsB[i].matches("\\d{1,9}");
            int result = numericA && numericB ? Integer.compare(Integer.parseInt(partsA[i]), Integer.parseInt(partsB[i]))
                    : numericA != numericB ? (numericA ? -1 : 1) : partsA[i].compareTo(partsB[i]);
            if (result != 0) {
                return result;
            }
        }
        return Integer.compare(partsA.length, partsB.length);
    }

    /**
     * Downloads a release into the pending folder, replacing any update already waiting. The jar only appears there
     * once it has passed every check, so an interrupted or bad download can never be installed.
     */
    public void stage(Release release, Path gameDir, Progress progress) throws IOException, InterruptedException {
        Path pending = gameDir.resolve(SetupInstaller.PENDING_UPDATE_DIR);
        SetupInstaller.deleteRecursively(pending);
        Files.createDirectories(pending);
        Path part = pending.resolve(release.fileName() + ".part");
        try {
            download(release, part, progress);
            checkIsSaveLogic(part, release.version());
            Files.move(part, pending.resolve(release.fileName()), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | InterruptedException | RuntimeException e) {
            SetupInstaller.deleteRecursively(pending);
            throw e;
        }
    }

    private void download(Release release, Path target, Progress progress) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(release.url()))
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", userAgent)
                .GET().build();
        HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("The download of " + release.fileName() + " failed (HTTP " + response.statusCode() + ")");
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        long done = 0;
        byte[] buffer = new byte[1 << 16];
        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(target)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                done += read;
                progress.bytes(done);
            }
        }
        if (!HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(release.sha256())) {
            throw new IOException("The download of " + release.fileName() + " doesn't match GitHub's checksum");
        }
    }

    /** Refuses a file that isn't the SaveLogic version it was offered as. */
    private static void checkIsSaveLogic(Path jar, String version) throws IOException {
        JsonObject metadata;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("fabric.mod.json");
            if (entry == null) {
                throw new IOException("The download isn't a Fabric mod");
            }
            try (InputStreamReader reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                metadata = GSON.fromJson(reader, JsonObject.class);
            }
        }
        if (!"savelogic".equals(metadata.get("id").getAsString()) || !version.equals(metadata.get("version").getAsString())) {
            throw new IOException("The download isn't SaveLogic " + version);
        }
    }
}
