package ci.itechciv.sigdep.hub.console.admin;

import ci.itechciv.sigdep.hub.console.auth.AuthService;
import ci.itechciv.sigdep.hub.console.auth.PasswordResetService;
import ci.itechciv.sigdep.hub.console.mail.EmailService;
import ci.itechciv.sigdep.hub.domain.entity.AuthUser;
import ci.itechciv.sigdep.hub.domain.entity.UserGeoScope;
import ci.itechciv.sigdep.hub.domain.repository.AuthUserRepository;
import ci.itechciv.sigdep.hub.domain.repository.UserGeoScopeRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Gestion des comptes utilisateurs (auth v2.0, JPA) pour la page « Utilisateurs ».
 * Remplace {@code KeycloakAdminService}.
 *
 * Modèle : un compte = un email (login) + un rôle unique + un niveau
 * ({@code user_level}) + au plus une portée géographique. Le niveau est déduit
 * du rôle :
 *   - rôles zone-bound → REGION / DISTRICT / SITE (scope obligatoire)
 *   - autres rôles → NATIONAL (pas de scope)
 */
@Service
public class UserAdminService {

    /** Les 8 rôles métier. */
    public static final List<String> ROLES = List.of(
            "SUPER_ADMIN", "IT_ADMIN", "NATIONAL_VIEWER", "REGIONAL_COORD",
            "DISTRICT_COORD", "SITE_USER", "ANALYST", "AUDITOR");

    /** Rôles liés à une zone → niveau correspondant. */
    private static final Map<String, String> SCOPED_LEVEL = Map.of(
            "REGIONAL_COORD", "REGION",
            "DISTRICT_COORD", "DISTRICT",
            "SITE_USER", "SITE");

    private final AuthUserRepository users;
    private final UserGeoScopeRepository scopes;
    private final PasswordEncoder encoder;
    private final AuthService authService;
    private final PasswordResetService passwordReset;
    private final EmailService email;

    public UserAdminService(AuthUserRepository users,
                            UserGeoScopeRepository scopes,
                            PasswordEncoder encoder,
                            AuthService authService,
                            PasswordResetService passwordReset,
                            EmailService email) {
        this.users = users;
        this.scopes = scopes;
        this.encoder = encoder;
        this.authService = authService;
        this.passwordReset = passwordReset;
        this.email = email;
    }

    // ---------- queries ------------------------------------------------------

    public UserPage list(String search, int page, int size) {
        int safeSize = Math.max(1, Math.min(200, size));
        int safePage = Math.max(0, page);
        // Motif LIKE non-null, déjà en minuscules. Recherche vide → "%" (tout).
        String pattern = (search == null || search.isBlank())
                ? "%"
                : "%" + search.trim().toLowerCase() + "%";

        var result = users.search(pattern, PageRequest.of(safePage, safeSize));
        List<UserRow> rows = result.getContent().stream().map(this::toRow).toList();
        return new UserPage(rows, result.getTotalElements(), safePage, safeSize);
    }

    public UserDetail get(Long id) {
        AuthUser u = users.findById(id).orElseThrow(this::notFound);
        UserGeoScope scope = scopes.findByUserId(id).orElse(null);
        return toDetail(u, scope);
    }

    public List<String> availableRoles() {
        return ROLES;
    }

    // ---------- writes -------------------------------------------------------

    @Transactional
    public Long create(CreateUserRequest req) {
        String emailAddr = requireEmail(req.email());
        if (users.existsByEmailIgnoreCase(emailAddr)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Un compte existe déjà pour " + emailAddr);
        }
        String role = requireRole(req.role());
        String level = levelFor(role, req.regionId(), req.districtId(), req.siteId());

        // Mot de passe optionnel : s'il est absent, le compte est créé sans
        // mot de passe utilisable et l'utilisateur reçoit un email avec un lien
        // pour le définir lui-même (l'admin ne connaît jamais le mot de passe).
        boolean hasPassword = req.password() != null && !req.password().isBlank();

        AuthUser u = new AuthUser();
        u.setEmail(emailAddr);
        // Hash non-utilisable (placeholder aléatoire) tant qu'aucun mot de passe
        // n'est défini : aucun login possible avec une valeur connue.
        u.setPasswordHash(encoder.encode(hasPassword
                ? req.password()
                : java.util.UUID.randomUUID().toString()));
        u.setDisplayName(displayName(req.displayName(), emailAddr));
        u.setRole(role);
        u.setUserLevel(level);
        u.setActive(req.active() == null || req.active());
        u.setPasswordExpired(hasPassword && Boolean.TRUE.equals(req.passwordTemporary()));
        u.setPasswordExpiresAt(toInstant(req.passwordExpiresAt()));
        u = users.save(u);

        saveScope(u.getId(), level, req.regionId(), req.districtId(), req.siteId());

        // Sans mot de passe → email de bienvenue avec lien de définition.
        if (!hasPassword) {
            passwordReset.sendWelcome(u);
        }
        return u.getId();
    }

