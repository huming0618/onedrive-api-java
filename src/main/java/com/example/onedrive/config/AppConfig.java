package com.example.onedrive.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Loads Azure AD / OneDrive settings from environment variables first,
 * then from {@code application.properties} on the classpath or working directory.
 * Never hardcode secrets.
 */
public final class AppConfig {

    public enum AuthMode {
        DEVICE_CODE,
        CLIENT_CREDENTIALS,
        INTERACTIVE;

        static AuthMode from(String raw) {
            if (raw == null || raw.isBlank()) {
                return DEVICE_CODE;
            }
            return AuthMode.valueOf(raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        }
    }

    private final String clientId;
    private final String tenantId;
    private final String clientSecret;
    private final AuthMode authMode;
    private final Set<String> scopes;
    private final String userId;
    private final String redirectUri;

    private AppConfig(
            String clientId,
            String tenantId,
            String clientSecret,
            AuthMode authMode,
            Set<String> scopes,
            String userId,
            String redirectUri) {
        this.clientId = clientId;
        this.tenantId = tenantId;
        this.clientSecret = clientSecret;
        this.authMode = authMode;
        this.scopes = scopes;
        this.userId = userId;
        this.redirectUri = redirectUri;
    }

    public static AppConfig load() {
        Properties props = new Properties();
        loadClasspath(props, "application.properties");
        loadFile(props, Path.of("application.properties"));
        loadFile(props, Path.of("src/main/resources/application.properties"));

        String clientId = firstNonBlank(
                System.getenv("ONEDRIVE_CLIENT_ID"),
                System.getenv("AZURE_CLIENT_ID"),
                props.getProperty("onedrive.clientId"));
        String tenantId = firstNonBlank(
                System.getenv("ONEDRIVE_TENANT_ID"),
                System.getenv("AZURE_TENANT_ID"),
                props.getProperty("onedrive.tenantId"),
                "common");
        String clientSecret = firstNonBlank(
                System.getenv("ONEDRIVE_CLIENT_SECRET"),
                System.getenv("AZURE_CLIENT_SECRET"),
                props.getProperty("onedrive.clientSecret"));
        AuthMode authMode = AuthMode.from(firstNonBlank(
                System.getenv("ONEDRIVE_AUTH_MODE"),
                props.getProperty("onedrive.auth.mode"),
                "device_code"));
        String scopesRaw = firstNonBlank(
                System.getenv("ONEDRIVE_SCOPES"),
                props.getProperty("onedrive.scopes"),
                "Files.ReadWrite User.Read offline_access");
        String userId = firstNonBlank(
                System.getenv("ONEDRIVE_USER_ID"),
                props.getProperty("onedrive.userId"));
        String redirectUri = firstNonBlank(
                System.getenv("ONEDRIVE_REDIRECT_URI"),
                props.getProperty("onedrive.redirectUri"),
                "http://localhost");

        if (clientId == null || clientId.isBlank() || "YOUR_CLIENT_ID".equals(clientId)) {
            throw new IllegalStateException(
                    "Missing clientId. Set ONEDRIVE_CLIENT_ID or onedrive.clientId "
                            + "(see application.properties.example).");
        }

        Set<String> scopes = Arrays.stream(scopesRaw.split("[\\s,]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());

        if (authMode == AuthMode.CLIENT_CREDENTIALS) {
            if (clientSecret == null || clientSecret.isBlank()) {
                throw new IllegalStateException(
                        "client_credentials requires ONEDRIVE_CLIENT_SECRET / onedrive.clientSecret.");
            }
            if (userId == null || userId.isBlank()) {
                throw new IllegalStateException(
                        "client_credentials requires ONEDRIVE_USER_ID / onedrive.userId "
                                + "(user UPN or object id whose drive to access).");
            }
        }

        return new AppConfig(clientId, tenantId, clientSecret, authMode, scopes, userId, redirectUri);
    }

    public String clientId() {
        return clientId;
    }

    public String tenantId() {
        return tenantId;
    }

    public String clientSecret() {
        return clientSecret;
    }

    public AuthMode authMode() {
        return authMode;
    }

    public Set<String> scopes() {
        return scopes;
    }

    public List<String> scopeList() {
        return List.copyOf(scopes);
    }

    public String userId() {
        return userId;
    }

    public String redirectUri() {
        return redirectUri;
    }

    public boolean isAppOnly() {
        return authMode == AuthMode.CLIENT_CREDENTIALS;
    }

    public String authority() {
        return "https://login.microsoftonline.com/" + tenantId;
    }

    private static void loadClasspath(Properties props, String name) {
        try (InputStream in = AppConfig.class.getClassLoader().getResourceAsStream(name)) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException ignored) {
            // optional
        }
    }

    private static void loadFile(Properties props, Path path) {
        if (!Files.isRegularFile(path)) {
            return;
        }
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        } catch (IOException ignored) {
            // optional
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "AppConfig{clientId=" + mask(clientId)
                + ", tenantId=" + tenantId
                + ", authMode=" + authMode
                + ", scopes=" + scopes
                + ", userId=" + (userId == null ? "" : userId)
                + ", hasSecret=" + (clientSecret != null && !clientSecret.isBlank())
                + "}";
    }

    private static String mask(String id) {
        if (id == null || id.length() < 8) {
            return "****";
        }
        return id.substring(0, 4) + "…" + id.substring(id.length() - 4);
    }
}
