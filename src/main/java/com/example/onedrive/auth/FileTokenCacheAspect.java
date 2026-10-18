package com.example.onedrive.auth;

import com.microsoft.aad.msal4j.ITokenCacheAccessAspect;
import com.microsoft.aad.msal4j.ITokenCacheAccessContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

/**
 * Persists MSAL token cache to a local JSON file so device-code login is not
 * required on every CLI invocation.
 */
public final class FileTokenCacheAspect implements ITokenCacheAccessAspect {

    private static final Logger log = LoggerFactory.getLogger(FileTokenCacheAspect.class);

    private final Path cachePath;

    public FileTokenCacheAspect(Path cachePath) {
        this.cachePath = cachePath.toAbsolutePath().normalize();
    }

    public Path cachePath() {
        return cachePath;
    }

    @Override
    public void beforeCacheAccess(ITokenCacheAccessContext context) {
        try {
            if (!Files.isRegularFile(cachePath)) {
                return;
            }
            String data = Files.readString(cachePath, StandardCharsets.UTF_8);
            if (data != null && !data.isBlank()) {
                context.tokenCache().deserialize(data);
            }
        } catch (IOException e) {
            log.warn("Could not read token cache {}: {}", cachePath, e.getMessage());
        }
    }

    @Override
    public void afterCacheAccess(ITokenCacheAccessContext context) {
        if (!context.hasCacheChanged()) {
            return;
        }
        try {
            Path parent = cachePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String data = context.tokenCache().serialize();
            Path tmp = cachePath.resolveSibling(cachePath.getFileName() + ".tmp");
            Files.writeString(tmp, data, StandardCharsets.UTF_8);
            try {
                Set<PosixFilePermission> perms = EnumSet.of(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
                Files.setPosixFilePermissions(tmp, perms);
            } catch (UnsupportedOperationException ignored) {
                // Windows / non-POSIX
            }
            try {
                Files.move(tmp, cachePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(tmp, cachePath, StandardCopyOption.REPLACE_EXISTING);
            }
            log.debug("Wrote token cache to {}", cachePath);
        } catch (IOException e) {
            log.warn("Could not write token cache {}: {}", cachePath, e.getMessage());
        }
    }
}
