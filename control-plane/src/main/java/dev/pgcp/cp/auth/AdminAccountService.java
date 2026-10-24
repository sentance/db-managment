package dev.pgcp.cp.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.pgcp.cp.common.ApiException;
import dev.pgcp.cp.crypto.Passwords;
import dev.pgcp.cp.crypto.SecretStore;

/** The single administrator account: password (Argon2id) + mandatory TOTP + one-time recovery codes. */
@Service
public class AdminAccountService {

    public record Admin(long id, String username, String passwordHash, Long totpSecretId, boolean totpEnabled,
            long totpLastStep) {
    }

    public record PendingSetup(long adminId, String totpSecret) {
    }

    private static final String DUMMY_HASH;

    static {
        DUMMY_HASH = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode("timing-equalizer");
    }

    private final JdbcClient jdbc;
    private final SecretStore secrets;
    private final PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    public AdminAccountService(JdbcClient jdbc, SecretStore secrets) {
        this.jdbc = jdbc;
        this.secrets = secrets;
    }

    public boolean setupRequired() {
        return jdbc.sql("select count(*) from admin_user where totp_enabled").query(Long.class).single() == 0;
    }

    /** Starts (or restarts an interrupted) initial setup. Only possible while no fully configured admin exists. */
    @Transactional
    public PendingSetup beginSetup(String username, String password) {
        if (!setupRequired()) {
            throw ApiException.conflict("setup already completed");
        }
        validateUsername(username);
        validatePassword(password);
        jdbc.sql("delete from admin_user where not totp_enabled").update();
        String totpSecret = Totp.newSecret();
        long secretId = secrets.create("totp", totpSecret);
        long id = jdbc.sql("insert into admin_user (username, password_hash, totp_secret_id) values (?, ?, ?) returning id")
                .params(username, encoder.encode(password), secretId).query(Long.class).single();
        return new PendingSetup(id, totpSecret);
    }

    /** Confirms the authenticator works, enables the account and returns recovery codes (shown once). */
    @Transactional
    public List<String> completeSetup(long adminId, String code) {
        Admin admin = byId(adminId).orElseThrow(() -> ApiException.badRequest("setup session expired"));
        if (admin.totpEnabled()) {
            throw ApiException.conflict("setup already completed");
        }
        OptionalLong step = Totp.verify(secrets.read(admin.totpSecretId()), code, Instant.now());
        if (step.isEmpty()) {
            throw ApiException.badRequest("authenticator code is not valid");
        }
        jdbc.sql("update admin_user set totp_enabled = true, totp_last_step = ? where id = ?")
                .params(step.getAsLong(), adminId).update();
        return regenerateRecoveryCodes(adminId);
    }

    /**
     * Verifies password and second factor. Always spends the same hashing effort so response time does not reveal
     * whether the username exists.
     */
    @Transactional
    public Optional<Admin> authenticate(String username, String password, String code) {
        Optional<Admin> found = byUsername(username).filter(Admin::totpEnabled);
        boolean passwordOk = encoder.matches(password == null ? "" : password,
                found.map(Admin::passwordHash).orElse(DUMMY_HASH));
        if (found.isEmpty() || !passwordOk) {
            return Optional.empty();
        }
        Admin admin = found.get();
        if (!verifySecondFactor(admin, code)) {
            return Optional.empty();
        }
        jdbc.sql("update admin_user set last_login_at = now() where id = ?").param(admin.id()).update();
        return Optional.of(admin);
    }

    /** TOTP only (recovery codes are not accepted for step-up). */
    @Transactional
    public boolean verifyTotp(String username, String code) {
        return byUsername(username).filter(Admin::totpEnabled).map(a -> consumeTotp(a, code)).orElse(false);
    }

    private boolean verifySecondFactor(Admin admin, String code) {
        if (code != null && code.matches("\\d{6}")) {
            return consumeTotp(admin, code);
        }
        return consumeRecoveryCode(admin.id(), code);
    }

    private boolean consumeTotp(Admin admin, String code) {
        OptionalLong step = Totp.verify(secrets.read(admin.totpSecretId()), code, Instant.now());
        if (step.isEmpty()) {
            return false;
        }
        // Reject replays of an already used code (or older step), atomically.
        int updated = jdbc.sql("update admin_user set totp_last_step = ? where id = ? and totp_last_step < ?")
                .params(step.getAsLong(), admin.id(), step.getAsLong()).update();
        return updated == 1;
    }

    private boolean consumeRecoveryCode(long adminId, String code) {
        if (code == null || !code.matches("[a-z0-9]{5}-[a-z0-9]{5}")) {
            return false;
        }
        int updated = jdbc.sql("""
                update totp_recovery_code set used_at = now()
                where id = (select id from totp_recovery_code
                            where admin_user_id = ? and code_hash = ? and used_at is null limit 1)
                """).params(adminId, sha256(code)).update();
        return updated == 1;
    }

    private List<String> regenerateRecoveryCodes(long adminId) {
        jdbc.sql("delete from totp_recovery_code where admin_user_id = ?").param(adminId).update();
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String raw = Passwords.generate(10).toLowerCase();
            String code = raw.substring(0, 5) + "-" + raw.substring(5);
            codes.add(code);
            jdbc.sql("insert into totp_recovery_code (admin_user_id, code_hash) values (?, ?)").params(adminId, sha256(code))
                    .update();
        }
        return codes;
    }

    /** Replaces all recovery codes; the caller must have passed step-up. */
    @Transactional
    public List<String> regenerateRecoveryCodes(String username) {
        Admin admin = byUsername(username).orElseThrow(() -> ApiException.notFound("admin"));
        return regenerateRecoveryCodes(admin.id());
    }

    public long remainingRecoveryCodes(String username) {
        return jdbc.sql("""
                select count(*) from totp_recovery_code c join admin_user a on a.id = c.admin_user_id
                where a.username = ? and c.used_at is null
                """).param(username).query(Long.class).single();
    }

    private Optional<Admin> byUsername(String username) {
        return jdbc.sql("select id, username, password_hash, totp_secret_id, totp_enabled, totp_last_step from admin_user where username = ?")
                .param(username).query(Admin.class).optional();
    }

    private Optional<Admin> byId(long id) {
        return jdbc.sql("select id, username, password_hash, totp_secret_id, totp_enabled, totp_last_step from admin_user where id = ?")
                .param(id).query(Admin.class).optional();
    }

    static void validateUsername(String username) {
        if (username == null || !username.matches("^[a-zA-Z0-9._-]{3,64}$")) {
            throw ApiException.badRequest("username must be 3-64 characters: letters, digits, . _ -");
        }
    }

    static void validatePassword(String password) {
        if (password == null || password.length() < 12 || password.length() > 256) {
            throw ApiException.badRequest("password must be 12-256 characters");
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
