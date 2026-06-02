package ci.itechciv.sigdep.hub.console.security;

import ci.itechciv.sigdep.hub.domain.entity.AuthUser;
import ci.itechciv.sigdep.hub.domain.entity.UserGeoScope;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Principal Spring Security adossé à un {@link AuthUser}. Porte le rôle (mappé
 * en autorité {@code ROLE_*}) et la portée géographique (region/district/site)
 * pour que {@link JwtService} puisse poser les claims correspondants et que
 * {@link AuthScope} les relise après authentification.
 *
 * Le « username » exposé est l'email (identifiant de login).
 */
public class UserPrincipal implements UserDetails {

    private final Long id;
    private final String email;
    private final String passwordHash;
    private final String displayName;
    private final String role;
    private final String userLevel;
    private final boolean active;
    private final boolean passwordExpired;
    private final Long regionId;
    private final Long districtId;
    private final Long siteId;

    private UserPrincipal(AuthUser u, UserGeoScope scope) {
        this.id = u.getId();
        this.email = u.getEmail();
        this.passwordHash = u.getPasswordHash();
        this.displayName = u.getDisplayName();
        this.role = u.getRole();
        this.userLevel = u.getUserLevel();
        this.active = Boolean.TRUE.equals(u.getActive());
        this.passwordExpired = Boolean.TRUE.equals(u.getPasswordExpired());
        this.regionId = scope != null ? scope.getRegionId() : null;
        this.districtId = scope != null ? scope.getDistrictId() : null;
        this.siteId = scope != null ? scope.getSiteId() : null;
    }

    public static UserPrincipal of(AuthUser user, UserGeoScope scope) {
        return new UserPrincipal(user, scope);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Override public String getPassword() { return passwordHash; }
    @Override public String getUsername() { return email; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }

    /** Mot de passe « expiré » force le changement (PR3) ; on n'empêche pas le login ici. */
    @Override public boolean isCredentialsNonExpired() { return true; }

    @Override public boolean isEnabled() { return active; }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getDisplayName() { return displayName; }
    public String getRole() { return role; }
    public String getUserLevel() { return userLevel; }
    public boolean isPasswordExpired() { return passwordExpired; }
    public Long getRegionId() { return regionId; }
    public Long getDistrictId() { return districtId; }
    public Long getSiteId() { return siteId; }
}
