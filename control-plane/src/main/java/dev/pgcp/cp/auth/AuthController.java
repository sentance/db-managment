package dev.pgcp.cp.auth;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.pgcp.cp.audit.AuditService;
import dev.pgcp.cp.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record Status(boolean setupRequired, boolean authenticated, String username, Instant stepUpValidUntil,
            Long recoveryCodesLeft) {
    }

    public record SetupRequest(String token, String username, String password) {
    }

    public record SetupResponse(String totpSecret, String otpauthUri) {
    }

    public record CodeRequest(String code) {
    }

    public record SetupComplete(String username, List<String> recoveryCodes) {
    }

    public record LoginRequest(String username, String password, String code) {
    }

    private static final String SETUP_ATTR = "pgcp.setupAdminId";
    private static final String SETUP_USER_ATTR = "pgcp.setupUsername";

    private final AdminAccountService accounts;
    private final SetupTokenService setupToken;
    private final LoginRateLimiter limiter;
    private final StepUp stepUp;
    private final AuditService audit;
    private final SecurityContextRepository contextRepository;

    public AuthController(AdminAccountService accounts, SetupTokenService setupToken, LoginRateLimiter limiter,
            StepUp stepUp, AuditService audit, SecurityContextRepository contextRepository) {
        this.accounts = accounts;
        this.setupToken = setupToken;
        this.limiter = limiter;
        this.stepUp = stepUp;
        this.audit = audit;
        this.contextRepository = contextRepository;
    }

    @GetMapping("/status")
    public Status status(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean authenticated = auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName());
        String username = authenticated ? auth.getName() : null;
        return new Status(accounts.setupRequired(), authenticated, username,
                authenticated ? StepUp.validUntil(request.getSession(false)) : null,
                authenticated ? accounts.remainingRecoveryCodes(username) : null);
    }

    @PostMapping("/setup")
    public ResponseEntity<SetupResponse> setup(@RequestBody SetupRequest req, HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        limiter.check("setup:" + ip);
        if (!accounts.setupRequired()) {
            throw ApiException.conflict("setup already completed");
        }
        if (!setupToken.matches(req.token())) {
            limiter.recordFailure("setup:" + ip);
            audit.failure("auth.setup", "admin", req.username(), Map.of("reason", "invalid setup token"));
            throw ApiException.forbidden("invalid setup token");
        }
        var pending = accounts.beginSetup(req.username(), req.password());
        HttpSession session = request.getSession(true);
        session.setAttribute(SETUP_ATTR, pending.adminId());
        session.setAttribute(SETUP_USER_ATTR, req.username());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new SetupResponse(pending.totpSecret(), Totp.otpauthUri("pgcp", req.username(), pending.totpSecret())));
    }

    @PostMapping("/setup/totp")
    public ResponseEntity<SetupComplete> completeSetup(@RequestBody CodeRequest req, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        HttpSession session = request.getSession(false);
        if (session == null || !(session.getAttribute(SETUP_ATTR) instanceof Long adminId)) {
            throw ApiException.badRequest("setup session expired, start again");
        }
        String username = (String) session.getAttribute(SETUP_USER_ATTR);
        List<String> codes = accounts.completeSetup(adminId, req.code());
        setupToken.consume();
        signIn(username, request, response);
        audit.success("auth.setup", "admin", username, Map.of());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new SetupComplete(username, codes));
    }

    @PostMapping("/login")
    public Status login(@RequestBody LoginRequest req, HttpServletRequest request, HttpServletResponse response) {
        String ipKey = "ip:" + request.getRemoteAddr();
        String userKey = "user:" + String.valueOf(req.username()).toLowerCase();
        limiter.check(ipKey, userKey);
        var admin = accounts.authenticate(req.username(), req.password(), req.code());
        if (admin.isEmpty()) {
            limiter.recordFailure(ipKey, userKey);
            audit.failure("auth.login", "admin", req.username(), Map.of());
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                    "invalid username, password or code");
        }
        limiter.reset(ipKey, userKey);
        signIn(admin.get().username(), request, response);
        audit.success("auth.login", "admin", admin.get().username(), Map.of());
        return status(request);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        audit.success("auth.logout", "admin", null, Map.of());
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/step-up")
    public Map<String, Instant> stepUp(@RequestBody CodeRequest req, HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String key = "stepup:" + auth.getName();
        limiter.check(key);
        if (!accounts.verifyTotp(auth.getName(), req.code())) {
            limiter.recordFailure(key);
            audit.failure("auth.step_up", "admin", auth.getName(), Map.of());
            throw ApiException.forbidden("authenticator code is not valid");
        }
        limiter.reset(key);
        Instant until = stepUp.grant(request.getSession());
        audit.success("auth.step_up", "admin", auth.getName(), Map.of());
        return Map.of("validUntil", until);
    }

    /** Issues a fresh set of recovery codes (shown once) and invalidates the old ones. */
    @PostMapping("/recovery-codes")
    public ResponseEntity<Map<String, List<String>>> regenerateRecoveryCodes() {
        stepUp.require();
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        List<String> codes = accounts.regenerateRecoveryCodes(username);
        audit.success("auth.recovery_codes_regenerated", "admin", username, Map.of());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("recoveryCodes", codes));
    }

    private void signIn(String username, HttpServletRequest request, HttpServletResponse response) {
        HttpSession old = request.getSession(false);
        if (old != null) {
            old.invalidate();
        }
        request.getSession(true);
        var auth = UsernamePasswordAuthenticationToken.authenticated(username, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
    }
}
