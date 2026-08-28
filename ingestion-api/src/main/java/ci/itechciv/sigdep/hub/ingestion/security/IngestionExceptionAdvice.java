package ci.itechciv.sigdep.hub.ingestion.security;

import ci.itechciv.sigdep.contracts.SyncBatchResponse;
import ci.itechciv.sigdep.hub.domain.service.SiteResolver.UnknownSiteException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Traduit en réponses 4xx explicites les échecs d'attribution de site des
 * endpoints d'ingestion. Sans cet advice, {@link UnknownSiteException}
 * remontait non gérée → HTTP 500 opaque.
 */
@RestControllerAdvice(basePackages = "ci.itechciv.sigdep.hub.ingestion.controller")
public class IngestionExceptionAdvice {

    /**
     * Site déclaré non concordant → 409 Conflict. Le corps est la
     * {@link SyncBatchResponse} déjà construite par {@link SiteGuard} (tous les
     * enregistrements rejetés, code {@code SITE_MISMATCH}), pour que l'agent la
     * traite comme un ACK de rejet total.
     */
    @ExceptionHandler(SiteMismatchException.class)
    public ResponseEntity<SyncBatchResponse> onSiteMismatch(SiteMismatchException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ex.response());
    }

    /**
     * Ni le {@code siteCode} ni le {@code locationUuid} ne correspondent à un
     * site connu → 422 Unprocessable Entity (le lot ne peut être rattaché).
     */
    @ExceptionHandler(UnknownSiteException.class)
    public ResponseEntity<String> onUnknownSite(UnknownSiteException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(ex.getMessage());
    }
}
