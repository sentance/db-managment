package dev.pgcp.cp.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class TotpTest {

    /** RFC 6238 appendix B, SHA-1 vectors (last 6 of the 8 published digits). */
    @Test
    void matchesRfc6238Vectors() {
        byte[] key = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        assertThat(Totp.code(key, 59 / 30)).isEqualTo("287082");
        assertThat(Totp.code(key, 1111111109L / 30)).isEqualTo("081804");
        assertThat(Totp.code(key, 1234567890L / 30)).isEqualTo("005924");
        assertThat(Totp.code(key, 2000000000L / 30)).isEqualTo("279037");
    }

    @Test
    void base32RoundTrip() {
        byte[] key = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        assertThat(Totp.base32(key)).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        assertThat(Totp.base32Decode(Totp.base32(key))).isEqualTo(key);
    }

    @Test
    void acceptsAdjacentStepsOnly() {
        String secret = Totp.newSecret();
        Instant now = Instant.ofEpochSecond(1_800_000_000L);
        long step = now.getEpochSecond() / 30;
        byte[] key = Totp.base32Decode(secret);
        assertThat(Totp.verify(secret, Totp.code(key, step), now)).hasValue(step);
        assertThat(Totp.verify(secret, Totp.code(key, step + 1), now)).hasValue(step + 1);
        assertThat(Totp.verify(secret, Totp.code(key, step - 2), now)).isEmpty();
        assertThat(Totp.verify(secret, "12345", now)).isEmpty();
        assertThat(Totp.verify(secret, null, now)).isEmpty();
    }
}