    @Transactional
    public void update(Long id, UpdateUserRequest req) {
        AuthUser u = users.findById(id).orElseThrow(this::notFound);

        // Résumé des changements (pour l'email de notification).
        List<String> changes = new java.util.ArrayList<>();

        if (req.displayName() != null && !req.displayName().equals(u.getDisplayName())) {
            changes.add("Nom : « " + u.getDisplayName() + " » → « " + req.displayName() + " »");
            u.setDisplayName(req.displayName());
        }
        if (req.active() != null && !req.active().equals(u.getActive())) {
            changes.add(req.active() ? "Compte réactivé" : "Compte désactivé");
            u.setActive(req.active());
        }

        String role = req.role() != null ? requireRole(req.role()) : u.getRole();
        if (!role.equals(u.getRole())) {
            changes.add("Rôle : " + u.getRole() + " → " + role);
        }

        // Date d'expiration du mot de passe : toujours réécrite d'après la
        // requête (null = pas d'expiration). Permet à l'admin de prolonger ou
        // de débloquer un compte dont le mot de passe a expiré.
        Instant newExpiry = toInstant(req.passwordExpiresAt());
        if (!java.util.Objects.equals(newExpiry, u.getPasswordExpiresAt())) {
            changes.add(newExpiry == null
                    ? "Expiration du mot de passe retirée"
                    : "Expiration du mot de passe mise à jour");
            u.setPasswordExpiresAt(newExpiry);
        }

        String level = levelFor(role, req.regionId(), req.districtId(), req.siteId());
        u.setRole(role);
        u.setUserLevel(level);
        users.save(u);

        // La portée est toujours réécrite d'après la requête : passer un rôle
        // non-zoné efface le scope existant.
        scopes.deleteByUserId(id);
        saveScope(id, level, req.regionId(), req.districtId(), req.siteId());

        if (!changes.isEmpty()) {
            notifyAccountChanged(u, String.join(" · ", changes));
        }
    }

    /** Email de notification de sécurité (best-effort). */
    private void notifyAccountChanged(AuthUser u, String summary) {
        email.send(u.getEmail(), "Votre compte SIGDEP-3 a été modifié",
                "account-changed",
                Map.of("displayName", u.getDisplayName(),
                       "email", u.getEmail(),
                       "changeSummary", summary));
    }

    @Transactional
    public void resetPassword(Long id, String password, boolean temporary) {
        if (password == null || password.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mot de passe requis");
        }
        AuthUser u = users.findById(id).orElseThrow(this::notFound);
        u.setPasswordHash(encoder.encode(password));
        u.setPasswordExpired(temporary);
        // Le reset par l'admin débloque un compte expiré : on repart de zéro
        // (plus d'expiration sur le nouveau mot de passe).
        u.setPasswordExpiresAt(null);
        users.save(u);
        // Invalide les sessions en cours (refresh tokens) après un reset.
        authService.revokeAllForUser(id);
        // Notifie l'utilisateur du changement de mot de passe (sécurité).
        email.send(u.getEmail(), "Votre mot de passe a été modifié",
                "reset-confirmed",
                Map.of("displayName", u.getDisplayName(), "email", u.getEmail()));
    }

    @Transactional
    public void setEnabled(Long id, boolean enabled) {
        AuthUser u = users.findById(id).orElseThrow(this::notFound);
        u.setActive(enabled);
        users.save(u);
        if (!enabled) authService.revokeAllForUser(id);
    }

    // ---------- helpers ------------------------------------------------------

