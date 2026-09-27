package com.example.onedrive.client;

import com.example.onedrive.auth.MsalTokenProvider;
import com.example.onedrive.config.AppConfig;
import com.example.onedrive.model.DriveItemInfo;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Thin Microsoft Graph OneDrive client: list folder, upload, download.
 * Uses Graph REST ({@code https://graph.microsoft.com/v1.0}) with MSAL tokens.
 */
public final class OneDriveClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OneDriveClient.class);
    private static final String GRAPH = "https://graph.microsoft.com/v1.0";
    private static final long SIMPLE_UPLOAD_LIMIT = 4L * 1024 * 1024;

    private final AppConfig config;
    private final MsalTokenProvider tokens;
    private final HttpClient http;

    public OneDriveClient(AppConfig config, MsalTokenProvider tokens) {
        this.config = Objects.requireNonNull(config);
        this.tokens = Objects.requireNonNull(tokens);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Lists children of a folder path under the drive root.
     *
     * @param path folder path relative to root, e.g. {@code ""} / {@code "/"} / {@code "Documents/Reports"}
     */
    public List<DriveItemInfo> listChildren(String path) throws IOException, InterruptedException {
        String url = driveBase() + itemPathSegment(path) + "/children?$select=id,name,webUrl,size,folder,file";
        JsonObject body = getJson(url);
        JsonArray value = body.has("value") && body.get("value").isJsonArray()
                ? body.getAsJsonArray("value")
                : new JsonArray();

        List<DriveItemInfo> items = new ArrayList<>();
        for (JsonElement el : value) {
            JsonObject o = el.getAsJsonObject();
            items.add(new DriveItemInfo(
                    text(o, "id"),
                    text(o, "name"),
                    text(o, "webUrl"),
                    o.has("size") && !o.get("size").isJsonNull() ? o.get("size").getAsLong() : null,
                    o.has("folder"),
                    o.has("file")));
        }
        return items;
    }

    /**
     * Uploads a local file to {@code remotePath} (path relative to drive root, including filename).
     * Uses simple upload for files &lt;= 4 MiB; otherwise a resumable upload session.
     */
    public DriveItemInfo uploadFile(Path localPath, String remotePath) throws IOException, InterruptedException {
        Path local = localPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(local)) {
            throw new IllegalArgumentException("Local file not found: " + local);
        }
        String remote = normalizePath(remotePath);
        if (remote.isEmpty()) {
            throw new IllegalArgumentException("remotePath must include a file name");
        }
        long size = Files.size(local);
        log.info("Uploading {} ({} bytes) -> {}", local, size, remote);

        if (size <= SIMPLE_UPLOAD_LIMIT) {
            return simpleUpload(local, remote);
        }
        return sessionUpload(local, remote, size);
    }

    /**
     * Downloads a remote file to {@code localPath}.
     */
    public void downloadFile(String remotePath, Path localPath) throws IOException, InterruptedException {
        String remote = normalizePath(remotePath);
        Path local = localPath.toAbsolutePath().normalize();
        if (local.getParent() != null) {
            Files.createDirectories(local.getParent());
        }

        String url = driveBase() + itemPathSegment(remote) + "/content";
        HttpRequest request = authorized(HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .GET());

        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() >= 400) {
            String err = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new IOException("Download failed HTTP " + response.statusCode() + ": " + err);
        }

        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(local)) {
            in.transferTo(out);
        }
        log.info("Downloaded {} -> {} ({} bytes)", remote, local, Files.size(local));
    }

    private DriveItemInfo simpleUpload(Path local, String remote) throws IOException, InterruptedException {
        byte[] bytes = Files.readAllBytes(local);
        String url = driveBase() + itemPathSegment(remote) + "/content";
        HttpRequest request = authorized(HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", "application/octet-stream")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)));

        JsonObject body = sendJson(request);
        return toItem(body);
    }

    private DriveItemInfo sessionUpload(Path local, String remote, long size)
            throws IOException, InterruptedException {
        String createUrl = driveBase() + itemPathSegment(remote)
                + "/createUploadSession";
        String createBody = "{\"item\":{\"@microsoft.graph.conflictBehavior\":\"replace\"}}";
        HttpRequest createReq = authorized(HttpRequest.newBuilder()
                .uri(URI.create(createUrl))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(createBody)));

        JsonObject session = sendJson(createReq);
        String uploadUrl = text(session, "uploadUrl");
        if (uploadUrl == null || uploadUrl.isBlank()) {
            throw new IOException("createUploadSession did not return uploadUrl");
        }

        // Single-chunk for simplicity when slightly over 4 MiB; chunk at 3.2 MiB otherwise.
        final long chunkSize = 320L * 1024 * 10; // 3.2 MiB (multiple of 320 KiB)
        byte[] buffer = new byte[(int) Math.min(chunkSize, size)];
        long offset = 0;

        try (InputStream in = Files.newInputStream(local)) {
            DriveItemInfo completed = null;
            while (offset < size) {
                int read = in.read(buffer, 0, (int) Math.min(buffer.length, size - offset));
                if (read < 0) {
                    break;
                }
                long end = offset + read - 1;
                HttpRequest put = HttpRequest.newBuilder()
                        .uri(URI.create(uploadUrl))
                        .timeout(Duration.ofMinutes(5))
                        .header("Content-Length", String.valueOf(read))
                        .header("Content-Range", "bytes " + offset + "-" + end + "/" + size)
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(buffer, 0, read))
                        .build();

                HttpResponse<String> resp = http.send(put, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int code = resp.statusCode();
                if (code == 200 || code == 201) {
                    completed = toItem(JsonParser.parseString(resp.body()).getAsJsonObject());
                    break;
                }
                if (code != 202) {
                    throw new IOException("Chunk upload failed HTTP " + code + ": " + resp.body());
                }
                offset += read;
            }
            if (completed == null) {
                throw new IOException("Upload session ended without a completed DriveItem");
            }
            return completed;
        }
    }

    private JsonObject getJson(String url) throws IOException, InterruptedException {
        HttpRequest request = authorized(HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .GET());
        return sendJson(request);
    }

    private JsonObject sendJson(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int code = response.statusCode();
        String body = response.body() == null ? "" : response.body();
        if (code >= 400) {
            throw new IOException("Graph HTTP " + code + " for " + request.uri() + ": " + body);
        }
        if (body.isBlank()) {
            return new JsonObject();
        }
        return JsonParser.parseString(body).getAsJsonObject();
    }

    private HttpRequest authorized(HttpRequest.Builder builder) {
        return builder.header("Authorization", "Bearer " + tokens.getAccessToken())
                .header("Accept", "application/json")
                .build();
    }

    /**
     * Drive root base: {@code /me/drive} (delegated) or {@code /users/{id}/drive} (app-only).
     */
    private String driveBase() {
        if (config.isAppOnly()) {
            return GRAPH + "/users/" + encodePathSegment(config.userId()) + "/drive";
        }
        return GRAPH + "/me/drive";
    }

    /**
     * {@code /root} or {@code /root:/path:} Graph path addressing.
     */
    private static String itemPathSegment(String path) {
        String normalized = normalizePath(path);
        if (normalized.isEmpty()) {
            return "/root";
        }
        return "/root:/" + encodeItemPath(normalized) + ":";
    }

    /** Encode each path segment but keep {@code /} separators. */
    private static String encodeItemPath(String path) {
        String[] parts = path.split("/");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(encodePathSegment(parts[i]));
        }
        return sb.toString();
    }

    private static String encodePathSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        String p = path.trim().replace('\\', '/');
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/") && p.length() > 1) {
            p = p.substring(0, p.length() - 1);
        }
        if ("/".equals(p) || ".".equals(p)) {
            return "";
        }
        return p;
    }

    private static String text(JsonObject o, String field) {
        return o.has(field) && !o.get(field).isJsonNull() ? o.get(field).getAsString() : null;
    }

    private static DriveItemInfo toItem(JsonObject o) {
        return new DriveItemInfo(
                text(o, "id"),
                text(o, "name"),
                text(o, "webUrl"),
                o.has("size") && !o.get("size").isJsonNull() ? o.get("size").getAsLong() : null,
                o.has("folder"),
                o.has("file"));
    }

    @Override
    public void close() {
        // HttpClient does not require explicit close on modern JDKs; placeholder for AutoCloseable.
    }
}
