package dev.pgcp.cp.pg;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

import org.springframework.stereotype.Component;

import dev.pgcp.cp.common.ApiException;
import dev.pgcp.cp.config.CpProperties;
import dev.pgcp.cp.crypto.SecretStore;
import dev.pgcp.cp.instances.Instance;

/**
 * Opens short-lived JDBC connections to managed or external clusters. No pooling: administrative traffic is rare
 * and pooled connections to user clusters would hold resources there.
 */
@Component
public class InstanceConnector {

    public enum As {
        /** The technical admin role (pgcp_admin for managed instances, the registered user for external ones). */
        ADMIN,
        /** The bootstrap superuser; only available for managed instances. */
        SUPERUSER
    }

    @FunctionalInterface
    public interface SqlWork<T> {
        T apply(Connection c) throws SQLException;
    }

    private final SecretStore secrets;
    private final CpProperties props;

    public InstanceConnector(SecretStore secrets, CpProperties props) {
        this.secrets = secrets;
        this.props = props;
    }

    public Connection open(Instance i, String database, As as) throws SQLException {
        String user;
        Long secretId;
        if (as == As.SUPERUSER) {
            if (i.superuserSecretId() == null) {
                throw ApiException.forbidden("superuser access is not available for this instance");
            }
            user = "postgres";
            secretId = i.superuserSecretId();
        } else {
            user = i.adminRole();
            secretId = i.adminSecretId();
        }
        return open(i, database, user, secretId == null ? null : secrets.read(secretId));
    }

    /** Connects with explicit credentials (used to validate an external instance before it is saved). */
    public Connection open(Instance i, String database, String user, String password) throws SQLException {
        String host = i.host();
        int port = i.port();
        if (props.devHostAccess() && i.managed() && i.hostPort() != null) {
            host = "127.0.0.1";
            port = i.hostPort();
        }
        Properties p = new Properties();
        p.setProperty("user", user);
        if (password != null) {
            p.setProperty("password", password);
        }
        p.setProperty("sslmode", i.sslMode());
        p.setProperty("connectTimeout", "10");
        p.setProperty("loginTimeout", "15");
        p.setProperty("socketTimeout", "900");
        p.setProperty("tcpKeepAlive", "true");
        p.setProperty("ApplicationName", "pgcp-control-plane");
        String url = "jdbc:postgresql://" + host + ":" + port + "/" + URLEncoder.encode(database, StandardCharsets.UTF_8);
        return DriverManager.getConnection(url, p);
    }

    public <T> T with(Instance i, String database, As as, SqlWork<T> work) {
        try (Connection c = open(i, database, as)) {
            return work.apply(c);
        } catch (SQLException e) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, "POSTGRES_ERROR",
                    "PostgreSQL (" + i.name() + "): " + e.getMessage());
        }
    }

    public <T> T withMaintenance(Instance i, SqlWork<T> work) {
        return with(i, i.maintenanceDb(), As.ADMIN, work);
    }
}
