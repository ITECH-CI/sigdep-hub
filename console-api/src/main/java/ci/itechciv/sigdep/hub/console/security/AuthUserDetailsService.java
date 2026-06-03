package ci.itechciv.sigdep.hub.console.security;

import ci.itechciv.sigdep.hub.domain.entity.AuthUser;
import ci.itechciv.sigdep.hub.domain.repository.AuthUserRepository;
import ci.itechciv.sigdep.hub.domain.repository.UserGeoScopeRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Charge un utilisateur par email pour Spring Security, en y attachant sa
 * portée géographique. Le login se fait par email (insensible à la casse).
 */
@Service
public class AuthUserDetailsService implements UserDetailsService {

    private final AuthUserRepository users;
    private final UserGeoScopeRepository scopes;

    public AuthUserDetailsService(AuthUserRepository users, UserGeoScopeRepository scopes) {
        this.users = users;
        this.scopes = scopes;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        AuthUser user = users.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new UsernameNotFoundException("Aucun compte pour " + email));
        return UserPrincipal.of(user, scopes.findByUserId(user.getId()).orElse(null));
    }
}
