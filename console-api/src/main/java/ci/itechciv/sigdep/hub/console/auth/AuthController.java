package ci.itechciv.sigdep.hub.console.auth;

import ci.itechciv.sigdep.hub.console.auth.AuthService.Tokens;
import ci.itechciv.sigdep.hub.console.security.AuthenticatedUser;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
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
    private final SsoCookieService ssoCookie;

    public AuthController(AuthService auth, PasswordResetService passwordReset,
                          SsoCookieService ssoCookie) {
        this.auth = auth;
        this.passwordReset = passwordReset;
        this.ssoCookie = ssoCookie;
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
    public ResponseEntity<TokenResponse> login(@RequestBody @Validated LoginRequest req) {
        Tokens t = auth.login(req.email(), req.password());
        return withSsoCookie(t);
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@RequestBody @Validated RefreshRequest req) {
        Tokens t = auth.refresh(req.refreshToken());
        return withSsoCookie(t);
    }

    /**
     * Renvoie les tokens en JSON ET dépose le cookie SSO (JWT d'accès, domaine
     * parent) pour que le sous-domaine Superset reconnaisse l'utilisateur.
     */
    private ResponseEntity<TokenResponse> withSsoCookie(Tokens t) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, ssoCookie.build(t.accessToken()).toString())
                .body(TokenResponse.from(t));
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
    public ResponseEntity<Void> logout(@RequestBody(required = false) LogoutRequest req) {
        if (req != null && req.refreshToken() != null) {
            auth.logout(req.refreshToken());
        }
        // Efface aussi le cookie SSO (déconnexion propre côté Superset).
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, ssoCookie.expire().toString())
                .build();
    }

    /**
     * Point de vérification pour le SSO Superset (appelé par nginx en
     * {@code auth_request}). Le JWT est lu soit dans {@code Authorization:
     * Bearer}, soit dans le cookie {@code sigdep_sso} (cf. JwtAuthFilter). Si
     * la requête est authentifiée, renvoie 200 + en-têtes d'identité que nginx
     * propage à Superset ; sinon le filtre de sécurité répond 401.
     */
    @GetMapping("/verify")
    public ResponseEntity<Void> verify(@AuthenticationPrincipal AuthenticatedUser user) {
        return ResponseEntity.ok()
                .header("X-Remote-User", user.email())
                .header("X-Remote-Role", user.role())
                .header("X-Remote-Name", user.displayName() == null ? "" : user.displayName())
                .build();
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
