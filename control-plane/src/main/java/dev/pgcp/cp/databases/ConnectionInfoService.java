package dev.pgcp.cp.databases;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import dev.pgcp.agent.api.AgentApi;
import dev.pgcp.cp.config.CpProperties;
import dev.pgcp.cp.instances.Instance;

/** Builds connection parameters and URIs. Passwords appear only when explicitly passed (creation or step-up reveal). */
@Service
public class ConnectionInfoService {

    public static final String MASK = "********";

    public record Endpoint(String kind, String description, String host, int port, String sslMode, String uri) {
    }

    public record ConnectionInfo(String database, String role, boolean passwordStored, List<Endpoint> endpoints,
            String caCertificateUrl) {
    }

    private final CpProperties props;

    public ConnectionInfoService(CpProperties props) {
        this.props = props;
    }

    public ConnectionInfo describe(Instance i, String database, String role, boolean passwordStored, String password) {
        List<Endpoint> endpoints = new ArrayList<>();
        String secret = password == null ? MASK : password;
        if (i.managed()) {
            List<String> nets = new ArrayList<>();
            nets.add(AgentApi.INTERNAL_NETWORK);
            nets.addAll(i.extraNetworks());
            endpoints.add(endpoint("DOCKER_NETWORK", "From containers on Docker networks: " + String.join(", ", nets),
                    i.containerName(), 5432, "require", database, role, secret));
            if (i.hostPort() != null && "127.0.0.1".equals(i.bindAddress())) {
                endpoints.add(endpoint("HOST", "From the server itself or through an SSH tunnel", "127.0.0.1", i.hostPort(),
                        "require", database, role, secret));
            } else if (i.hostPort() != null) {
                String host = props.publicHost() == null || props.publicHost().isBlank() ? "<server-address>" : props.publicHost();
                endpoints.add(endpoint("PUBLIC", "From anywhere (TLS required; use verify-full with the downloaded CA)", host,
                        i.hostPort(), "require", database, role, secret));
            }
        } else {
            endpoints.add(endpoint("EXTERNAL", "As registered", i.host(), i.port(), i.sslMode(), database, role, secret));
        }
        return new ConnectionInfo(database, role, passwordStored, endpoints, i.managed() ? "/api/tls/instances-ca" : null);
    }

    static Endpoint endpoint(String kind, String description, String host, int port, String sslMode, String db, String role,
            String password) {
        String uri = "postgresql://" + enc(role) + ":" + (MASK.equals(password) ? MASK : enc(password)) + "@" + host + ":" + port
                + "/" + enc(db) + "?sslmode=" + sslMode;
        return new Endpoint(kind, description, host, port, sslMode, uri);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