    private void saveScope(Long userId, String level, Long regionId, Long districtId, Long siteId) {
        if ("NATIONAL".equals(level)) return; // pas de scope
        UserGeoScope s = new UserGeoScope();
        s.setUserId(userId);
        switch (level) {
            case "REGION"   -> s.setRegionId(regionId);
            case "DISTRICT" -> s.setDistrictId(districtId);
            case "SITE"     -> s.setSiteId(siteId);
            default -> { return; }
        }
        scopes.save(s);
    }

    /** Déduit le niveau depuis le rôle et valide que la portée requise est fournie. */
    private static String levelFor(String role, Long regionId, Long districtId, Long siteId) {
        String level = SCOPED_LEVEL.get(role);
        if (level == null) return "NATIONAL";
        boolean ok = switch (level) {
            case "REGION"   -> regionId != null;
            case "DISTRICT" -> districtId != null;
            case "SITE"     -> siteId != null;
            default -> false;
        };
        if (!ok) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le rôle " + role + " exige une zone (" + level.toLowerCase() + ")");
        }
        return level;
    }

    private static String requireRole(String role) {
        if (role == null || !ROLES.contains(role)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rôle invalide : " + role);
        }
        return role;
    }

    private static String requireEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email requis");
        }
        return email.trim().toLowerCase();
    }

    private static String displayName(String displayName, String email) {
        return (displayName == null || displayName.isBlank()) ? email : displayName.trim();
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Utilisateur introuvable");
    }

    private UserRow toRow(AuthUser u) {
        UserGeoScope s = scopes.findByUserId(u.getId()).orElse(null);
        return new UserRow(
                u.getId(), u.getEmail(), u.getDisplayName(), u.getRole(), u.getUserLevel(),
                Boolean.TRUE.equals(u.getActive()),
                Boolean.TRUE.equals(u.getPasswordExpired()),
                u.getPasswordExpiresAt() == null ? null : u.getPasswordExpiresAt().toEpochMilli(),
                u.getLastLoginAt() == null ? null : u.getLastLoginAt().toEpochMilli(),
                u.getCreatedAt() == null ? null : u.getCreatedAt().toEpochMilli(),
                s == null ? null : s.getRegionId(),
                s == null ? null : s.getDistrictId(),
                s == null ? null : s.getSiteId());
    }

    private UserDetail toDetail(AuthUser u, UserGeoScope s) {
        return new UserDetail(
                u.getId(), u.getEmail(), u.getDisplayName(), u.getRole(), u.getUserLevel(),
                Boolean.TRUE.equals(u.getActive()),
                Boolean.TRUE.equals(u.getPasswordExpired()),
                u.getPasswordExpiresAt() == null ? null : u.getPasswordExpiresAt().toEpochMilli(),
                u.getLastLoginAt() == null ? null : u.getLastLoginAt().toEpochMilli(),
                u.getCreatedAt() == null ? null : u.getCreatedAt().toEpochMilli(),
                s == null ? null : s.getRegionId(),
                s == null ? null : s.getDistrictId(),
                s == null ? null : s.getSiteId());
    }

    /** Convertit un epoch-millis (depuis le front) en Instant, ou null. */
    private static Instant toInstant(Long epochMillis) {
        return epochMillis == null ? null : Instant.ofEpochMilli(epochMillis);
    }

    // ---------- DTOs ---------------------------------------------------------

    public record UserRow(
            Long id, String email, String displayName, String role, String userLevel,
            boolean active, boolean passwordExpired, Long passwordExpiresAt,
            Long lastLoginAt, Long createdAt,
            Long regionId, Long districtId, Long siteId) {}

    public record UserPage(List<UserRow> content, long total, int page, int size) {}

    public record UserDetail(
            Long id, String email, String displayName, String role, String userLevel,
            boolean active, boolean passwordExpired, Long passwordExpiresAt,
            Long lastLoginAt, Long createdAt,
            Long regionId, Long districtId, Long siteId) {}

    public record CreateUserRequest(
            String email, String displayName, String role,
            Boolean active, String password, Boolean passwordTemporary,
            Long passwordExpiresAt,
            Long regionId, Long districtId, Long siteId) {}

    public record UpdateUserRequest(
            String displayName, String role, Boolean active,
            Long passwordExpiresAt,
            Long regionId, Long districtId, Long siteId) {}

    public record ResetPasswordRequest(String password, boolean temporary) {}
}
