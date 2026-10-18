package com.example.onedrive;

import com.example.onedrive.auth.MsalTokenProvider;
import com.example.onedrive.client.OneDriveClient;
import com.example.onedrive.config.AppConfig;
import com.example.onedrive.model.DriveItemInfo;

import java.util.List;

/**
 * CLI demo: authenticate → list root.
 * Upload/download round-trip is left commented out (e.g. when quota is full).
 *
 * <pre>
 *   mvn -q package
 *   export ONEDRIVE_CLIENT_ID=...
 *   export ONEDRIVE_TENANT_ID=common
 *   java -jar target/onedrive-api-java-1.0.0.jar
 *   # or: mvn -q exec:java
 * </pre>
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) throws Exception {
        System.out.println("OneDrive / Microsoft Graph demo");
        System.out.println("-------------------------------");

        AppConfig config = AppConfig.load();
        System.out.println("Config: " + config);

        MsalTokenProvider tokens = new MsalTokenProvider(config);
        System.out.println("Authenticating (" + config.authMode() + ")...");
        tokens.getAccessToken();
        System.out.println("Authenticated.");

        try (OneDriveClient client = new OneDriveClient(config, tokens)) {
            System.out.println();
            System.out.println("Listing drive root:");
            List<DriveItemInfo> children = client.listChildren("/");
            if (children.isEmpty()) {
                System.out.println("  (empty)");
            } else {
                for (DriveItemInfo item : children) {
                    System.out.println("  " + item);
                }
            }

            // Upload/download temporarily disabled (e.g. quotaLimitReached).
            // Uncomment when you need the round-trip demo again:
            /*
            String remoteName = "onedrive-api-java-demo.txt";
            Path uploadLocal = Path.of("demo-upload.txt");
            Path downloadLocal = Path.of("demo-download.txt");
            String payload = "Hello from onedrive-api-java at " + Instant.now() + System.lineSeparator();
            Files.writeString(uploadLocal, payload, StandardCharsets.UTF_8);

            System.out.println();
            System.out.println("Uploading " + uploadLocal.toAbsolutePath() + " -> /" + remoteName);
            DriveItemInfo uploaded = client.uploadFile(uploadLocal, remoteName);
            System.out.println("  Uploaded: " + uploaded);

            System.out.println();
            System.out.println("Downloading /" + remoteName + " -> " + downloadLocal.toAbsolutePath());
            client.downloadFile(remoteName, downloadLocal);
            String roundTrip = Files.readString(downloadLocal, StandardCharsets.UTF_8);
            System.out.println("  Content: " + roundTrip.trim());

            if (!payload.equals(roundTrip)) {
                throw new IllegalStateException("Round-trip content mismatch");
            }
            */

            System.out.println();
            System.out.println("Demo completed successfully.");
        }
    }
}
