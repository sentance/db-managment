package dev.pgcp.cp.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import dev.pgcp.cp.config.CpProperties;
import dev.pgcp.cp.crypto.Passwords;

/**
 * Guards the first-run setup: until an administrator exists, setup requires a random token written to a 0600 file.
 * The token itself is never logged, only the file location.
 */
@Service
public class SetupTokenService {

    private static final Logger log = LoggerFactory.getLogger(SetupTokenService.class);

    private final Path file;
    private final AdminAccountService accounts;

    public SetupTokenService(CpProperties props, AdminAccountService accounts) {
        this.file = props.setupTokenFile();
        this.accounts = accounts;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureToken() throws IOException {
        if (!accounts.setupRequired()) {
            Files.deleteIfExists(file);
            return;
        }
        if (!Files.exists(file)) {
            Files.createDirectories(file.getParent());
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(file, Passwords.generate(32), StandardCharsets.US_ASCII);
        }
        log.warn("Initial setup required. Read the one-time setup token from {} on the server.", file);
    }

    public boolean matches(String candidate) {
        if (candidate == null || !Files.exists(file)) {
            return false;
        }
        try {
            String token = Files.readString(file, StandardCharsets.US_ASCII).trim();
            return MessageDigest.isEqual(token.getBytes(StandardCharsets.US_ASCII),
                    candidate.trim().getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
            return false;
        }
    }

    public void consume() throws IOException {
        Files.deleteIfExists(file);
    }
}
