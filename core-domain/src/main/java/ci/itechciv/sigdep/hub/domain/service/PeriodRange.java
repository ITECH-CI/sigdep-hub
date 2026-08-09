package ci.itechciv.sigdep.hub.domain.service;

import java.time.LocalDate;

/**
 * Intervalle de dates d'une requête analytique : {@code from} et {@code to}
 * INCLUS tous les deux. Remplace l'ancien paramètre {@code months} relatif (qui
 * n'exprimait qu'une borne de début, la fin étant implicitement « aujourd'hui »)
 * par un intervalle explicite début → fin, permettant à l'utilisateur de choisir
 * une plage personnalisée en plus des raccourcis « N derniers mois ».
 *
 * <p>Sémantique des bornes : {@code from <= date <= to} pour une colonne de type
 * date pure ; pour une colonne horodatée (timestamp), l'équivalent inclusif est
 * {@code ts < to + 1 jour} (utiliser {@link #toExclusive()}).</p>
 */
public record PeriodRange(LocalDate from, LocalDate to) {

    /** Défaut historique : 12 mois glissants finissant aujourd'hui. */
    private static final int DEFAULT_MONTHS = 12;

    /** Garde-fou : on ne remonte pas au-delà de 10 ans en arrière. */
    private static final int MAX_YEARS_BACK = 10;

    public PeriodRange {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from et to sont requis");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException(
                    "from (" + from + ") doit être <= to (" + to + ")");
        }
    }

    /**
     * Résout l'intervalle depuis des bornes optionnelles reçues d'une requête :
     * <ul>
     *   <li>les deux absentes → 12 mois glissants finissant aujourd'hui ;</li>
     *   <li>seul {@code to} absent → to = aujourd'hui ;</li>
     *   <li>seul {@code from} absent → from = to − 12 mois ;</li>
     *   <li>{@code from > to} → échange (tolérant à une saisie inversée) ;</li>
     *   <li>{@code from} trop ancien → clampé à aujourd'hui − 10 ans.</li>
     * </ul>
     */
    public static PeriodRange resolve(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now();
        LocalDate end = (to != null) ? to : today;
        LocalDate start = (from != null) ? from : end.minusMonths(DEFAULT_MONTHS);

        if (start.isAfter(end)) {
            LocalDate tmp = start;
            start = end;
            end = tmp;
        }
        LocalDate floor = today.minusYears(MAX_YEARS_BACK);
        if (start.isBefore(floor)) {
            start = floor;
        }
        return new PeriodRange(start, end);
    }

    /**
     * Borne haute EXCLUSIVE pour les comparaisons sur colonne horodatée :
     * {@code to + 1 jour}. Un {@code ts < toExclusive()} capture ainsi toute la
     * journée du {@code to} inclus.
     */
    public LocalDate toExclusive() {
        return to.plusDays(1);
    }
}
