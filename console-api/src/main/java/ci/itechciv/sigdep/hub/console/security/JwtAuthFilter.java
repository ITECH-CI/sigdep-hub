package ci.itechciv.sigdep.hub.console.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authentifie les requêtes console portant un {@code Authorization: Bearer
 * <jwt>}. Vérifie la signature HS256 via {@link JwtService} et pose dans le
 * SecurityContext un {@link AuthenticatedUser} (principal) portant le rôle et
 * la portée géo lus depuis les claims.
 *
 * Un token absent ou invalide ne lève pas d'erreur ici : on laisse passer
 * sans authentification, et c'est la chaîne de sécurité qui renvoie 401 sur
 * les routes protégées.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final JwtService jwt;

    public JwtAuthFilter(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                Claims claims = jwt.parse(header.substring(BEARER.length()).trim());
                AuthenticatedUser principal = AuthenticatedUser.fromClaims(claims);
                var authToken = new UsernamePasswordAuthenticationToken(
                        principal, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + principal.role())));
                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authToken);
            } catch (JwtException | IllegalArgumentException ex) {
                // Token invalide/expiré : on n'authentifie pas, 401 plus loin.
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}
