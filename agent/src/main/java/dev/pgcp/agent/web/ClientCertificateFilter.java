package dev.pgcp.agent.web;

import java.io.IOException;
import java.security.cert.X509Certificate;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import dev.pgcp.agent.config.AgentProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * TLS client authentication (server.ssl.client-auth=need) proves the caller holds a certificate from our CA;
 * this filter additionally pins the subject CN so that other certificates from the same CA are rejected.
 * Actuator health stays reachable for the container healthcheck.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ClientCertificateFilter extends OncePerRequestFilter {

    private final String requiredCn;

    public ClientCertificateFilter(AgentProperties props) {
        this.requiredCn = props.clientCertCn();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return requiredCn == null || requiredCn.isBlank() || request.getRequestURI().equals("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        X509Certificate[] certs = (X509Certificate[]) request.getAttribute("jakarta.servlet.request.X509Certificate");
        if (certs == null || certs.length == 0 || !requiredCn.equals(commonName(certs[0]))) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "client certificate not accepted");
            return;
        }
        chain.doFilter(request, response);
    }

    static String commonName(X509Certificate cert) {
        try {
            for (Rdn rdn : new LdapName(cert.getSubjectX500Principal().getName()).getRdns()) {
                if ("CN".equalsIgnoreCase(rdn.getType())) {
                    return rdn.getValue().toString();
                }
            }
        } catch (InvalidNameException e) {
            return null;
        }
        return null;
    }
}
