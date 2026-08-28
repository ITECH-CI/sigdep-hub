package ci.itechciv.sigdep.hub.ingestion.security;

import ci.itechciv.sigdep.contracts.SyncBatchRequest;
import ci.itechciv.sigdep.contracts.SyncBatchResponse;
import ci.itechciv.sigdep.contracts.SyncBatchResponse.RecordError;
import ci.itechciv.sigdep.hub.domain.entity.Site;
import ci.itechciv.sigdep.hub.domain.service.SiteResolver;
import ci.itechciv.sigdep.hub.ingestion.log.SyncBatchLogger;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Garde d'attribution de site, appelée par CHAQUE endpoint d'ingestion avant
 * d'écrire. Recoupe trois déclarations indépendantes du site et refuse le lot
 * (HTTP 409 via {@link SiteMismatchException}) à la moindre divergence :
 *
 * <ol>
 *   <li><b>siteCode</b> du lot — configuré sur l'agent, saisi par un humain,
 *       donc faillible ;</li>
 *   <li><b>locationUuid</b> du lot — uuid de la {@code location} OpenMRS
 *       réellement portée par les données (déterminé par le préflight de
 *       l'agent), résolu ici via {@code core.sites.source_uuid} ;</li>
 *   <li><b>site de la clé API</b> — le principal posé par
 *       {@link ApiKeyAuthFilter} est le {@code site_id} lié à la clé.</li>
 * </ol>
 *
 * <p>Contexte : un {@code siteCode} erroné a déjà fait attribuer les données
 * d'un établissement à un autre. Ici, si {@code locationUuid} ou la clé
 * désignent un site différent du {@code siteCode}, RIEN n'est écrit ; le lot
 * entier est rejeté et tracé (code {@code SITE_MISMATCH}, visible dans l'onglet
 * « Rejets »).
 *
 * <p>Rétro-compatibilité / profils :
 * <ul>
 *   <li>{@code locationUuid} {@code null} (agent antérieur à ce mécanisme) → on
 *       ne peut pas recouper les données, on garde la seule vérification
 *       {@code siteCode} ↔ clé ;</li>
 *   <li>principal {@code null} (profil {@code dev}, sécurité désactivée) → pas
 *       de clé à recouper, on garde {@code siteCode} ↔ {@code locationUuid}.</li>
 * </ul>
 */
@Component
public class SiteGuard {

    private static final Logger log = LoggerFactory.getLogger(SiteGuard.class);

    /** Code de rejet (littéral, cohérent avec UPSERT_FAILED / UNKNOWN_PATIENT). */
    public static final String REJECT_CODE = "SITE_MISMATCH";

    private final SiteResolver siteResolver;
    private final SyncBatchLogger auditLog;

    public SiteGuard(SiteResolver siteResolver, SyncBatchLogger auditLog) {
        this.siteResolver = siteResolver;
        this.auditLog = auditLog;
    }

    /**
     * Résout le site du lot et vérifie sa cohérence. Renvoie le {@link Site}
     * validé (à utiliser pour l'écriture), ou lève :
     * <ul>
     *   <li>{@link SiteResolver.UnknownSiteException} si ni le {@code siteCode}
     *       ni le {@code locationUuid} ne résolvent (mappée en 4xx par l'advice) ;</li>
     *   <li>{@link SiteMismatchException} (409 + audit déjà écrit) si les
     *       déclarations divergent.</li>
     * </ul>
     *
     * @param entityType   libellé d'entité pour l'audit (ex. "patients")
     * @param auth         authentification courante (peut être {@code null} en dev)
     */
    public Site resolveAndGuard(SyncBatchRequest<?> batch, String entityType, Authentication auth) {
        // 1) Site déclaré. Le fallback source_uuid permet de résoudre même si
        //    seul le locationUuid est exploitable ; UnknownSiteException sinon.
        Site declared = siteResolver.resolve(batch.siteCode(), batch.locationUuid());

        // 2) Recoupement avec le site RÉEL des données (locationUuid).
        if (batch.locationUuid() != null && !batch.locationUuid().isBlank()) {
            Site dataSite = siteResolver.resolve(null, batch.locationUuid());
            if (!dataSite.getId().equals(declared.getId())) {
                throw reject(batch, entityType, declared.getId(),
                        "siteCode='" + batch.siteCode() + "' (site " + declared.getCode()
                                + ") mais les données proviennent du site "
                                + dataSite.getCode() + " (locationUuid=" + batch.locationUuid()
                                + "). Lot refusé pour éviter une attribution erronée.");
            }
        }

        // 3) Recoupement avec le site de la clé API (si authentifié).
        Long keySiteId = keySiteId(auth);
        if (keySiteId != null && !keySiteId.equals(declared.getId())) {
            throw reject(batch, entityType, declared.getId(),
                    "la clé API est liée au site " + keySiteId + " mais le lot déclare le site "
                            + declared.getCode() + " (id " + declared.getId()
                            + "). Lot refusé : une clé ne peut pousser que pour son propre site.");
        }

        return declared;
    }

    /** Principal = {@code Long siteId} (cf. {@link ApiKeyAuthFilter}), ou null. */
    private static Long keySiteId(Authentication auth) {
        if (auth == null) return null;
        Object p = auth.getPrincipal();
        return (p instanceof Long l) ? l : null;
    }

    /**
     * Construit la réponse de rejet, écrit l'audit (batch + rejets) et renvoie
     * l'exception à lever. Tous les enregistrements sont comptés rejetés ; un
     * {@link RecordError} synthétique porte la cause (les DTO étant hétérogènes,
     * on ne rejette pas ligne par ligne : c'est le LOT qui est mal attribué).
     */
    private SiteMismatchException reject(SyncBatchRequest<?> batch, String entityType,
                                         Long declaredSiteId, String detail) {
        int n = batch.records() == null ? 0 : batch.records().size();
        log.warn("SITE_MISMATCH (batch {}, {} {} rec.) : {}",
                batch.batchId(), n, entityType, detail);

        List<RecordError> errors = List.of(new RecordError(null, REJECT_CODE, detail));

        // Audit : ouvre puis clôt une ligne audit.sync_batch (status=partial) +
        // une ligne audit.rejected_record (code SITE_MISMATCH), best-effort.
        Instant t0 = Instant.now();
        long auditId = auditLog.start(batch.batchId(), declaredSiteId, batch.siteCode(),
                entityType, n);
        auditLog.finish(auditId, t0, 0, n, errors, declaredSiteId, entityType);

        SyncBatchResponse response = new SyncBatchResponse(batch.batchId(), 0, n, errors);
        return new SiteMismatchException(detail, response);
    }
}
