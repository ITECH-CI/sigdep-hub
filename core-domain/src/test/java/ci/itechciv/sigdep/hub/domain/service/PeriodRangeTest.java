package ci.itechciv.sigdep.hub.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Résolution de l'intervalle de période (défaut, inversion, clamp, bornes). */
class PeriodRangeTest {

    @Test
    @DisplayName("Deux bornes absentes → 12 mois glissants finissant aujourd'hui")
    void bothNull_defaultsTo12Months() {
        LocalDate today = LocalDate.now();
        PeriodRange p = PeriodRange.resolve(null, null);
        assertEquals(today, p.to());
        assertEquals(today.minusMonths(12), p.from());
    }

    @Test
    @DisplayName("to absent → to = aujourd'hui ; from conservé")
    void toNull_endsToday() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        PeriodRange p = PeriodRange.resolve(from, null);
        assertEquals(from, p.from());
        assertEquals(LocalDate.now(), p.to());
    }

    @Test
    @DisplayName("from absent → from = to − 12 mois")
    void fromNull_backs12MonthsFromTo() {
        LocalDate to = LocalDate.of(2026, 6, 30);
        PeriodRange p = PeriodRange.resolve(null, to);
        assertEquals(to, p.to());
        assertEquals(to.minusMonths(12), p.from());
    }

    @Test
    @DisplayName("from > to → bornes échangées (saisie inversée tolérée)")
    void inverted_swaps() {
        PeriodRange p = PeriodRange.resolve(
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 1, 1));
        assertEquals(LocalDate.of(2026, 1, 1), p.from());
        assertEquals(LocalDate.of(2026, 6, 1), p.to());
    }

    @Test
    @DisplayName("from trop ancien → clampé à aujourd'hui − 10 ans")
    void tooOldFrom_clampedToFloor() {
        LocalDate ancient = LocalDate.now().minusYears(50);
        PeriodRange p = PeriodRange.resolve(ancient, LocalDate.now());
        assertEquals(LocalDate.now().minusYears(10), p.from());
    }

    @Test
    @DisplayName("toExclusive() = to + 1 jour (borne haute pour timestamps)")
    void toExclusive_isNextDay() {
        PeriodRange p = new PeriodRange(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
        assertEquals(LocalDate.of(2026, 2, 1), p.toExclusive());
    }

    @Test
    @DisplayName("Constructeur direct : from > to rejeté")
    void constructor_rejectsInverted() {
        assertThrows(IllegalArgumentException.class, () -> new PeriodRange(
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 1, 1)));
    }
}
