package com.zinngar.savelogic.mods;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Checks a set of mod jars against what each one declares in its fabric.mod.json: the versions of other mods it needs
 * ("depends") and the ones it doesn't work with ("breaks"). Fabric refuses to start the game when these aren't met, and
 * Modrinth doesn't always list them, so an update is checked against the rest of the mods before it is installed.
 * <p>
 * Only what can be known for sure is reported: a needed mod that isn't one of the jars is left to Fabric, because it
 * may be bundled inside another jar.
 */
public final class ModCompatibility {

    public record ModInfo(Path jar, String id, String name, String version, Map<String, List<String>> depends,
                          Map<String, List<String>> breaks) {}

    /** "mod" can't be used together with "other"; description says why, for the player. */
    public record Conflict(ModInfo mod, ModInfo other, String description) {}

    private ModCompatibility() {
    }

    /** Reads every jar in the folder that is a Fabric mod; anything else is skipped. */
    public static List<ModInfo> read(Path modsDir) {
        List<ModInfo> mods = new ArrayList<>();
        if (!Files.isDirectory(modsDir)) {
            return mods;
        }
        try (Stream<Path> list = Files.list(modsDir)) {
            for (Path jar : list.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList()) {
                read1(jar).ifPresent(mods::add);
            }
        } catch (Exception e) {
            // An unreadable folder has nothing to check.
        }
        return mods;
    }

    public static Optional<ModInfo> read1(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("fabric.mod.json");
            if (entry == null) {
                return Optional.empty();
            }
            JsonObject json;
            try (JsonReader reader = new JsonReader(new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8))) {
                reader.setLenient(true); // Some mods have line breaks inside their descriptions.
                json = JsonParser.parseReader(reader).getAsJsonObject();
            }
            String id = json.get("id").getAsString();
            return Optional.of(new ModInfo(jar, id, json.has("name") ? json.get("name").getAsString() : id,
                    json.get("version").getAsString(), ranges(json, "depends"), ranges(json, "breaks")));
        } catch (Exception e) {
            return Optional.empty(); // Not a jar, or not a Fabric mod.
        }
    }

    /** A value is one version range or a list of them, any of which may match. */
    private static Map<String, List<String>> ranges(JsonObject json, String key) {
        Map<String, List<String>> result = new HashMap<>();
        if (!json.has(key) || !json.get(key).isJsonObject()) {
            return result;
        }
        for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject(key).entrySet()) {
            List<String> values = new ArrayList<>();
            if (entry.getValue().isJsonArray()) {
                entry.getValue().getAsJsonArray().forEach(v -> values.add(v.getAsString()));
            } else if (entry.getValue().isJsonPrimitive()) {
                values.add(entry.getValue().getAsString());
            }
            result.put(entry.getKey(), values);
        }
        return result;
    }

    /** Every pair in the set that Fabric would refuse to load together. */
    public static List<Conflict> conflicts(Collection<ModInfo> mods) {
        Map<String, ModInfo> byId = new HashMap<>();
        mods.forEach(mod -> byId.put(mod.id(), mod));
        List<Conflict> conflicts = new ArrayList<>();
        for (ModInfo mod : mods) {
            for (Map.Entry<String, List<String>> breaks : mod.breaks().entrySet()) {
                ModInfo other = byId.get(breaks.getKey());
                if (other != null && other != mod && matches(breaks.getValue(), other.version()) == Boolean.TRUE) {
                    conflicts.add(new Conflict(mod, other, mod.name() + " " + mod.version() + " doesn't work with "
                            + other.name() + " " + other.version()));
                }
            }
            for (Map.Entry<String, List<String>> depends : mod.depends().entrySet()) {
                ModInfo other = byId.get(depends.getKey());
                if (other != null && other != mod && matches(depends.getValue(), other.version()) == Boolean.FALSE) {
                    conflicts.add(new Conflict(mod, other, mod.name() + " " + mod.version() + " needs a different version of "
                            + other.name() + " than " + other.version()));
                }
            }
        }
        return conflicts;
    }

    /** Whether the version is in any of the ranges; null if a range or the version can't be understood. */
    private static Boolean matches(List<String> ranges, String version) {
        try {
            Version parsed = Version.parse(version);
            for (String range : ranges) {
                if (VersionPredicate.parse(range).test(parsed)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return null;
        }
    }
}
