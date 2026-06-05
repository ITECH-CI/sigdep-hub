package ci.itechciv.sigdep.hub.console.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Cookie de SSO pour Superset (« Analyses avancées »).
 *
 * <p>Au login, console-api dépose un cookie {@code sigdep_sso} contenant le JWT
 * d'accès, portant {@code Domain=<domaine parent>} pour qu'il soit transmis au
 * sous-domaine Superset ({@code analytics.<domaine>}). nginx (vhost analytics)
 * le vérifie via {@code /api/auth/verify} (auth_request) et injecte l'identité
 * vers Superset, qui connecte alors l'utilisateur sans formulaire.
 *
 * <p>Le cookie est {@code HttpOnly} (jamais lu par du JS) et {@code SameSite=Lax}
 * (envoyé en navigation de premier niveau vers le sous-domaine, ce qui est
 * exactement le clic « Analyses avancées »).
 */
@Service
public class SsoCookieService {

    /** Nom du cookie. Aligné avec ce que lit l'endpoint /verify. */
    public static final String COOKIE_NAME = "sigdep_sso";

    private final String domain;
    private final boolean secure;
    private final Duration ttl;

    public SsoCookieService(
            @Value("${app.sso.cookie-domain:}") String domain,
            @Value("${app.sso.cookie-secure:false}") boolean secure,
            @Value("${app.jwt.access-ttl:PT1H}") Duration accessTtl) {
        this.domain = domain == null ? "" : domain.trim();
        this.secure = secure;
        this.ttl = accessTtl;
    }

    /** Cookie de session SSO portant le JWT d'accès (durée = TTL access token). */
    public ResponseCookie build(String accessToken) {
        return baseBuilder()
                .value(accessToken)
                .maxAge(ttl.getSeconds())
                .build();
    }

    /** Cookie d'expiration immédiate (déconnexion). */
    public ResponseCookie expire() {
        return baseBuilder().value("").maxAge(0).build();
    }

    private ResponseCookie.ResponseCookieBuilder baseBuilder() {
        ResponseCookie.ResponseCookieBuilder b = ResponseCookie.from(COOKIE_NAME)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/");
        if (!domain.isEmpty()) {
            b.domain(domain);
        }
        return b;
    }
}
