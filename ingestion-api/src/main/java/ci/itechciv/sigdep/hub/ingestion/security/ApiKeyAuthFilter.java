package ci.itechciv.sigdep.hub.ingestion.security;

import ci.itechciv.sigdep.hub.domain.entity.ApiKey;
import ci.itechciv.sigdep.hub.domain.repository.ApiKeyRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authentifie les requêtes de l'agent sigdep-sync via le header
 * {@code X-API-Key: <uuid>}. La clé brute n'est jamais stockée : on retrouve
 * la (ou les) clé(s) actives par préfixe puis on compare le hash BCrypt.
 *
 * En cas de succès, pose dans le SecurityContext un principal {@code Long}
 * (le siteId lié à la clé) avec l'autorité {@code ROLE_SYNC_AGENT}.
 *
 * Une clé absente/invalide ne lève pas d'erreur ici : on laisse la chaîne de
 * sécurité renvoyer 401 sur les routes protégées.
 */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    static final String HEADER = "X-API-Key";
    static final String ROLE = "ROLE_SYNC_AGENT";

    private final ApiKeyRepository apiKeys;
    private final PasswordEncoder encoder;

    public ApiKeyAuthFilter(ApiKeyRepository apiKeys, PasswordEncoder encoder) {
        this.apiKeys = apiKeys;
        this.encoder = encoder;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String raw = request.getHeader(HEADER);
        if (raw != null && !raw.isBlank()
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            authenticate(raw.trim(), request);
        }
        chain.doFilter(request, response);
    }

    private void authenticate(String rawKey, HttpServletRequest request) {
        if (rawKey.length() < 8) return;
        String prefix = rawKey.substring(0, 8);
        for (ApiKey candidate : apiKeys.findByKeyPrefixAndRevokedAtIsNull(prefix)) {
            if (encoder.matches(rawKey, candidate.getKeyHash())) {
                var auth = new UsernamePasswordAuthenticationToken(
                        candidate.getSiteId(), null,
                        List.of(new SimpleGrantedAuthority(ROLE)));
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);
                touchLastUsed(candidate);
                return;
            }
        }
    }

    /** Met à jour last_used_at (best-effort : ne doit jamais faire échouer la requête). */
    private void touchLastUsed(ApiKey key) {
        try {
            key.setLastUsedAt(Instant.now());
            apiKeys.save(key);
        } catch (RuntimeException ex) {
            log.debug("Échec maj last_used_at pour la clé {} : {}", key.getKeyPrefix(), ex.toString());
        }
    }
}
