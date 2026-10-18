package com.example.onedrive;

import com.example.onedrive.auth.MsalTokenProvider;
import com.example.onedrive.cli.ExitCodes;
import com.example.onedrive.client.OneDriveClient;
import com.example.onedrive.config.AppConfig;
import com.example.onedrive.model.DriveItemInfo;
import com.example.onedrive.util.GraphException;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Command-line interface for OneDrive via Microsoft Graph.
 *
 * <pre>
 *   java -jar onedrive-api-java-1.0.0.jar [--json|--tsv] &lt;command&gt; [args]
 * </pre>
 *
 * Results go to stdout; progress / device-code prompts / errors go to stderr.
 */
public final class Main {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private Main() {}

    public static void main(String[] args) {
        int code = run(args);
        System.exit(code);
    }

    static int run(String[] args) {
        if (args.length == 0 || hasFlag(args, "--help") || hasFlag(args, "-h")) {
            printHelp();
            return args.length == 0 ? ExitCodes.USAGE : ExitCodes.OK;
        }

        OutputFormat format = OutputFormat.PLAIN;
        List<String> rest = new ArrayList<>();
        for (String a : args) {
            if ("--json".equals(a)) {
                format = OutputFormat.JSON;
            } else if ("--tsv".equals(a)) {
                format = OutputFormat.TSV;
            } else if ("--help".equals(a) || "-h".equals(a)) {
                printHelp();
                return ExitCodes.OK;
            } else {
                rest.add(a);
            }
        }

        if (rest.isEmpty()) {
            err("Missing command. Use --help for usage.");
            return ExitCodes.USAGE;
        }

        String command = rest.get(0).toLowerCase(Locale.ROOT);
        List<String> cmdArgs = rest.subList(1, rest.size());

        try {
            return switch (command) {
                case "auth" -> cmdAuth(format);
                case "ls", "list" -> cmdLs(cmdArgs, format, false);
                case "ls-recursive", "tree", "ls-r" -> cmdLs(cmdArgs, format, true);
                case "download", "dl" -> cmdDownload(cmdArgs);
                case "upload", "up" -> cmdUpload(cmdArgs, format);
                case "help" -> {
                    printHelp();
                    yield ExitCodes.OK;
                }
                default -> {
                    err("Unknown command: " + command + ". Use --help for usage.");
                    yield ExitCodes.USAGE;
                }
            };
        } catch (IllegalArgumentException e) {
            err("Usage error: " + e.getMessage());
            return ExitCodes.USAGE;
        } catch (IllegalStateException e) {
            err("Auth/config error: " + rootMessage(e));
            return ExitCodes.AUTH_CONFIG;
        } catch (GraphException e) {
            err(e.getMessage());
            return ExitCodes.GRAPH;
        } catch (IOException e) {
            err("I/O error: " + rootMessage(e));
            return ExitCodes.IO;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err("Interrupted: " + e.getMessage());
            return ExitCodes.IO;
        } catch (Exception e) {
            Throwable cause = e;
            while (cause.getCause() != null && cause.getCause() != cause) {
                if (cause instanceof GraphException ge) {
                    err(ge.getMessage());
                    return ExitCodes.GRAPH;
                }
                cause = cause.getCause();
            }
            if (e instanceof GraphException ge) {
                err(ge.getMessage());
                return ExitCodes.GRAPH;
            }
            // unwrap IllegalStateException from token provider
            if (rootMessage(e).contains("Failed to acquire") || rootMessage(e).contains("Missing clientId")
                    || rootMessage(e).contains("client_credentials")) {
                err("Auth/config error: " + rootMessage(e));
                return ExitCodes.AUTH_CONFIG;
            }
            err("Error: " + rootMessage(e));
            return ExitCodes.GRAPH;
        }
    }

    private static int cmdAuth(OutputFormat format) throws Exception {
        AppConfig config = AppConfig.load();
        MsalTokenProvider tokens = new MsalTokenProvider(config);
        tokens.getAccessToken();
        String user = tokens.accountUsername().orElse(
                config.isAppOnly() ? ("app:" + config.userId()) : "(unknown)");

        if (format == OutputFormat.JSON) {
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            o.addProperty("authMode", config.authMode().name());
            o.addProperty("account", user);
            o.addProperty("tokenCache", tokens.tokenCachePath().toString());
            o.addProperty("tenantId", config.tenantId());
            System.out.println(GSON.toJson(o));
        } else if (format == OutputFormat.TSV) {
            System.out.println("ok\t" + config.authMode() + "\t" + user + "\t" + tokens.tokenCachePath());
        } else {
            System.out.println("Authenticated.");
            System.out.println("authMode=" + config.authMode());
            System.out.println("account=" + user);
            System.out.println("tokenCache=" + tokens.tokenCachePath());
        }
        return ExitCodes.OK;
    }

    private static int cmdLs(List<String> cmdArgs, OutputFormat format, boolean recursive)
            throws Exception {
        String remote = cmdArgs.isEmpty() ? "/" : cmdArgs.get(0);
        AppConfig config = AppConfig.load();
        MsalTokenProvider tokens = new MsalTokenProvider(config);
        try (OneDriveClient client = new OneDriveClient(config, tokens)) {
            List<DriveItemInfo> items = recursive
                    ? client.listFilesRecursive(remote)
                    : client.listChildren(remote);
            for (DriveItemInfo item : items) {
                printItem(item, format);
            }
        }
        return ExitCodes.OK;
    }

