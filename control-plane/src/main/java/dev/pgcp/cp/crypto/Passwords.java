package dev.pgcp.cp.crypto;

import java.security.SecureRandom;

/** Generated credentials: alphanumeric so they survive URIs, shells and config files without escaping. */
public final class Passwords {

    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Passwords() {
    }

    /** 32 characters from a 57-symbol alphabet: about 186 bits of entropy. */
    public static String generate() {
        return generate(32);
    }

    public static String generate(int length) {
        char[] out = new char[length];
        for (int i = 0; i < length; i++) {
            out[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return new String(out);
    }
}
