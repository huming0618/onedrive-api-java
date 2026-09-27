package com.example.onedrive.auth;

import com.example.onedrive.config.AppConfig;
import com.microsoft.aad.msal4j.ClientCredentialFactory;
import com.microsoft.aad.msal4j.ClientCredentialParameters;
import com.microsoft.aad.msal4j.ConfidentialClientApplication;
import com.microsoft.aad.msal4j.DeviceCodeFlowParameters;
import com.microsoft.aad.msal4j.IAuthenticationResult;
import com.microsoft.aad.msal4j.InteractiveRequestParameters;
import com.microsoft.aad.msal4j.PublicClientApplication;
import com.microsoft.aad.msal4j.SilentParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Instant;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Acquires Microsoft Graph access tokens via MSAL4J.
 * Supports device code, client credentials, and interactive browser flows.
 */
public final class MsalTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(MsalTokenProvider.class);
    private static final String GRAPH_DEFAULT_SCOPE = "https://graph.microsoft.com/.default";

    private final AppConfig config;
    private final AtomicReference<IAuthenticationResult> cached = new AtomicReference<>();
    private PublicClientApplication publicClient;
    private ConfidentialClientApplication confidentialClient;

    public MsalTokenProvider(AppConfig config) {
        this.config = config;
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

    private IAuthenticationResult acquireDeviceCode() throws Exception {
        PublicClientApplication app = publicClient();
        Set<String> scopes = config.scopes();

        try {
            if (!app.getAccounts().join().isEmpty()) {
                SilentParameters silent = SilentParameters.builder(scopes, app.getAccounts().join().iterator().next())
                        .build();
                return app.acquireTokenSilently(silent).join();
            }
        } catch (Exception silentFailure) {
            log.debug("Silent token refresh unavailable, falling back to device code: {}", silentFailure.getMessage());
        }

        DeviceCodeFlowParameters params = DeviceCodeFlowParameters.builder(
                        scopes,
                        deviceCode -> {
                            System.out.println();
                            System.out.println("=== Device Code Login ===");
                            System.out.println(deviceCode.message());
                            System.out.println("=========================");
                            System.out.println();
                        })
                .build();
        return app.acquireToken(params).join();
    }

    private IAuthenticationResult acquireInteractive() throws Exception {
        PublicClientApplication app = publicClient();
        Set<String> scopes = config.scopes();

        try {
            if (!app.getAccounts().join().isEmpty()) {
                SilentParameters silent = SilentParameters.builder(scopes, app.getAccounts().join().iterator().next())
                        .build();
                return app.acquireTokenSilently(silent).join();
            }
        } catch (Exception silentFailure) {
            log.debug("Silent token refresh unavailable, falling back to interactive: {}", silentFailure.getMessage());
        }

        InteractiveRequestParameters params = InteractiveRequestParameters.builder(URI.create(config.redirectUri()))
                .scopes(scopes)
                .build();
        return app.acquireToken(params).join();
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
                    .build();
        }
        return confidentialClient;
    }

    /** Prefetch / warm token (useful before CLI demo steps). */
    public CompletableFuture<String> warmUp() {
        return CompletableFuture.supplyAsync(this::getAccessToken);
    }
}
