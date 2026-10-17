package com.zinngar.savelogic.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.zinngar.savelogic.GitHubStorageProvider;
import com.zinngar.savelogic.GitHubStorageProvider.CloudSave;
import com.zinngar.savelogic.SaveLogic;
import com.zinngar.savelogic.setup.SetupInstaller;
import com.zinngar.savelogic.util.ZipUtil;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Syncs the "setup" (mods folder and config folder) through GitHub. Uploading zips both folders, leaving out
 * SaveLogic's own jar and token files. Installing downloads into a pending folder; {@link SetupInstaller}
 * moves it into place after Minecraft closes, because the game keeps its mod jars open while running.
 */
public final class SetupSync {

    private static final String MANIFEST = "savelogic-setup.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private SetupSync() {
    }

    public static void register() {
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> startInstallerIfPending());
    }

    public static String minecraftVersion() {
        return SharedConstants.getCurrentVersion().name();
    }

    private static Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    public static boolean isInstallPending() {
        return Files.isDirectory(gameDir().resolve(SetupInstaller.PENDING_DIR).resolve("mods"));
    }

    public static void cancelPendingInstall() {
        try {
            SetupInstaller.deleteRecursively(gameDir().resolve(SetupInstaller.PENDING_DIR));
        } catch (Exception e) {
            SaveLogic.LOGGER.error("Could not remove the pending setup", e);
        }
    }

    // --- Upload ---

    public static void uploadSetup() {
        GitHubStorageProvider provider = CloudStorageManager.getProvider();
        if (provider == null) {
            CloudSync.toast("Upload failed", "Set up your GitHub repository in SaveLogic settings.");
            return;
        }
        TransferStatus status = TransferStatus.INSTANCE;
        if (!status.begin("Uploading mods & configs")) {
            CloudSync.toast("Upload not started", "Wait for the current upload or download to finish.");
            return;
        }

        String version = minecraftVersion();
        CompletableFuture.supplyAsync(() -> {
            try {
                Path zip = Files.createTempFile("savelogic-setup", ".zip");
                List<String> mods = zipSetup(zip, status);
                return Map.entry(zip, mods);
            } catch (Exception e) {
                throw new RuntimeException("Could not zip mods and configs: " + e.getMessage(), e);
            }
        }).thenCompose(zipAndMods -> provider.uploadSetup(version, zipAndMods.getKey(), notes(zipAndMods.getValue()), status)
                .whenComplete((ignored, error) -> deleteQuietly(zipAndMods.getKey()))
                .thenApply(ignored -> zipAndMods.getValue().size()))
          .whenComplete((modCount, error) -> {
              if (error == null) {
                  status.succeed("Done! " + modCount + " mods and your configs are saved on GitHub.");
              } else {
                  SaveLogic.LOGGER.error("Setup upload failed", error);
                  status.fail("Failed: " + rootMessage(error));
              }
          });
    }

    /** Writes mods/, config/ and a manifest into zip. Returns the mod file names included. */
    public static List<String> zipSetup(Path zip, TransferStatus status) throws Exception {
        Path gameDir = gameDir();
        Set<String> ownJars = ownJarNames();
        Map<String, Path> entries = new LinkedHashMap<>();
        List<String> mods = new ArrayList<>();

        Path modsDir = gameDir.resolve("mods");
        if (Files.isDirectory(modsDir)) {
            try (Stream<Path> list = Files.list(modsDir)) {
                for (Path jar : list.filter(Files::isRegularFile).sorted().toList()) {
                    String name = jar.getFileName().toString();
                    if (name.endsWith(".jar") && !ownJars.contains(name)) {
                        entries.put("mods/" + name, jar);
                        mods.add(name);
                    }
                }
            }
        }

        Path configDir = gameDir.resolve("config");
        if (Files.isDirectory(configDir)) {
            try (Stream<Path> walk = Files.walk(configDir)) {
                for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                    String relative = ZipUtil.entryName(configDir, file);
                    if (!SetupInstaller.PRIVATE_CONFIG_FILES.contains(relative)) {
                        entries.put("config/" + relative, file);
                    }
                }
            }
        }

        JsonObject manifest = new JsonObject();
        manifest.addProperty("minecraft", minecraftVersion());
        manifest.addProperty("fabricLoader", FabricLoader.getInstance().getModContainer("fabricloader")
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("unknown"));
        JsonArray modList = new JsonArray();
        mods.forEach(modList::add);
        manifest.add("mods", modList);
        Path manifestFile = Files.createTempFile("savelogic-manifest", ".json");
        Files.writeString(manifestFile, GSON.toJson(manifest), StandardCharsets.UTF_8);
        entries.put(MANIFEST, manifestFile);
        try {
            ZipUtil.zipFiles(entries, zip, "Zipping mods & configs", status);
        } finally {
            deleteQuietly(manifestFile);
        }
        return mods;
    }

    private static String notes(List<String> mods) {
        StringBuilder text = new StringBuilder("**Mods (" + mods.size() + "):**\n");
        for (String mod : mods) {
            text.append("- ").append(mod).append('\n');
        }
        return text.toString();
    }

    // --- Install ---

    /** Looks up the cloud setup for this Minecraft version; calls back on the render thread. */
    public static void findSetup(Consumer<Optional<CloudSave>> onResult, Consumer<String> onError) {
        GitHubStorageProvider provider = CloudStorageManager.getProvider();
        if (provider == null) {
            onError.accept("Set up your GitHub repository in SaveLogic settings.");
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        provider.findSetup(minecraftVersion()).whenComplete((setup, error) -> minecraft.execute(() -> {
            if (error == null) {
                onResult.accept(setup);
            } else {
                onError.accept(rootMessage(error));
            }
        }));
    }

    /** Downloads the setup into the pending folder; calls onReady on the render thread when it can be installed. */
    public static void downloadSetup(CloudSave setup, Runnable onReady) {
        GitHubStorageProvider provider = CloudStorageManager.getProvider();
        TransferStatus status = TransferStatus.INSTANCE;
        if (provider == null || !status.begin("Downloading mods & configs")) {
            CloudSync.toast("Download not started", "Wait for the current upload or download to finish.");
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Path pending = gameDir().resolve(SetupInstaller.PENDING_DIR);
        Path zip;
        try {
            zip = Files.createTempFile("savelogic-setup", ".zip");
        } catch (Exception e) {
            status.fail("Could not create a temporary file: " + e.getMessage());
            return;
        }

        provider.downloadSave(setup, zip, status).thenRun(() -> {
            status.phase("Unpacking", 0);
            try {
                SetupInstaller.deleteRecursively(pending);
                ZipUtil.unzip(zip, pending);
                Files.createDirectories(pending.resolve("mods"));
            } catch (Exception e) {
                throw new RuntimeException("Could not unpack setup: " + e.getMessage(), e);
            }
        }).whenComplete((ignored, error) -> minecraft.execute(() -> {
            deleteQuietly(zip);
            if (error == null) {
                status.succeed("Downloaded. Close Minecraft to finish installing.");
                onReady.run();
            } else {
                cancelPendingInstall();
                SaveLogic.LOGGER.error("Setup download failed", error);
                status.fail("Failed: " + rootMessage(error));
            }
        }));
    }

    /** Starts the installer as its own process; it waits for this game to exit, then swaps the files. */
    private static void startInstallerIfPending() {
        if (!isInstallPending()) {
            return;
        }
        try {
            String java = ProcessHandle.current().info().command()
                    .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            Path classpath = Path.of(SetupInstaller.class.getProtectionDomain().getCodeSource().getLocation().toURI());

            List<String> command = new ArrayList<>(List.of(java, "-cp", classpath.toString(),
                    SetupInstaller.class.getName(), gameDir().toAbsolutePath().toString(),
                    Long.toString(ProcessHandle.current().pid())));
            command.addAll(ownJarNames());

            new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            SaveLogic.LOGGER.info("Started the setup installer; it will finish after Minecraft closes");
        } catch (Exception e) {
            SaveLogic.LOGGER.error("Could not start the setup installer", e);
        }
    }

    /** File names of SaveLogic's own jar(s), which a setup never replaces. */
    static Set<String> ownJarNames() {
        Set<String> names = new TreeSet<>();
        FabricLoader.getInstance().getModContainer(SaveLogic.MOD_ID).map(ModContainer::getOrigin).ifPresent(origin -> {
            try {
                for (Path path : origin.getPaths()) {
                    String name = path.getFileName().toString();
                    if (name.endsWith(".jar")) {
                        names.add(name);
                    }
                }
            } catch (UnsupportedOperationException ignored) {
                // Nested jars have no paths of their own.
            }
        });
        return names;
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (Exception ignored) {
        }
    }
}
