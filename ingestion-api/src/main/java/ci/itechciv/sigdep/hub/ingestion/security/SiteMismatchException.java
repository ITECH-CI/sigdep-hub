package ci.itechciv.sigdep.hub.ingestion.security;

import ci.itechciv.sigdep.contracts.SyncBatchResponse;

/**
 * Levée quand le site déclaré par un lot ({@code siteCode}) ne concorde pas
 * avec le site réel des données ({@code locationUuid} résolu via
 * {@code core.sites.source_uuid}) ou avec le site de la clé API authentifiée.
 *
 * <p>Motivation : un {@code siteCode} erroné, saisi à la main dans la config de
 * l'agent, a déjà fait attribuer des données au mauvais site. Le lot entier est
 * refusé (aucune écriture), traduit en HTTP 409 par
 * {@link IngestionExceptionAdvice}. La {@link SyncBatchResponse} portée ici est
 * renvoyée telle quelle au client (tous les enregistrements comptés comme
 * rejetés, code {@code SITE_MISMATCH}) ; l'audit a déjà été écrit par
 * {@link SiteGuard} avant que l'exception ne soit levée.
 */
public class SiteMismatchException extends RuntimeException {

    private final transient SyncBatchResponse response;

    public SiteMismatchException(String message, SyncBatchResponse response) {
        super(message);
        this.response = response;
    }

    public SyncBatchResponse response() {
        return response;
    }
}
