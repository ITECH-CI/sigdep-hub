package ci.itechciv.sigdep.hub.console.auth;

import ci.itechciv.sigdep.hub.console.auth.AuthService.Tokens;
import ci.itechciv.sigdep.hub.console.security.AuthenticatedUser;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final PasswordResetService passwordReset;

    public AuthController(AuthService auth, PasswordResetService passwordReset) {
        this.auth = auth;
        this.passwordReset = passwordReset;
    }

    public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {}
    public record RefreshRequest(@NotBlank String refreshToken) {}
    public record LogoutRequest(String refreshToken) {}
    public record ForgotRequest(@Email @NotBlank String email) {}
    public record ResetRequest(@NotBlank String token, @NotBlank @Size(min = 8) String password) {}
    public record ChangeRequest(@NotBlank String currentPassword,
                                @NotBlank @Size(min = 8) String newPassword) {}

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

    /** L'utilisateur courant met à jour son propre profil (nom affiché). */
    @PostMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateMe(@AuthenticationPrincipal AuthenticatedUser user,
                         @RequestBody @Validated UpdateProfileRequest req) {
        auth.updateOwnProfile(user.id(), req.displayName());
    }

    public record UpdateProfileRequest(@NotBlank String displayName) {}

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestBody(required = false) LogoutRequest req) {
        if (req != null && req.refreshToken() != null) {
            auth.logout(req.refreshToken());
        }
    }

    /**
     * Mot de passe oublié : envoie un lien de réinitialisation si le compte
     * existe. Répond toujours 204 (anti-énumération : ne révèle pas si l'email
     * correspond à un compte).
     */
    @PostMapping("/password/forgot")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forgot(@RequestBody @Validated ForgotRequest req) {
        passwordReset.requestReset(req.email());
    }

    /** Vérifie qu'un token de réinitialisation est encore valide. */
    @GetMapping("/password/validate")
    public ValidityResponse validate(@RequestParam("token") String token) {
        return new ValidityResponse(passwordReset.isValid(token));
    }

    /** Applique le nouveau mot de passe via un token valide. */
    @PostMapping("/password/reset")
    public ResponseEntity<Void> reset(@RequestBody @Validated ResetRequest req) {
        boolean ok = passwordReset.reset(req.token(), req.password());
        return ok ? ResponseEntity.noContent().build()
                  : ResponseEntity.status(HttpStatus.GONE).build(); // 410 : token invalide/expiré
    }

    public record ValidityResponse(boolean valid) {}

    /**
     * Changement de mot de passe par l'utilisateur authentifié (ancien +
     * nouveau). Sert notamment au changement forcé après un login avec mot de
     * passe temporaire. Endpoint authentifié (JWT requis).
     */
    @PostMapping("/password/change")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void change(@AuthenticationPrincipal AuthenticatedUser user,
                       @RequestBody @Validated ChangeRequest req) {
        auth.changePassword(user.id(), req.currentPassword(), req.newPassword());
    }

    /** Identifiants invalides / compte désactivé / refresh expiré → 401. */
    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ErrorResponse onAuthError(AuthenticationException ex) {
        return new ErrorResponse("unauthorized", ex.getMessage());
    }

    public record ErrorResponse(String error, String message) {}
}