    private static int cmdDownload(List<String> cmdArgs) throws Exception {
        boolean overwrite = false;
        boolean skipSameSize = true; // default: skip when local exists with same size
        List<String> positional = new ArrayList<>();
        for (String a : cmdArgs) {
            if ("--overwrite".equals(a) || "--force".equals(a)) {
                overwrite = true;
                skipSameSize = false;
            } else if ("--skip-same-size".equals(a)) {
                skipSameSize = true;
                overwrite = false;
            } else if (a.startsWith("-")) {
                throw new IllegalArgumentException("Unknown option for download: " + a);
            } else {
                positional.add(a);
            }
        }
        if (positional.size() != 2) {
            throw new IllegalArgumentException("download requires <remotePath> <localPath>");
        }
        String remote = positional.get(0);
        Path local = Path.of(positional.get(1));

        AppConfig config = AppConfig.load();
        MsalTokenProvider tokens = new MsalTokenProvider(config);
        try (OneDriveClient client = new OneDriveClient(config, tokens)) {
            boolean doOverwrite = overwrite || !skipSameSize;
            boolean downloaded = client.downloadFile(remote, local, doOverwrite);
            if (downloaded) {
                System.out.println("downloaded\t" + remote + "\t" + local.toAbsolutePath());
            } else {
                System.out.println("skipped\t" + remote + "\t" + local.toAbsolutePath());
            }
        }
        return ExitCodes.OK;
    }

    private static int cmdUpload(List<String> cmdArgs, OutputFormat format) throws Exception {
        if (cmdArgs.size() != 2) {
            throw new IllegalArgumentException("upload requires <localPath> <remotePath>");
        }
        Path local = Path.of(cmdArgs.get(0));
        String remote = cmdArgs.get(1);

        AppConfig config = AppConfig.load();
        MsalTokenProvider tokens = new MsalTokenProvider(config);
        try (OneDriveClient client = new OneDriveClient(config, tokens)) {
            DriveItemInfo uploaded = client.uploadFile(local, remote);
            printItem(uploaded, format);
        }
        return ExitCodes.OK;
    }

    private static void printItem(DriveItemInfo item, OutputFormat format) {
        switch (format) {
            case JSON -> {
                JsonObject o = new JsonObject();
                o.addProperty("id", item.id());
                o.addProperty("name", item.name());
                o.addProperty("path", item.displayPath());
                o.addProperty("kind", item.kind());
                if (item.size() != null) {
                    o.addProperty("size", item.size());
                } else {
                    o.add("size", null);
                }
                o.addProperty("lastModified", item.lastModified());
                o.addProperty("webUrl", item.webUrl());
                System.out.println(GSON.toJson(o));
            }
            case TSV -> System.out.println(
                    nullToEmpty(item.displayPath()) + "\t"
                            + (item.size() == null ? "" : item.size()) + "\t"
                            + nullToEmpty(item.id()) + "\t"
                            + nullToEmpty(item.lastModified()) + "\t"
                            + item.kind() + "\t"
                            + nullToEmpty(item.name()));
            default -> System.out.println(item);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static boolean hasFlag(String[] args, String flag) {
        return Arrays.asList(args).contains(flag);
    }

    private static void err(String msg) {
        System.err.println(msg);
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        String msg = e.getMessage();
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
            if (cur.getMessage() != null && !cur.getMessage().isBlank()) {
                msg = cur.getMessage();
            }
        }
        return msg == null ? e.getClass().getSimpleName() : msg;
    }

    private static void printHelp() {
        System.out.println("""
                onedrive-api-java — OneDrive CLI (Microsoft Graph + MSAL4J)

                Usage:
                  java -jar onedrive-api-java-1.0.0.jar [global-options] <command> [args]

                Global options:
                  --json     Machine-readable: one JSON object per line (NDJSON) on stdout
                  --tsv      Tab-separated: path, size, id, lastModified, kind, name
                  --help, -h Show this help

                Commands:
                  auth
                      Sign in / refresh token; print account and token-cache path.
                      First device_code run prints a browser URL on stderr.

                  ls [remotePath]
                      List children of a folder (default: /). One item per line.

                  ls-recursive [remotePath]
                  tree [remotePath]
                      Recursively list all FILES under remotePath (default: /).
                      Follows Graph @odata.nextLink paging.

                  download [--overwrite|--skip-same-size] <remotePath> <localPath>
                      Download a file. Creates parent directories.
                      Default / --skip-same-size: skip if local exists with same size.
                      --overwrite / --force: always replace.

                  upload <localPath> <remotePath>
                      Upload a local file to the given remote path (incl. file name).

                Exit codes:
                  0  success
                  1  usage / bad arguments
                  2  auth or configuration error (missing clientId, login failure, …)
                  3  Graph / API error (not found, quota, permission, …)
                  4  local I/O error

                Config (env preferred over application.properties):
                  ONEDRIVE_CLIENT_ID, ONEDRIVE_TENANT_ID, ONEDRIVE_AUTH_MODE
                  ONEDRIVE_CLIENT_SECRET, ONEDRIVE_USER_ID   (client_credentials)
                  ONEDRIVE_TOKEN_CACHE   (default: ~/.onedrive-api-java/token_cache.json)

                Output:
                  Results → stdout; logs, device-code prompts, errors → stderr.

                Examples:
                  java -jar target/onedrive-api-java-1.0.0.jar auth
                  java -jar target/onedrive-api-java-1.0.0.jar ls /
                  java -jar target/onedrive-api-java-1.0.0.jar --json ls-recursive /
                  java -jar target/onedrive-api-java-1.0.0.jar --tsv ls-recursive Documents
                  java -jar target/onedrive-api-java-1.0.0.jar download "Photos/a.jpg" ./a.jpg
                  java -jar target/onedrive-api-java-1.0.0.jar upload ./report.pdf Documents/report.pdf
                """);
    }

    private enum OutputFormat {
        PLAIN, JSON, TSV
    }
}
