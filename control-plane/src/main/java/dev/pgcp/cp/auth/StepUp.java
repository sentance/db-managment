package dev.pgcp.cp.auth;

import java.time.Instant;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import dev.pgcp.cp.common.ApiException;
import dev.pgcp.cp.config.CpProperties;
import jakarta.servlet.http.HttpSession;

/** Sensitive actions require a recent TOTP confirmation in the current session. */
@Component
public class StepUp {

    static final String ATTR = "pgcp.stepUpUntil";

    private final CpProperties props;

    public StepUp(CpProperties props) {
        this.props = props;
    }

    public void require() {
        Instant until = validUntil(session());
        if (until == null || Instant.now().isAfter(until)) {
            throw ApiException.stepUpRequired();
        }
    }

    public Instant grant(HttpSession session) {
        Instant until = Instant.now().plus(props.stepUpValidity());
        session.setAttribute(ATTR, until);
        return until;
    }

    public static Instant validUntil(HttpSession session) {
        if (session == null) {
            return null;
        }
        Object v = session.getAttribute(ATTR);
        return v instanceof Instant i && Instant.now().isBefore(i) ? i : null;
    }

    private static HttpSession session() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest().getSession(false);
        }
        return null;
    }
}
