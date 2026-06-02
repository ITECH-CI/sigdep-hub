package ci.itechciv.sigdep.hub.console.admin;

import ci.itechciv.sigdep.hub.console.auth.AuthService;
import ci.itechciv.sigdep.hub.domain.entity.AuthUser;
import ci.itechciv.sigdep.hub.domain.entity.UserGeoScope;
import ci.itechciv.sigdep.hub.domain.repository.AuthUserRepository;
import ci.itechciv.sigdep.hub.domain.repository.UserGeoScopeRepository;
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

    public UserAdminService(AuthUserRepository users,
                            UserGeoScopeRepository scopes,
                            PasswordEncoder encoder,
                            AuthService authService) {
        this.users = users;
        this.scopes = scopes;
        this.encoder = encoder;
        this.authService = authService;
    }

    // ---------- queries ------------------------------------------------------

    public UserPage list(String search, int page, int size) {
        int safeSize = Math.max(1, Math.min(200, size));
        int safePage = Math.max(0, page);
        String q = (search == null || search.isBlank()) ? null : search.trim();

        var result = users.search(q, PageRequest.of(safePage, safeSize));
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
        String email = requireEmail(req.email());
        if (users.existsByEmailIgnoreCase(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Un compte existe déjà pour " + email);
        }
        String role = requireRole(req.role());
        String level = levelFor(role, req.regionId(), req.districtId(), req.siteId());

        if (req.password() == null || req.password().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Mot de passe initial requis");
        }

        AuthUser u = new AuthUser();
        u.setEmail(email);
        u.setPasswordHash(encoder.encode(req.password()));
        u.setDisplayName(displayName(req.displayName(), email));
        u.setRole(role);
        u.setUserLevel(level);
        u.setActive(req.active() == null || req.active());
        u.setPasswordExpired(Boolean.TRUE.equals(req.passwordTemporary()));
        u = users.save(u);

        saveScope(u.getId(), level, req.regionId(), req.districtId(), req.siteId());
        return u.getId();
    }

    @Transactional
    public void update(Long id, UpdateUserRequest req) {
        AuthUser u = users.findById(id).orElseThrow(this::notFound);

        if (req.displayName() != null) u.setDisplayName(req.displayName());
        if (req.active() != null) u.setActive(req.active());

        String role = req.role() != null ? requireRole(req.role()) : u.getRole();
        String level = levelFor(role, req.regionId(), req.districtId(), req.siteId());
        u.setRole(role);
        u.setUserLevel(level);
        users.save(u);

        // La portée est toujours réécrite d'après la requête : passer un rôle
        // non-zoné efface le scope existant.
        scopes.deleteByUserId(id);
        saveScope(id, level, req.regionId(), req.districtId(), req.siteId());
    }

    @Transactional
    public void resetPassword(Long id, String password, boolean temporary) {
        if (password == null || password.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mot de passe requis");
        }
        AuthUser u = users.findById(id).orElseThrow(this::notFound);
        u.setPasswordHash(encoder.encode(password));
        u.setPasswordExpired(temporary);
        users.save(u);
        // Invalide les sessions en cours (refresh tokens) après un reset.
        authService.revokeAllForUser(id);
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
                u.getLastLoginAt() == null ? null : u.getLastLoginAt().toEpochMilli(),
                u.getCreatedAt() == null ? null : u.getCreatedAt().toEpochMilli(),
                s == null ? null : s.getRegionId(),
                s == null ? null : s.getDistrictId(),
                s == null ? null : s.getSiteId());
    }

    // ---------- DTOs ---------------------------------------------------------

    public record UserRow(
            Long id, String email, String displayName, String role, String userLevel,
            boolean active, boolean passwordExpired,
            Long lastLoginAt, Long createdAt,
            Long regionId, Long districtId, Long siteId) {}

    public record UserPage(List<UserRow> content, long total, int page, int size) {}

    public record UserDetail(
            Long id, String email, String displayName, String role, String userLevel,
            boolean active, boolean passwordExpired,
            Long lastLoginAt, Long createdAt,
            Long regionId, Long districtId, Long siteId) {}

    public record CreateUserRequest(
            String email, String displayName, String role,
            Boolean active, String password, Boolean passwordTemporary,
            Long regionId, Long districtId, Long siteId) {}

    public record UpdateUserRequest(
            String displayName, String role, Boolean active,
            Long regionId, Long districtId, Long siteId) {}

    public record ResetPasswordRequest(String password, boolean temporary) {}
}
