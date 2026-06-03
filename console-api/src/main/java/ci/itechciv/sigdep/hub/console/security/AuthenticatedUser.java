package ci.itechciv.sigdep.hub.console.security;

import io.jsonwebtoken.Claims;

/**
 * Principal posé dans le SecurityContext par {@link JwtAuthFilter} pour une
 * requête authentifiée. Reconstruit à partir des claims du JWT — c'est la vue
 * « par requête » de l'utilisateur (à ne pas confondre avec {@link UserPrincipal},
 * utilisé au moment du login pour la vérification du mot de passe).
 *
 * Expose la portée géo (region/district/site) lue par {@link AuthScope}.
 */
public record AuthenticatedUser(
        Long id,
        String email,
        String displayName,
        String role,
        String userLevel,
        Long regionId,
        Long districtId,
        Long siteId) {

    public static AuthenticatedUser fromClaims(Claims claims) {
        return new AuthenticatedUser(
                claimAsLong(claims, "uid"),
                claims.getSubject(),
                claims.get("name", String.class),
                claims.get("role", String.class),
                claims.get("level", String.class),
                claimAsLong(claims, "regionId"),
                claimAsLong(claims, "districtId"),
                claimAsLong(claims, "siteId"));
    }

    private static Long claimAsLong(Claims claims, String name) {
        Object raw = claims.get(name);
        if (raw == null) return null;
        if (raw instanceof Number n) return n.longValue();
        if (raw instanceof String s && !s.isBlank()) {
            try { return Long.parseLong(s.trim()); }
            catch (NumberFormatException ex) { return null; }
        }
        return null;
    }
}
