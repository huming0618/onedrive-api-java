package com.example.onedrive.client;

import com.example.onedrive.auth.MsalTokenProvider;
import com.example.onedrive.config.AppConfig;
import com.example.onedrive.model.DriveItemInfo;
import com.example.onedrive.util.GraphException;
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
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Thin Microsoft Graph OneDrive client: list folder (with paging), recursive file list,
 * upload, download. Uses Graph REST with MSAL tokens.
 */
public final class OneDriveClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OneDriveClient.class);
    private static final String GRAPH = "https://graph.microsoft.com/v1.0";
    private static final long SIMPLE_UPLOAD_LIMIT = 4L * 1024 * 1024;
    private static final String SELECT =
            "id,name,webUrl,size,folder,file,lastModifiedDateTime,parentReference";

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
     * Lists children of a folder path under the drive root (follows {@code @odata.nextLink}).
     *
     * @param path folder path relative to root, e.g. {@code ""} / {@code "/"} / {@code "Documents/Reports"}
     */
    public List<DriveItemInfo> listChildren(String path) throws IOException, InterruptedException {
        String parentPath = normalizePath(path);
        String url = driveBase() + itemPathSegment(path) + "/children?$select=" + SELECT
                + "&$top=200";
        List<DriveItemInfo> items = new ArrayList<>();
        while (url != null) {
            JsonObject body = getJson(url);
            JsonArray value = body.has("value") && body.get("value").isJsonArray()
                    ? body.getAsJsonArray("value")
                    : new JsonArray();
            for (JsonElement el : value) {
                items.add(toItem(el.getAsJsonObject(), parentPath));
            }
            url = text(body, "@odata.nextLink");
        }
        return items;
    }

    /**
     * Recursively lists all <strong>files</strong> under {@code path} (folders are traversed, not returned).
     * Handles Graph paging on each folder.
     */
    public List<DriveItemInfo> listFilesRecursive(String path) throws IOException, InterruptedException {
        String root = normalizePath(path);
        List<DriveItemInfo> files = new ArrayList<>();
        Deque<String> folders = new ArrayDeque<>();
        folders.add(root);
        int foldersDone = 0;

        while (!folders.isEmpty()) {
            String folder = folders.removeFirst();
            String label = folder.isEmpty() ? "/" : folder;
            log.info("Listing folder ({}/{} queued after this): {}", foldersDone + 1, folders.size(), label);
            // stderr progress for CLI/scripts (logger may be quiet)
            System.err.printf("Listing [%d done, %d queued] %s%n", foldersDone, folders.size(), label);

            List<DriveItemInfo> children = listChildren(folder.isEmpty() ? "/" : folder);
            foldersDone++;
            for (DriveItemInfo item : children) {
                if (item.folder()) {
                    folders.add(item.displayPath());
                } else if (item.file()) {
                    files.add(item);
                }
            }
            if (foldersDone % 10 == 0 || folders.isEmpty()) {
                System.err.printf(
                        "Listing progress: folders=%d files=%d still_queued=%d%n",
                        foldersDone, files.size(), folders.size());
            }
        }
        System.err.printf("Listing complete: %d folders, %d files%n", foldersDone, files.size());
        return files;
    }

    /** Metadata for a single item (file or folder). */
    public DriveItemInfo getItem(String remotePath) throws IOException, InterruptedException {
        String remote = normalizePath(remotePath);
        String url = driveBase() + itemPathSegment(remote) + "?$select=" + SELECT;
        JsonObject body = getJson(url);
        String parent = parentOf(remote);
        return toItem(body, parent);
    }

    /**
     * Uploads a local file to {@code remotePath} (path relative to drive root, including filename).
     * Uses simple upload for files &lt;= 4 MiB; otherwise a resumable upload session.
     */
    public DriveItemInfo uploadFile(Path localPath, String remotePath) throws IOException, InterruptedException {
        Path local = localPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(local)) {
            throw new IOException("Local file not found: " + local);
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
     * Downloads a remote file to {@code localPath}. Creates parent directories.
     *
     * @param overwrite if false and local exists with the same size as remote, skip download
     * @return true if a download was performed; false if skipped
     */
    public boolean downloadFile(String remotePath, Path localPath, boolean overwrite)
            throws IOException, InterruptedException {
        String remote = normalizePath(remotePath);
        Path local = localPath.toAbsolutePath().normalize();
        if (local.getParent() != null) {
            Files.createDirectories(local.getParent());
        }

        if (!overwrite && Files.isRegularFile(local)) {
            DriveItemInfo meta = getItem(remote);
            Long remoteSize = meta.size();
            long localSize = Files.size(local);
            if (remoteSize != null && remoteSize == localSize) {
                log.info("Skip download (same size {}): {} -> {}", localSize, remote, local);
                return false;
            }
        }

        String url = driveBase() + itemPathSegment(remote) + "/content";
        HttpRequest request = authorized(HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(30))
                .GET());

        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() >= 400) {
            String err = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new GraphException(response.statusCode(), url, err);
        }

        Path tmp = local.resolveSibling(local.getFileName() + ".part");
        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(tmp)) {
            in.transferTo(out);
        }
        Files.move(tmp, local, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        log.info("Downloaded {} -> {} ({} bytes)", remote, local, Files.size(local));
        return true;
    }

    /** Overwrite by default (backward compatible). */
    public void downloadFile(String remotePath, Path localPath) throws IOException, InterruptedException {
        downloadFile(remotePath, localPath, true);
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
        return toItem(body, parentOf(remote));
    }

    private DriveItemInfo sessionUpload(Path local, String remote, long size)
            throws IOException, InterruptedException {
        String createUrl = driveBase() + itemPathSegment(remote) + "/createUploadSession";
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
                    completed = toItem(JsonParser.parseString(resp.body()).getAsJsonObject(), parentOf(remote));
                    break;
                }
                if (code != 202) {
                    throw new GraphException(code, uploadUrl, resp.body());
                }
                offset += read;
            }
            if (completed == null) {
                throw new IOException("Upload session ended without a completed DriveItem");
            }
            return completed;
        }
    }

    private static final int MAX_RETRIES = 5;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(180);

    private JsonObject getJson(String url) throws IOException, InterruptedException {
        HttpRequest request = authorized(HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .GET());
        return sendJsonWithRetry(request);
    }

    private JsonObject sendJson(HttpRequest request) throws IOException, InterruptedException {
        return sendJsonWithRetry(request);
    }

    /**
     * Sends a Graph JSON request with retries on timeouts, transient I/O errors,
     * and HTTP 429 / 502 / 503 / 504.
     */
    private JsonObject sendJsonWithRetry(HttpRequest request) throws IOException, InterruptedException {
        IOException lastIo = null;
        GraphException lastGraph = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                HttpResponse<String> response = http.send(
                        request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int code = response.statusCode();
                String body = response.body() == null ? "" : response.body();
                if (code == 429 || code == 502 || code == 503 || code == 504) {
                    lastGraph = new GraphException(code, request.uri().toString(), body);
                    long sleepMs = backoffMs(attempt, response.headers().firstValue("Retry-After").orElse(null));
                    log.warn("Graph HTTP {} (attempt {}/{}), retry in {} ms: {}",
                            code, attempt, MAX_RETRIES, sleepMs, request.uri());
                    System.err.printf("WARN: Graph HTTP %d, retry %d/%d in %d ms%n",
                            code, attempt, MAX_RETRIES, sleepMs);
                    Thread.sleep(sleepMs);
                    continue;
                }
                if (code >= 400) {
                    throw new GraphException(code, request.uri().toString(), body);
                }
                if (body.isBlank()) {
                    return new JsonObject();
                }
                return JsonParser.parseString(body).getAsJsonObject();
            } catch (HttpTimeoutException e) {
                lastIo = e;
                long sleepMs = backoffMs(attempt, null);
                log.warn("Request timed out (attempt {}/{}), retry in {} ms: {}",
                        attempt, MAX_RETRIES, sleepMs, request.uri());
                System.err.printf("WARN: request timed out, retry %d/%d in %d ms%n",
                        attempt, MAX_RETRIES, sleepMs);
                Thread.sleep(sleepMs);
            } catch (IOException e) {
                lastIo = e;
                long sleepMs = backoffMs(attempt, null);
                log.warn("I/O error (attempt {}/{}), retry in {} ms: {} — {}",
                        attempt, MAX_RETRIES, sleepMs, request.uri(), e.getMessage());
                System.err.printf("WARN: I/O error (%s), retry %d/%d in %d ms%n",
                        e.getMessage(), attempt, MAX_RETRIES, sleepMs);
                Thread.sleep(sleepMs);
            }
        }
        if (lastGraph != null) {
            throw lastGraph;
        }
        throw new IOException("Graph request failed after " + MAX_RETRIES + " attempts: "
                + request.uri(), lastIo);
    }

    private static long backoffMs(int attempt, String retryAfterHeader) {
        if (retryAfterHeader != null && !retryAfterHeader.isBlank()) {
            try {
                return Math.max(1000L, Long.parseLong(retryAfterHeader.trim()) * 1000L);
            } catch (NumberFormatException ignored) {
                // fall through to exponential backoff
            }
        }
        // 2s, 4s, 8s, 16s, 32s (capped)
        return Math.min(32_000L, 1000L * (1L << attempt));
    }

    private HttpRequest authorized(HttpRequest.Builder builder) {
        return builder.header("Authorization", "Bearer " + tokens.getAccessToken())
                .header("Accept", "application/json")
                .build();
    }

    private String driveBase() {
        if (config.isAppOnly()) {
            return GRAPH + "/users/" + encodePathSegment(config.userId()) + "/drive";
        }
        return GRAPH + "/me/drive";
    }

    private static String itemPathSegment(String path) {
        String normalized = normalizePath(path);
        if (normalized.isEmpty()) {
            return "/root";
        }
        return "/root:/" + encodeItemPath(normalized) + ":";
    }

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
        while (p.endsWith("/") && !p.isEmpty()) {
            p = p.substring(0, p.length() - 1);
        }
        if (".".equals(p)) {
            return "";
        }
        return p;
    }

    private static String parentOf(String remote) {
        String n = normalizePath(remote);
        int idx = n.lastIndexOf('/');
        if (idx < 0) {
            return "";
        }
        return n.substring(0, idx);
    }

    private static String joinPath(String parent, String name) {
        if (name == null || name.isBlank()) {
            return parent == null ? "" : parent;
        }
        if (parent == null || parent.isBlank()) {
            return name;
        }
        return parent + "/" + name;
    }

    private static String text(JsonObject o, String field) {
        return o.has(field) && !o.get(field).isJsonNull() ? o.get(field).getAsString() : null;
    }

    private static DriveItemInfo toItem(JsonObject o, String parentFolderPath) {
        String name = text(o, "name");
        String pathFromParent = joinPath(parentFolderPath, name);

        // Prefer Graph parentReference.path when present: "/drive/root:/Documents"
        String path = pathFromParent;
        if (o.has("parentReference") && o.get("parentReference").isJsonObject()) {
            JsonObject pr = o.getAsJsonObject("parentReference");
            String prPath = text(pr, "path");
            if (prPath != null) {
                String relative = stripDriveRootPrefix(prPath);
                path = joinPath(relative, name);
            }
        }

        return new DriveItemInfo(
                text(o, "id"),
                name,
                path,
                text(o, "webUrl"),
                o.has("size") && !o.get("size").isJsonNull() ? o.get("size").getAsLong() : null,
                text(o, "lastModifiedDateTime"),
                o.has("folder"),
                o.has("file"));
    }

    /** Convert {@code /drive/root:} or {@code /drive/root:/Documents} to relative path. */
    private static String stripDriveRootPrefix(String prPath) {
        String p = prPath;
        final String marker = "/root:";
        int idx = p.indexOf(marker);
        if (idx >= 0) {
            p = p.substring(idx + marker.length());
        }
        return normalizePath(p);
    }

    @Override
    public void close() {
        // HttpClient does not require explicit close on modern JDKs.
    }
}
