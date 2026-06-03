package ci.itechciv.sigdep.hub.console.auth;

import ci.itechciv.sigdep.hub.console.security.AuthUserDetailsService;
import ci.itechciv.sigdep.hub.console.security.JwtService;
import ci.itechciv.sigdep.hub.console.security.UserPrincipal;
import ci.itechciv.sigdep.hub.domain.entity.AuthUser;
import ci.itechciv.sigdep.hub.domain.entity.RefreshToken;
import ci.itechciv.sigdep.hub.domain.repository.AuthUserRepository;
import ci.itechciv.sigdep.hub.domain.repository.RefreshTokenRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Logique d'authentification v2.0 : login (mot de passe → access JWT + refresh
 * token opaque), rotation du refresh, et logout (révocation).
 *
 * Le refresh token est un UUID stocké en DB (table {@code auth.refresh_tokens})
 * et renvoyé au client en JSON ; à chaque refresh on révoque l'ancien et on en
 * émet un nouveau (rotation), ce qui borne la fenêtre de rejeu.
 */
@Service
public class AuthService {

    private final AuthenticationManager authManager;
    private final AuthUserDetailsService userDetails;
    private final JwtService jwt;
    private final AuthUserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder encoder;
    private final Duration refreshTtl;

    public AuthService(AuthenticationManager authManager,
                       AuthUserDetailsService userDetails,
                       JwtService jwt,
                       AuthUserRepository users,
                       RefreshTokenRepository refreshTokens,
                       PasswordEncoder encoder,
                       @Value("${app.jwt.refresh-ttl:P7D}") Duration refreshTtl) {
        this.authManager = authManager;
        this.userDetails = userDetails;
        this.jwt = jwt;
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.encoder = encoder;
        this.refreshTtl = refreshTtl;
    }

    /** Tokens émis : access JWT (court) + refresh opaque (long). */
    public record Tokens(String accessToken, String refreshToken, long expiresInSeconds) {}

    /**
     * Vérifie email + mot de passe, met à jour {@code last_login_at} et émet
     * une paire de tokens.
     *
     * @throws AuthenticationException si les identifiants sont invalides ou le
     *                                 compte est désactivé.
     */
    @Transactional
    public Tokens login(String email, String password) {
        var authentication = authManager.authenticate(
                new UsernamePasswordAuthenticationToken(email, password));
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();

        // Mot de passe EXPIRÉ (date dépassée) → login refusé. Seul un admin peut
        // prolonger la date ou réinitialiser le mot de passe (le « mot de passe
        // oublié » self-service ne contourne pas l'expiration). À distinguer du
        // mot de passe « temporaire » qui, lui, laisse entrer et force le
        // changement après login.
        if (principal.isPasswordTimeExpired()) {
            throw new CredentialsExpiredException(
                    "Votre mot de passe a expiré. Contactez un administrateur.");
        }

        users.findById(principal.getId()).ifPresent(u -> {
            u.setLastLoginAt(Instant.now());
            users.save(u);
        });

        return issue(principal);
    }

    /** Met à jour le nom affiché du compte courant. */
    @Transactional
    public void updateOwnProfile(Long userId, String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw new BadCredentialsException("Nom requis");
        }
        AuthUser user = users.findById(userId)
                .orElseThrow(() -> new BadCredentialsException("Compte introuvable"));
        user.setDisplayName(displayName.trim());
        users.save(user);
    }

    /**
     * Changement de mot de passe par l'utilisateur authentifié (vérifie
     * l'ancien). Lève le drapeau « mot de passe temporaire » et révoque les
     * autres sessions. Utilisé après un login avec mot de passe temporaire,
     * ou volontairement par l'utilisateur.
     *
     * @throws BadCredentialsException si l'ancien mot de passe est incorrect.
     */
    @Transactional
    public void changePassword(Long userId, String currentPassword, String newPassword) {
        AuthUser user = users.findById(userId)
                .orElseThrow(() -> new BadCredentialsException("Compte introuvable"));
        if (!encoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BadCredentialsException("Mot de passe actuel incorrect");
        }
        user.setPasswordHash(encoder.encode(newPassword));
        user.setPasswordExpired(false); // n'était plus temporaire une fois changé
        users.save(user);
        revokeAllForUser(userId);
    }

    /**
     * Échange un refresh token valide contre une nouvelle paire de tokens, en
     * révoquant l'ancien refresh (rotation).
     *
     * @throws BadCredentialsException si le token est inconnu, révoqué ou expiré.
     */
    @Transactional
    public Tokens refresh(String refreshTokenValue) {
        UUID id = parseUuid(refreshTokenValue);
        RefreshToken stored = refreshTokens.findById(id)
                .filter(t -> t.isUsable(Instant.now()))
                .orElseThrow(() -> new BadCredentialsException("Refresh token invalide ou expiré"));

        stored.setRevoked(true);
        refreshTokens.save(stored);

        AuthUser user = users.findById(stored.getUserId())
                .filter(u -> Boolean.TRUE.equals(u.getActive()))
                .orElseThrow(() -> new BadCredentialsException("Compte introuvable ou désactivé"));

        UserPrincipal principal = (UserPrincipal) userDetails.loadUserByUsername(user.getEmail());
        return issue(principal);
    }

    /** Révoque le refresh token fourni (logout). Idempotent. */
    @Transactional
    public void logout(String refreshTokenValue) {
        try {
            refreshTokens.findById(parseUuid(refreshTokenValue)).ifPresent(t -> {
                t.setRevoked(true);
                refreshTokens.save(t);
            });
        } catch (IllegalArgumentException ignored) {
            // Token malformé : rien à révoquer.
        }
    }

    /** Révoque tous les refresh tokens d'un utilisateur (logout global / reset). */
    @Transactional
    public void revokeAllForUser(Long userId) {
        refreshTokens.revokeAllForUser(userId);
    }

    private Tokens issue(UserPrincipal principal) {
        String access = jwt.issueAccessToken(principal);

        RefreshToken refresh = new RefreshToken();
        refresh.setId(UUID.randomUUID());
        refresh.setUserId(principal.getId());
        refresh.setExpiresAt(Instant.now().plus(refreshTtl));
        refresh.setRevoked(false);
        refresh.setCreatedAt(Instant.now());
        refreshTokens.save(refresh);

        return new Tokens(access, refresh.getId().toString(), jwt.getAccessTtl().toSeconds());
    }

    private static UUID parseUuid(String value) {
        if (value == null) throw new BadCredentialsException("Refresh token manquant");
        return UUID.fromString(value.trim());
    }
}
