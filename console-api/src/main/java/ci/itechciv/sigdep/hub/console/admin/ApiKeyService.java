package ci.itechciv.sigdep.hub.console.admin;

import ci.itechciv.sigdep.hub.console.security.AuthenticatedUser;
import ci.itechciv.sigdep.hub.domain.entity.ApiKey;
import ci.itechciv.sigdep.hub.domain.repository.ApiKeyRepository;
import ci.itechciv.sigdep.hub.domain.repository.SiteRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Génération et révocation des clés API par site (auth de l'agent sigdep-sync).
 *
 * La clé est un UUID opaque. On ne stocke jamais le UUID brut : seul son hash
 * BCrypt et un préfixe de 8 chars (pour la recherche rapide côté ingestion).
 * Le UUID n'est renvoyé en clair qu'une seule fois, au moment de la génération.
 * Générer une nouvelle clé révoque la précédente (rotation).
 */
@Service
public class ApiKeyService {

    private final ApiKeyRepository apiKeys;
    private final SiteRepository sites;
    private final PasswordEncoder encoder;

    public ApiKeyService(ApiKeyRepository apiKeys, SiteRepository sites, PasswordEncoder encoder) {
        this.apiKeys = apiKeys;
        this.sites = sites;
        this.encoder = encoder;
    }

    /** Statut de la clé d'un site (sans jamais exposer le secret). */
    public record KeyStatus(boolean present, String prefix, Instant createdAt, Instant lastUsedAt) {
        static KeyStatus absent() { return new KeyStatus(false, null, null, null); }
    }

    /** Résultat d'une génération : le secret en clair, à afficher une seule fois. */
    public record GeneratedKey(String apiKey, String prefix) {}

    public KeyStatus status(Long siteId) {
        return apiKeys.findBySiteIdAndRevokedAtIsNull(siteId)
                .map(k -> new KeyStatus(true, k.getKeyPrefix(), k.getCreatedAt(), k.getLastUsedAt()))
                .orElseGet(KeyStatus::absent);
    }

    /**
     * Génère une nouvelle clé pour le site, révoquant l'éventuelle clé active.
     * @param createdBy l'admin authentifié qui effectue l'opération.
     */
    @Transactional
    public GeneratedKey generate(Long siteId, AuthenticatedUser createdBy) {
        if (!sites.existsById(siteId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Site introuvable");
        }
        // Révoque la clé active existante (rotation). Flush immédiat pour que
        // l'UPDATE (revoked_at) parte avant l'INSERT de la nouvelle clé :
        // l'index UNIQUE partiel (site_id WHERE revoked_at IS NULL) verrait
        // sinon deux clés actives au flush en fin de transaction.
        apiKeys.findBySiteIdAndRevokedAtIsNull(siteId).ifPresent(existing -> {
            existing.setRevokedAt(Instant.now());
            apiKeys.saveAndFlush(existing);
        });

        String rawKey = UUID.randomUUID().toString();
        String prefix = rawKey.substring(0, 8);

        ApiKey key = new ApiKey();
        key.setSiteId(siteId);
        key.setKeyHash(encoder.encode(rawKey));
        key.setKeyPrefix(prefix);
        key.setCreatedBy(createdBy != null && createdBy.id() != null ? createdBy.id() : 0L);
        apiKeys.save(key);

        return new GeneratedKey(rawKey, prefix);
    }

    @Transactional
    public void revoke(Long siteId) {
        apiKeys.findBySiteIdAndRevokedAtIsNull(siteId).ifPresent(key -> {
            key.setRevokedAt(Instant.now());
            apiKeys.save(key);
        });
    }
}
