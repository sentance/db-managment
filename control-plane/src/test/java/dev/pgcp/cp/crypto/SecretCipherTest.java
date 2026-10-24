package dev.pgcp.cp.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.pgcp.cp.config.CpProperties;

class SecretCipherTest {

    @TempDir
    Path dir;

    Path newKey(String name) throws Exception {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        Path p = dir.resolve(name);
        Files.writeString(p, Base64.getEncoder().encodeToString(k));
        return p;
    }

    static CpProperties props(Path key, List<Path> previous) {
        return new CpProperties(new CpProperties.Crypto(key, previous), null, null, "local", null, null, null, false);
    }

    @Test
    void roundTripsAndBindsPurpose() throws Exception {
        var cipher = new SecretCipher(props(newKey("k1"), List.of()));
        var sealed = cipher.seal("pg-role", "s3cret-value");
        assertThat(new String(sealed.ciphertext())).doesNotContain("s3cret");
        assertThat(cipher.open("pg-role", sealed)).isEqualTo("s3cret-value");
        assertThatThrownBy(() -> cipher.open("totp", sealed)).hasMessageContaining("decryption failed");
    }

    @Test
    void detectsTampering() throws Exception {
        var cipher = new SecretCipher(props(newKey("k1"), List.of()));
        var sealed = cipher.seal("pg-role", "value");
        sealed.ciphertext()[0] ^= 1;
        assertThatThrownBy(() -> cipher.open("pg-role", sealed)).hasMessageContaining("decryption failed");
    }

    @Test
    void previousKeysStillDecryptAfterRotation() throws Exception {
        Path oldKey = newKey("old");
        var before = new SecretCipher(props(oldKey, List.of()));
        var sealed = before.seal("pg-role", "value");
        var after = new SecretCipher(props(newKey("new"), List.of(oldKey)));
        assertThat(after.currentKeyId()).isNotEqualTo(before.currentKeyId());
        assertThat(after.open("pg-role", sealed)).isEqualTo("value");
        var withoutOld = new SecretCipher(props(newKey("other"), List.of()));
        assertThatThrownBy(() -> withoutOld.open("pg-role", sealed)).hasMessageContaining("not configured");
    }

    @Test
    void rejectsShortKeys() throws Exception {
        Path p = dir.resolve("short");
        Files.writeString(p, Base64.getEncoder().encodeToString(new byte[16]));
        assertThatThrownBy(() -> new SecretCipher(props(p, List.of()))).hasMessageContaining("32 bytes");
    }

    @Test
    void generatedPasswordsAreLongAndUnambiguous() {
        String p = Passwords.generate();
        assertThat(p).hasSize(32).matches("[A-Za-z0-9]+").doesNotContain("0", "O", "l", "1", "I");
    }
}
