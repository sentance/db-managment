package dev.pgcp.cp.auth;

import java.time.Instant;

/** Generates codes for tests. Offsets let a test use a later time step, since reused steps are rejected as replays. */
public final class TotpTestSupport {

    private TotpTestSupport() {
    }

    public static String code(String secret, int stepOffset) {
        long step = Instant.now().getEpochSecond() / 30 + stepOffset;
        return Totp.code(Totp.base32Decode(secret), step);
    }
}
