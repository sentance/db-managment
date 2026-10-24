package dev.pgcp.cp.crypto;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import dev.pgcp.cp.common.ApiException;

/** Encrypted secret storage in the metadata database. Plaintext never leaves this class except to its caller. */
@Service
public class SecretStore {

    private final JdbcClient jdbc;
    private final SecretCipher cipher;

    public SecretStore(JdbcClient jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    public long create(String purpose, String plaintext) {
        SecretCipher.Sealed s = cipher.seal(purpose, plaintext);
        return jdbc.sql("insert into secret (purpose, key_id, nonce, ciphertext) values (?, ?, ?, ?) returning id")
                .params(purpose, s.keyId(), s.nonce(), s.ciphertext())
                .query(Long.class).single();
    }

    public String read(long id) {
        record Row(String purpose, String keyId, byte[] nonce, byte[] ciphertext) {
        }
        Row row = jdbc.sql("select purpose, key_id, nonce, ciphertext from secret where id = ?").param(id)
                .query(Row.class).optional().orElseThrow(() -> ApiException.notFound("secret"));
        return cipher.open(row.purpose(), new SecretCipher.Sealed(row.keyId(), row.nonce(), row.ciphertext()));
    }

    public void replace(long id, String plaintext) {
        String purpose = jdbc.sql("select purpose from secret where id = ?").param(id).query(String.class).single();
        SecretCipher.Sealed s = cipher.seal(purpose, plaintext);
        jdbc.sql("update secret set key_id = ?, nonce = ?, ciphertext = ?, updated_at = now() where id = ?")
                .params(s.keyId(), s.nonce(), s.ciphertext(), id).update();
    }

    public void delete(long id) {
        jdbc.sql("delete from secret where id = ?").param(id).update();
    }
}
