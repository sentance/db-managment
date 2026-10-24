package dev.pgcp.cp.crypto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import dev.pgcp.cp.config.CpProperties;

/**
 * AES-256-GCM with a master key read from a file outside the metadata database. Each ciphertext records the id of
 * the key that produced it, so older keys can stay configured for decryption while secrets are re-encrypted.
 * The secret's purpose is bound as associated data, so ciphertexts cannot be swapped between records of different kinds.
 */
@Component
public class SecretCipher {

    public record Sealed(String keyId, byte[] nonce, byte[] ciphertext) {
    }

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String currentKeyId;
    private final Map<String, SecretKey> keys = new LinkedHashMap<>();

    public SecretCipher(CpProperties props) {
        SecretKey current = load(props.crypto().masterKeyFile());
        this.currentKeyId = keyId(current);
        keys.put(currentKeyId, current);
        List<Path> previous = props.crypto().previousKeyFiles() == null ? List.of() : props.crypto().previousKeyFiles();
        for (Path p : previous) {
            SecretKey k = load(p);
            keys.putIfAbsent(keyId(k), k);
        }
    }

    public String currentKeyId() {
        return currentKeyId;
    }

    public Sealed seal(String purpose, String plaintext) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, keys.get(currentKeyId), new GCMParameterSpec(TAG_BITS, nonce));
            c.updateAAD(aad(purpose));
            return new Sealed(currentKeyId, nonce, c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encryption failed", e);
        }
    }

    public String open(String purpose, Sealed sealed) {
        SecretKey key = keys.get(sealed.keyId());
        if (key == null) {
            throw new IllegalStateException("master key " + sealed.keyId() + " is not configured; cannot decrypt");
        }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, sealed.nonce()));
            c.updateAAD(aad(purpose));
            return new String(c.doFinal(sealed.ciphertext()), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("decryption failed (wrong key or tampered data)", e);
        }
    }

    private static byte[] aad(String purpose) {
        return ("pgcp:secret:" + purpose).getBytes(StandardCharsets.UTF_8);
    }

    static SecretKey load(Path file) {
        try {
            byte[] raw = Base64.getDecoder().decode(Files.readString(file, StandardCharsets.US_ASCII).trim());
            if (raw.length != 32) {
                throw new IllegalStateException("master key in " + file + " must be 32 bytes, base64 encoded");
            }
            return new SecretKeySpec(raw, "AES");
        } catch (IOException e) {
            throw new IllegalStateException("cannot read master key file " + file + ": " + e.getMessage(), e);
        }
    }

    static String keyId(SecretKey key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
