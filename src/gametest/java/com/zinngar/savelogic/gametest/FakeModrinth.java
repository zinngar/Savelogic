package com.zinngar.savelogic.gametest;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * An in-memory stand-in for the Modrinth endpoints SaveLogic uses. Holds a few projects, each with versions; a
 * version is identified by the SHA-1 of its (made-up) jar bytes, like on Modrinth.
 */
final class FakeModrinth implements AutoCloseable {

    record FakeVersion(String id, String projectId, String number, String published, String fileName, byte[] bytes,
                       List<String> requires, String gameVersion) {
        String sha1() {
            return sha1Of(bytes);
        }
    }

    private static final Gson GSON = new Gson();
    private final HttpServer server;
    private final Map<String, String> titles = new HashMap<>();
    private final Map<String, FakeVersion> byId = new HashMap<>();

    FakeModrinth() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                synchronized (this) {
                    handle(exchange);
                }
            } catch (Exception e) {
                send(exchange, 500, ("{\"error\":\"" + e + "\"}").getBytes(StandardCharsets.UTF_8));
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void project(String id, String title) {
        titles.put(id, title);
    }

    void version(FakeVersion version) {
        byId.put(version.id(), version);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        if (!String.valueOf(ex.getRequestHeaders().getFirst("User-Agent")).startsWith("zinngar/Savelogic/")) {
            send(ex, 400, "{\"error\":\"missing user agent\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (method.equals("POST") && path.equals("/v2/version_files")) {
            JsonObject body = GSON.fromJson(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
            JsonObject result = new JsonObject();
            body.getAsJsonArray("hashes").forEach(h -> byId.values().stream()
                    .filter(v -> v.sha1().equals(h.getAsString())).findFirst()
                    .ifPresent(v -> result.add(h.getAsString(), json(v))));
            sendJson(ex, result);
        } else if (method.equals("POST") && path.equals("/v2/version_files/update")) {
            JsonObject body = GSON.fromJson(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
            String game = body.getAsJsonArray("game_versions").get(0).getAsString();
            JsonObject result = new JsonObject();
            body.getAsJsonArray("hashes").forEach(h -> byId.values().stream()
                    .filter(v -> v.sha1().equals(h.getAsString())).findFirst()
                    .flatMap(installed -> byId.values().stream()
                            .filter(v -> v.projectId().equals(installed.projectId()) && v.gameVersion().equals(game))
                            .max((a, b) -> a.published().compareTo(b.published())))
                    .ifPresent(v -> result.add(h.getAsString(), json(v))));
            sendJson(ex, result);
        } else if (method.equals("GET") && path.equals("/v2/projects")) {
            JsonArray result = new JsonArray();
            titles.forEach((id, title) -> {
                JsonObject project = new JsonObject();
                project.addProperty("id", id);
                project.addProperty("title", title);
                result.add(project);
            });
            sendJson(ex, result);
        } else if (method.equals("GET") && path.startsWith("/v2/project/") && path.endsWith("/version")) {
            String project = path.substring("/v2/project/".length(), path.length() - "/version".length());
            JsonArray result = new JsonArray();
            byId.values().stream().filter(v -> v.projectId().equals(project)).forEach(v -> result.add(json(v)));
            sendJson(ex, result);
        } else if (method.equals("GET") && path.startsWith("/files/")) {
            String id = path.substring("/files/".length());
            send(ex, 200, byId.get(id).bytes());
        } else {
            send(ex, 404, "{}".getBytes(StandardCharsets.UTF_8));
        }
    }

    private JsonObject json(FakeVersion v) {
        JsonObject json = new JsonObject();
        json.addProperty("id", v.id());
        json.addProperty("project_id", v.projectId());
        json.addProperty("version_number", v.number());
        json.addProperty("date_published", v.published());
        JsonObject file = new JsonObject();
        file.addProperty("filename", v.fileName());
        file.addProperty("url", baseUrl() + "/files/" + v.id());
        file.addProperty("primary", true);
        file.addProperty("size", v.bytes().length);
        JsonObject hashes = new JsonObject();
        hashes.addProperty("sha1", v.sha1());
        file.add("hashes", hashes);
        JsonArray files = new JsonArray();
        files.add(file);
        json.add("files", files);
        JsonArray deps = new JsonArray();
        for (String required : v.requires()) {
            JsonObject dep = new JsonObject();
            dep.addProperty("project_id", required);
            dep.addProperty("dependency_type", "required");
            deps.add(dep);
        }
        json.add("dependencies", deps);
        return json;
    }

    static String sha1Of(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sendJson(HttpExchange ex, Object json) throws IOException {
        send(ex, 200, GSON.toJson(json).getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int code, byte[] data) throws IOException {
        ex.sendResponseHeaders(code, data.length);
        ex.getResponseBody().write(data);
    }
}
