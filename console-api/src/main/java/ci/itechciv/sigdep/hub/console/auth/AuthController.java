package ci.itechciv.sigdep.hub.console.auth;

import ci.itechciv.sigdep.hub.console.auth.AuthService.Tokens;
import ci.itechciv.sigdep.hub.console.security.AuthenticatedUser;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.validation.annotation.Validated;

/**
 * Endpoints d'authentification v2.0 (Spring Security pur). {@code login},
 * {@code refresh}, {@code password/*} sont publics (cf. ConsoleSecurityConfig) ;
 * {@code me} et {@code logout} exigent un JWT valide.
 */
@RestController
@RequestMapping("/api/auth")
@Validated
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {}
    public record RefreshRequest(@NotBlank String refreshToken) {}
    public record LogoutRequest(String refreshToken) {}

    public record TokenResponse(String accessToken, String refreshToken,
                                String tokenType, long expiresIn) {
        static TokenResponse from(Tokens t) {
            return new TokenResponse(t.accessToken(), t.refreshToken(), "Bearer", t.expiresInSeconds());
        }
    }

    public record MeResponse(Long id, String email, String displayName,
                             String role, String userLevel,
                             Long regionId, Long districtId, Long siteId) {}

    @PostMapping("/login")
    public TokenResponse login(@RequestBody @Validated LoginRequest req) {
        return TokenResponse.from(auth.login(req.email(), req.password()));
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@RequestBody @Validated RefreshRequest req) {
        return TokenResponse.from(auth.refresh(req.refreshToken()));
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal AuthenticatedUser user) {
        return new MeResponse(user.id(), user.email(), user.displayName(),
                user.role(), user.userLevel(),
                user.regionId(), user.districtId(), user.siteId());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestBody(required = false) LogoutRequest req) {
        if (req != null && req.refreshToken() != null) {
            auth.logout(req.refreshToken());
        }
    }

    /** Identifiants invalides / compte désactivé / refresh expiré → 401. */
    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ErrorResponse onAuthError(AuthenticationException ex) {
        return new ErrorResponse("unauthorized", ex.getMessage());
    }

    public record ErrorResponse(String error, String message) {}
}
