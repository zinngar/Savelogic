package com.zinngar.savelogic.gametest;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An in-memory stand-in for the parts of GitHub's REST API that SaveLogic uses (repository "o/r", token
 * "good-token"), so the sync flows can be tested without touching a real account. Like GitHub, asset downloads
 * redirect to a storage URL that rejects the API token, an empty repository can't have releases until it has a
 * commit, and every upload gets its own created_at time.
 */
final class FakeGitHub implements AutoCloseable {

    private static final String REPO = "/repos/o/r";
    private static final Gson GSON = new Gson();

    private final HttpServer server;
    private final Map<Long, JsonObject> releases = new LinkedHashMap<>();
    private final Map<Long, byte[]> blobs = new HashMap<>();
    private long nextId = 1000;
    private boolean hasCommit;
    private Instant clock = Instant.parse("2026-01-01T00:00:00Z");

    FakeGitHub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                synchronized (this) {
                    handle(exchange);
                }
            } catch (Exception e) {
                send(exchange, 500, json("message", String.valueOf(e)));
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = URLDecoder.decode(ex.getRequestURI().getRawPath(), StandardCharsets.UTF_8);
        Map<String, String> query = query(ex.getRequestURI().getRawQuery());
        Matcher m;

        if (path.startsWith("/blob/")) {
            if (ex.getRequestHeaders().containsKey("Authorization")) {
                send(ex, 400, json("message", "storage rejects extra Authorization"));
            } else {
                sendBytes(ex, 200, blobs.get(Long.parseLong(path.substring(6))));
            }
            return;
        }
        if (!String.valueOf(ex.getRequestHeaders().getFirst("Authorization")).startsWith("Bearer good")) {
            send(ex, 401, json("message", "Bad credentials"));
            return;
        }

        if (method.equals("GET") && path.equals(REPO)) {
            JsonObject repo = new JsonObject();
            repo.addProperty("private", true);
            repo.add("permissions", json("push", true));
            send(ex, 200, repo);
        } else if (method.equals("GET") && path.equals(REPO + "/releases")) {
            send(ex, 200, page(new ArrayList<>(releases.values()), query));
        } else if (method.equals("GET") && (m = match(REPO + "/releases/tags/(.+)", path)) != null) {
            String tag = m.group(1);
            JsonObject release = releases.values().stream().filter(r -> r.get("tag_name").getAsString().equals(tag)).findFirst().orElse(null);
            send(ex, release != null ? 200 : 404, release != null ? release : json("message", "Not Found"));
        } else if (method.equals("POST") && path.equals(REPO + "/releases")) {
            if (!hasCommit) {
                send(ex, 422, json("message", "Repository is empty."));
                return;
            }
            JsonObject body = GSON.fromJson(body(ex), JsonObject.class);
            JsonObject release = new JsonObject();
            long id = ++nextId;
            release.addProperty("id", id);
            release.addProperty("tag_name", body.get("tag_name").getAsString());
            release.addProperty("name", body.get("name").getAsString());
            release.addProperty("body", body.has("body") ? body.get("body").getAsString() : "");
            release.add("assets", new JsonArray());
            releases.put(id, release);
            send(ex, 201, release);
        } else if ((m = match(REPO + "/releases/(\\d+)", path)) != null && method.equals("PATCH")) {
            JsonObject release = releases.get(Long.parseLong(m.group(1)));
            JsonObject body = GSON.fromJson(body(ex), JsonObject.class);
            for (String field : List.of("name", "body")) {
                if (body.has(field)) {
                    release.add(field, body.get(field));
                }
            }
            send(ex, 200, release);
        } else if ((m = match(REPO + "/releases/(\\d+)", path)) != null && method.equals("DELETE")) {
            releases.remove(Long.parseLong(m.group(1)));
            send(ex, 204, null);
        } else if (method.equals("DELETE") && path.startsWith(REPO + "/git/refs/tags/")) {
            send(ex, 204, null);
        } else if ((m = match(REPO + "/releases/(\\d+)/assets", path)) != null && method.equals("GET")) {
            JsonArray assets = releases.get(Long.parseLong(m.group(1))).getAsJsonArray("assets");
            List<JsonObject> list = new ArrayList<>();
            assets.forEach(a -> list.add(a.getAsJsonObject()));
            send(ex, 200, page(list, query));
        } else if ((m = match(REPO + "/releases/(\\d+)/assets", path)) != null && method.equals("POST")) {
            byte[] data = ex.getRequestBody().readAllBytes();
            long id = ++nextId;
            blobs.put(id, data);
            clock = clock.plus(1, ChronoUnit.MINUTES); // Every upload is a minute "later".
            JsonObject asset = new JsonObject();
            asset.addProperty("id", id);
            asset.addProperty("name", query.get("name"));
            asset.addProperty("label", query.getOrDefault("label", ""));
            asset.addProperty("size", data.length);
            asset.addProperty("created_at", clock.toString());
            releases.get(Long.parseLong(m.group(1))).getAsJsonArray("assets").add(asset);
            send(ex, 201, asset);
        } else if ((m = match(REPO + "/releases/assets/(\\d+)", path)) != null && method.equals("DELETE")) {
            long id = Long.parseLong(m.group(1));
            for (JsonObject release : releases.values()) {
                JsonArray kept = new JsonArray();
                release.getAsJsonArray("assets").forEach(a -> {
                    if (a.getAsJsonObject().get("id").getAsLong() != id) {
                        kept.add(a);
                    }
                });
                release.add("assets", kept);
            }
            blobs.remove(id);
            send(ex, 204, null);
        } else if ((m = match(REPO + "/releases/assets/(\\d+)", path)) != null && method.equals("GET")) {
            List<String> accept = ex.getRequestHeaders().get("Accept");
            if (accept == null || !accept.equals(List.of("application/octet-stream"))) {
                send(ex, 200, json("note", "JSON description, not the file")); // What a doubled Accept header gets.
            } else {
                ex.getResponseHeaders().add("Location", baseUrl() + "/blob/" + m.group(1));
                send(ex, 302, null);
            }
        } else if (method.equals("PUT") && path.equals(REPO + "/contents/README.md")) {
            hasCommit = true;
            send(ex, 201, new JsonObject());
        } else {
            send(ex, 404, json("message", "Not Found: " + method + " " + path));
        }
    }

    private static Matcher match(String regex, String path) {
        Matcher m = Pattern.compile(regex).matcher(path);
        return m.matches() ? m : null;
    }

    private static JsonArray page(List<JsonObject> items, Map<String, String> query) {
        int page = Integer.parseInt(query.getOrDefault("page", "1"));
        JsonArray result = new JsonArray();
        items.stream().skip((page - 1) * 100L).limit(100).forEach(result::add);
        return result;
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> result = new HashMap<>();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                String[] kv = pair.split("=", 2);
                result.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                        kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
            }
        }
        return result;
    }

    private static String body(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static JsonObject json(String key, Object value) {
        JsonObject json = new JsonObject();
        if (value instanceof Boolean b) {
            json.addProperty(key, b);
        } else {
            json.addProperty(key, String.valueOf(value));
        }
        return json;
    }

    private static void send(HttpExchange ex, int code, Object body) throws IOException {
        sendBytes(ex, code, body == null ? null : GSON.toJson(body).getBytes(StandardCharsets.UTF_8));
    }

    private static void sendBytes(HttpExchange ex, int code, byte[] data) throws IOException {
        if (data == null || code == 204 || code == 302) {
            ex.sendResponseHeaders(code, -1);
        } else {
            ex.sendResponseHeaders(code, data.length);
            ex.getResponseBody().write(data);
        }
    }
}
