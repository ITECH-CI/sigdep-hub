package ci.itechciv.sigdep.hub.console.auth;

import ci.itechciv.sigdep.hub.console.mail.EmailService;
import ci.itechciv.sigdep.hub.domain.entity.AuthUser;
import ci.itechciv.sigdep.hub.domain.entity.PasswordResetOtp;
import ci.itechciv.sigdep.hub.domain.repository.AuthUserRepository;
import ci.itechciv.sigdep.hub.domain.repository.PasswordResetOtpRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gestion des liens de réinitialisation / définition de mot de passe.
 *
 * Un « token » est une chaîne opaque de la forme {@code <id>.<secret>} :
 *   - {@code id} = clé primaire de la ligne {@code auth.password_reset_otp},
 *   - {@code secret} = UUID aléatoire, dont seul le hash BCrypt est stocké
 *     (colonne {@code otp_hash}). Le secret en clair n'existe que dans le lien
 *     envoyé par email — irrécupérable ensuite.
 *
 * Le même mécanisme sert au reset (mot de passe oublié) et à la définition
 * initiale (compte créé sans mot de passe), avec des TTL différents.
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private final AuthUserRepository users;
    private final PasswordResetOtpRepository tokens;
    private final PasswordEncoder encoder;
    private final EmailService email;
    private final AuthService authService;
    private final String publicUrl;
    private final Duration resetTtl;
    private final Duration welcomeTtl;

    public PasswordResetService(AuthUserRepository users,
                                PasswordResetOtpRepository tokens,
                                PasswordEncoder encoder,
                                EmailService email,
                                AuthService authService,
                                @Value("${app.public-url:http://localhost:9000}") String publicUrl,
                                @Value("${app.mail.reset-ttl:PT1H}") Duration resetTtl,
                                @Value("${app.mail.welcome-ttl:PT72H}") Duration welcomeTtl) {
        this.users = users;
        this.tokens = tokens;
        this.encoder = encoder;
        this.email = email;
        this.authService = authService;
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
        this.resetTtl = resetTtl;
        this.welcomeTtl = welcomeTtl;
    }

    /** Type de lien — détermine le TTL, le template et le sujet. */
    public enum Kind { RESET, WELCOME }

    // ---------- émission -----------------------------------------------------

    /**
     * Émet un lien « mot de passe oublié » et envoie l'email de reset.
     * Silencieux si l'email est inconnu (anti-énumération : l'appelant répond
     * toujours 200).
     */
    @Transactional
    public void requestReset(String emailAddr) {
        users.findByEmailIgnoreCase(emailAddr.trim())
                .filter(u -> Boolean.TRUE.equals(u.getActive()))
                // Un mot de passe EXPIRÉ ne peut PAS être réinitialisé en
                // self-service (sinon l'expiration ne servirait à rien) : seul
                // un admin débloque le compte.
                .filter(u -> !u.isPasswordTimeExpired(java.time.Instant.now()))
                .ifPresent(u -> sendLink(u, Kind.RESET));
    }

    /**
     * Émet un lien « définir mon mot de passe » et envoie l'email de bienvenue.
     * Appelé à la création d'un compte sans mot de passe.
     */
    @Transactional
    public void sendWelcome(AuthUser user) {
        sendLink(user, Kind.WELCOME);
    }

    /**
     * Réinitialisation déclenchée par un administrateur : envoie un lien de
     * reset à l'utilisateur. Action de confiance → débloque l'expiration
     * (efface password_expires_at) pour que l'utilisateur puisse redéfinir son
     * mot de passe même si le compte était expiré. L'admin ne connaît jamais
     * le nouveau mot de passe.
     */
    @Transactional
    public void sendAdminReset(AuthUser user) {
        if (user.getPasswordExpiresAt() != null) {
            user.setPasswordExpiresAt(null);
            users.save(user);
        }
        sendLink(user, Kind.RESET);
    }

    private void sendLink(AuthUser user, Kind kind) {
        // Un seul lien actif à la fois : on invalide les précédents.
        tokens.invalidateActiveForUser(user.getId());

        String secret = UUID.randomUUID().toString();
        Duration ttl = kind == Kind.WELCOME ? welcomeTtl : resetTtl;

        PasswordResetOtp row = new PasswordResetOtp();
        row.setUserId(user.getId());
        row.setOtpHash(encoder.encode(secret));
        row.setExpiresAt(Instant.now().plus(ttl));
        row = tokens.save(row);

        String token = row.getId() + "." + secret;
        String link = publicUrl + "/definir-mot-de-passe?token="
                + java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8);

        Map<String, Object> vars = Map.of(
                "displayName", user.getDisplayName(),
                "email", user.getEmail(),
                "role", user.getRole(),
                "link", link,
                "ttlText", humanize(ttl));

        if (kind == Kind.WELCOME) {
            email.send(user.getEmail(), "Bienvenue sur SIGDEP-3", "welcome-set-password", vars);
        } else {
            email.send(user.getEmail(), "Réinitialisation de votre mot de passe", "reset-password", vars);
        }
        log.info("Lien {} émis pour {}", kind, user.getEmail());
    }

    // ---------- validation + consommation -----------------------------------

    /** Vérifie qu'un token est encore valide (pour afficher ou non le formulaire). */
    @Transactional(readOnly = true)
    public boolean isValid(String token) {
        return resolve(token).isPresent();
    }

    /**
     * Applique le nouveau mot de passe si le token est valide, marque le token
     * utilisé, lève le drapeau password_expired, révoque les sessions et
     * envoie l'email de confirmation.
     *
     * @return true si appliqué, false si le token est invalide/expiré/utilisé.
     */
    @Transactional
    public boolean reset(String token, String newPassword) {
        Optional<PasswordResetOtp> resolved = resolve(token);
        if (resolved.isEmpty()) return false;
        PasswordResetOtp row = resolved.get();

        AuthUser user = users.findById(row.getUserId()).orElse(null);
        if (user == null) return false;

        user.setPasswordHash(encoder.encode(newPassword));
        user.setPasswordExpired(false);
        users.save(user);

        row.setUsedAt(Instant.now());
        tokens.save(row);

        authService.revokeAllForUser(user.getId());

        email.send(user.getEmail(), "Votre mot de passe a été modifié",
                "reset-confirmed",
                Map.of("displayName", user.getDisplayName(), "email", user.getEmail()));
        log.info("Mot de passe réinitialisé pour {}", user.getEmail());
        return true;
    }

    /** Décode {@code <id>.<secret>}, charge la ligne et vérifie hash + validité. */
    private Optional<PasswordResetOtp> resolve(String token) {
        if (token == null) return Optional.empty();
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) return Optional.empty();
        long id;
        try {
            id = Long.parseLong(token.substring(0, dot));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
        String secret = token.substring(dot + 1);
        return tokens.findById(id)
                .filter(t -> t.isUsable(Instant.now()))
                .filter(t -> encoder.matches(secret, t.getOtpHash()));
    }

    private static String humanize(Duration ttl) {
        long hours = ttl.toHours();
        if (hours >= 48) return (hours / 24) + " jours";
        if (hours >= 1) return hours + (hours > 1 ? " heures" : " heure");
        return ttl.toMinutes() + " minutes";
    }
}
