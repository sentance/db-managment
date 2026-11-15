package dev.pgcp.agent.backups;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import dev.pgcp.agent.api.LogicalBackupRequest;

class LogicalRequestTest {
    private final UUID id=UUID.randomUUID();
    private LogicalBackupRequest.Storage storage(String endpoint) {
        return new LogicalBackupRequest.Storage(endpoint,"auto","test-bucket","pgcp/test","access-private","secret-private","age1"+"q".repeat(58),"identity-private");
    }
    @Test void requiresTlsExceptForExplicitTestConfiguration() {
        assertThatThrownBy(()->storage("http://storage:5000").validate(false)).hasMessageContaining("HTTPS");
        assertThatCode(()->storage("http://storage:5000").validate(true)).doesNotThrowAnyException();
        assertThatThrownBy(()->storage("https://user:password@host.example").validate(false)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void keepsSecretsOutOfDiagnosticRendering() {
        assertThat(storage("").toString()).doesNotContain("access-private","secret-private","identity-private");
        assertThat(new LogicalBackupRequest.Connection("postgres",5432,"app","user","password-private","require",null).toString()).doesNotContain("password-private");
    }
    @Test void refusesObjectsOutsideTheOwnedOperationPrefix() {
        var r=new LogicalBackupRequest(id,LogicalBackupRequest.Action.BACKUP,id,"postgres","a".repeat(64),18,
                new LogicalBackupRequest.Connection("postgres",5432,"app","user","private","require",null),storage(""),
                "another-prefix/data.age","pgcp/test/"+id+".manifest.age",null,null);
        assertThatThrownBy(()->r.validate(false)).hasMessageContaining("prefix");
    }
}
