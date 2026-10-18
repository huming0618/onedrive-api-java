package com.example.onedrive.auth;

import com.example.onedrive.config.AppConfig;
import com.microsoft.aad.msal4j.ClientCredentialFactory;
import com.microsoft.aad.msal4j.ClientCredentialParameters;
import com.microsoft.aad.msal4j.ConfidentialClientApplication;
import com.microsoft.aad.msal4j.DeviceCodeFlowParameters;
import com.microsoft.aad.msal4j.IAccount;
import com.microsoft.aad.msal4j.IAuthenticationResult;
import com.microsoft.aad.msal4j.InteractiveRequestParameters;
import com.microsoft.aad.msal4j.PublicClientApplication;
import com.microsoft.aad.msal4j.SilentParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Acquires Microsoft Graph access tokens via MSAL4J.
 * Supports device code, client credentials, and interactive browser flows.
 * Public-client tokens are persisted under {@code ~/.onedrive-api-java/token_cache.json}
 * (override with {@code ONEDRIVE_TOKEN_CACHE}) so scripts do not re-prompt device code each run.
 */
public final class MsalTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(MsalTokenProvider.class);
    private static final String GRAPH_DEFAULT_SCOPE = "https://graph.microsoft.com/.default";

    private final AppConfig config;
    private final AtomicReference<IAuthenticationResult> cached = new AtomicReference<>();
    private final FileTokenCacheAspect tokenCacheAspect;
    private PublicClientApplication publicClient;
    private ConfidentialClientApplication confidentialClient;

    public MsalTokenProvider(AppConfig config) {
        this.config = config;
        this.tokenCacheAspect = new FileTokenCacheAspect(resolveCachePath());
    }

    public Path tokenCachePath() {
        return tokenCacheAspect.cachePath();
    }

    private static Path resolveCachePath() {
        String env = System.getenv("ONEDRIVE_TOKEN_CACHE");
        if (env != null && !env.isBlank()) {
            return Path.of(env.trim());
        }
        String home = System.getProperty("user.home");
        return Path.of(home, ".onedrive-api-java", "token_cache.json");
    }

    public synchronized String getAccessToken() {
        IAuthenticationResult existing = cached.get();
        if (existing != null && existing.expiresOnDate() != null
                && existing.expiresOnDate().toInstant().isAfter(Instant.now().plusSeconds(60))) {
            return existing.accessToken();
        }

        try {
            IAuthenticationResult result = switch (config.authMode()) {
                case DEVICE_CODE -> acquireDeviceCode();
                case CLIENT_CREDENTIALS -> acquireClientCredentials();
                case INTERACTIVE -> acquireInteractive();
            };
            cached.set(result);
            log.info("Acquired token; expires at {}", result.expiresOnDate());
            return result.accessToken();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to acquire access token: " + e.getMessage(), e);
        }
    }

    /** Account username from last successful public-client acquisition, if any. */
    public Optional<String> accountUsername() {
        IAuthenticationResult r = cached.get();
        if (r != null && r.account() != null) {
            return Optional.ofNullable(r.account().username());
        }
        try {
            if (config.authMode() != AppConfig.AuthMode.CLIENT_CREDENTIALS) {
                Set<IAccount> accounts = publicClient().getAccounts().join();
                if (!accounts.isEmpty()) {
                    return Optional.ofNullable(accounts.iterator().next().username());
                }
            }
        } catch (Exception ignored) {
            // no account yet
        }
        return Optional.empty();
    }

    private IAuthenticationResult acquireDeviceCode() throws Exception {
        PublicClientApplication app = publicClient();
        Set<String> scopes = config.scopes();

        IAuthenticationResult silent = trySilent(app, scopes);
        if (silent != null) {
            return silent;
        }

        DeviceCodeFlowParameters params = DeviceCodeFlowParameters.builder(
                        scopes,
                        deviceCode -> {
                            // stderr so scripts reading stdout stay clean
                            System.err.println();
                            System.err.println("=== Device Code Login ===");
                            System.err.println(deviceCode.message());
                            System.err.println("=========================");
                            System.err.println();
                        })
                .build();
        return app.acquireToken(params).join();
    }

    private IAuthenticationResult acquireInteractive() throws Exception {
        PublicClientApplication app = publicClient();
        Set<String> scopes = config.scopes();

        IAuthenticationResult silent = trySilent(app, scopes);
        if (silent != null) {
            return silent;
        }

        InteractiveRequestParameters params = InteractiveRequestParameters.builder(URI.create(config.redirectUri()))
                .scopes(scopes)
                .build();
        return app.acquireToken(params).join();
    }

    private IAuthenticationResult trySilent(PublicClientApplication app, Set<String> scopes) {
        try {
            Set<IAccount> accounts = app.getAccounts().join();
            if (accounts.isEmpty()) {
                return null;
            }
            SilentParameters silent = SilentParameters.builder(scopes, accounts.iterator().next()).build();
            IAuthenticationResult result = app.acquireTokenSilently(silent).join();
            log.debug("Silent token acquisition succeeded");
            return result;
        } catch (Exception silentFailure) {
            log.debug("Silent token refresh unavailable: {}", silentFailure.getMessage());
            return null;
        }
    }

    private IAuthenticationResult acquireClientCredentials() throws Exception {
        ConfidentialClientApplication app = confidentialClient();
        ClientCredentialParameters params = ClientCredentialParameters.builder(
                        Collections.singleton(GRAPH_DEFAULT_SCOPE))
                .build();
        return app.acquireToken(params).join();
    }

    private PublicClientApplication publicClient() throws Exception {
        if (publicClient == null) {
            publicClient = PublicClientApplication.builder(config.clientId())
                    .authority(config.authority())
                    .setTokenCacheAccessAspect(tokenCacheAspect)
                    .build();
        }
        return publicClient;
    }

    private ConfidentialClientApplication confidentialClient() throws Exception {
        if (confidentialClient == null) {
            confidentialClient = ConfidentialClientApplication.builder(
                            config.clientId(),
                            ClientCredentialFactory.createFromSecret(config.clientSecret()))
                    .authority(config.authority())
                    .setTokenCacheAccessAspect(tokenCacheAspect)
                    .build();
        }
        return confidentialClient;
    }

    public CompletableFuture<String> warmUp() {
        return CompletableFuture.supplyAsync(this::getAccessToken);
    }
}
