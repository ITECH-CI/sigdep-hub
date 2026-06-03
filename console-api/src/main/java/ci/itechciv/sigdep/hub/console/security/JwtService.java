package ci.itechciv.sigdep.hub.console.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.MacAlgorithm;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Émet et vérifie les access tokens JWT signés en HS256 (clé symétrique en
 * {@code .env}). Les claims portent l'identité et la portée géo :
 * {@code sub} (email), {@code uid}, {@code name}, {@code role}, et
 * {@code regionId}/{@code districtId}/{@code siteId} quand l'utilisateur est
 * zone-bound (consommés par {@link AuthScope}).
 *
 * Le refresh token n'est PAS un JWT : c'est un UUID opaque stocké en DB
 * (cf. RefreshToken), géré par {@code AuthService}.
 */
@Service
public class JwtService {

    /** Algorithme de signature figé (v2.0). */
    private static final MacAlgorithm ALG = Jwts.SIG.HS256;

    private final SecretKey key;
    private final Duration accessTtl;
    private final String issuer;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.access-ttl:PT1H}") Duration accessTtl,
            @Value("${app.jwt.issuer:sigdep-hub}") String issuer) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "app.jwt.secret doit faire au moins 32 octets pour HS256 (actuel : "
                            + keyBytes.length + "). Configurez SIGDEP_JWT_SECRET.");
        }
        this.key = Keys.hmacShaKeyFor(keyBytes);
        this.accessTtl = accessTtl;
        this.issuer = issuer;
    }

    /** Émet un access token pour le principal donné. */
    public String issueAccessToken(UserPrincipal user) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .issuer(issuer)
                .subject(user.getEmail())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTtl)))
                .claim("uid", user.getId())
                .claim("name", user.getDisplayName())
                .claim("role", user.getRole())
                .claim("level", user.getUserLevel());
        if (user.getRegionId() != null) builder.claim("regionId", user.getRegionId());
        if (user.getDistrictId() != null) builder.claim("districtId", user.getDistrictId());
        if (user.getSiteId() != null) builder.claim("siteId", user.getSiteId());
        // Mot de passe temporaire → le front force le changement après login.
        if (user.isPasswordExpired()) builder.claim("mustChangePassword", true);
        // HS256 explicite : JJWT choisirait sinon HS384/HS512 selon la taille
        // de la clé. On fige HS256 (décision v2.0).
        return builder.signWith(key, ALG).compact();
    }

    /**
     * Vérifie la signature + l'expiration et retourne les claims.
     *
     * @throws JwtException si le token est invalide, expiré ou mal signé.
     */
    public Claims parse(String token) throws JwtException {
        return Jwts.parser()
                .verifyWith(key)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Duration getAccessTtl() {
        return accessTtl;
    }
}
