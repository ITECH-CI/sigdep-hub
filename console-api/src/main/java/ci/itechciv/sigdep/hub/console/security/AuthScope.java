package ci.itechciv.sigdep.hub.console.security;

import java.util.Collection;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Resolves the geo scope for the current request by combining:
 *   - the role-based ceiling encoded in the JWT (regionId/districtId/siteId
 *     claims for users who are bound to a specific zone),
 *   - the optional UI-supplied scope (?regionId=…&districtId=…&siteId=…).
 *
 * The JWT scope is the *upper bound*: if a user is a {@code DISTRICT_COORD}
 * for district 7, narrowing further via the UI is allowed (selecting one
 * site within that district), but broadening is silently clamped back to
 * the JWT scope. So an unauthorized broaden attempt becomes a no-op rather
 * than an error — the UI just shows what the user is allowed to see.
 *
 * Roles that are NOT zone-bound (SUPER_ADMIN, IT_ADMIN, NATIONAL_VIEWER,
 * AUDITOR, ANALYST) accept the UI scope as-is.
 *
 * Dev profile note: when no JWT is in the SecurityContext (the case under
 * the {@code dev} security filter that bypasses auth), the JWT ceiling is
 * "none" and the UI scope passes through. That preserves smoke-test ergonomics.
 */
@Component
public class AuthScope {

    /** Geo scope resolved for the current request — null id means "no constraint". */
    public record Scope(Long regionId, Long districtId, Long siteId) {
        public static final Scope NONE = new Scope(null, null, null);
    }

    /**
     * Combine the JWT ceiling with the UI request scope.
     *
     * @param uiRegion   regionId from the request, or null
     * @param uiDistrict districtId from the request, or null
     * @param uiSite     siteId from the request, or null
     */
    public Scope effective(Long uiRegion, Long uiDistrict, Long uiSite) {
        Scope ceiling = jwtCeiling();

        // Site-bound user: ignore everything else. UI can narrow to nothing.
        if (ceiling.siteId() != null) {
            return new Scope(null, null, ceiling.siteId());
        }

        // District-bound: UI can pick a site (must be in this district — we
        // don't validate site→district here for cost reasons; the listing
        // queries filter on district as well, so a forged siteId outside
        // the district returns nothing anyway).
        if (ceiling.districtId() != null) {
            Long site = uiSite;
            return new Scope(null, ceiling.districtId(), site);
        }

        // Region-bound: UI can pick a district (within the region) and/or site.
        if (ceiling.regionId() != null) {
            return new Scope(ceiling.regionId(), uiDistrict, uiSite);
        }

        // No JWT ceiling: pass UI scope through unchanged.
        return new Scope(uiRegion, uiDistrict, uiSite);
    }

    /** Read the geo scope from the current {@link AuthenticatedUser}, if any. */
    private Scope jwtCeiling() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return Scope.NONE;
        if (!(auth.getPrincipal() instanceof AuthenticatedUser user)) return Scope.NONE;

        // Roles that bypass scope entirely. Anyone with one of these holds a
        // "national" view; even if they happen to also have a regionId set
        // on their account, we treat that as inert. SUPER_ADMIN / IT_ADMIN
        // need full visibility to administer the platform.
        if (hasAnyAuthority(auth.getAuthorities(),
                "ROLE_SUPER_ADMIN", "ROLE_IT_ADMIN",
                "ROLE_NATIONAL_VIEWER", "ROLE_AUDITOR", "ROLE_ANALYST")) {
            return Scope.NONE;
        }

        return new Scope(user.regionId(), user.districtId(), user.siteId());
    }

    private static boolean hasAnyAuthority(Collection<? extends GrantedAuthority> authorities, String... names) {
        for (GrantedAuthority a : authorities) {
            for (String n : names) {
                if (n.equals(a.getAuthority())) return true;
            }
        }
        return false;
    }
}
