package ci.itechciv.sigdep.hub.ingestion.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ci.itechciv.sigdep.contracts.EntityType;
import ci.itechciv.sigdep.contracts.SyncBatchRequest;
import ci.itechciv.sigdep.hub.domain.entity.Site;
import ci.itechciv.sigdep.hub.domain.service.SiteResolver;
import ci.itechciv.sigdep.hub.domain.service.SiteResolver.UnknownSiteException;
import ci.itechciv.sigdep.hub.ingestion.log.SyncBatchLogger;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * Recoupement d'attribution de site : siteCode ↔ données (locationUuid) ↔ clé
 * API. La garde doit laisser passer un lot cohérent et rejeter (SITE_MISMATCH)
 * toute divergence, tout en tolérant les cas dégradés (locationUuid null =
 * vieil agent, principal null = profil dev).
 */
class SiteGuardTest {

    private static final String UUID_A = "SSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSS0064";
    private static final String UUID_B = "SSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSS2884";

    private SiteResolver resolver;
    private SyncBatchLogger auditLog;
    private SiteGuard guard;

    private Site siteA;
    private Site siteB;

    @BeforeEach
    void setUp() {
        resolver = mock(SiteResolver.class);
        auditLog = mock(SyncBatchLogger.class);
        guard = new SiteGuard(resolver, auditLog);

        siteA = mock(Site.class);
        lenient().when(siteA.getId()).thenReturn(1L);
        lenient().when(siteA.getCode()).thenReturn("00064");
        siteB = mock(Site.class);
        lenient().when(siteB.getId()).thenReturn(2L);
        lenient().when(siteB.getCode()).thenReturn("02884");

        // audit best-effort : ne doit rien casser
        lenient().when(auditLog.start(any(), any(), any(), anyString(), anyInt())).thenReturn(1L);
    }

    private static SyncBatchRequest<Object> batch(String siteCode, String locationUuid) {
        return new SyncBatchRequest<>(siteCode, locationUuid, UUID.randomUUID(),
                EntityType.PATIENTS, List.of(new Object(), new Object()));
    }

    private static Authentication keyFor(Long siteId) {
        return new UsernamePasswordAuthenticationToken(siteId, null, List.of());
    }

    @Test
    @DisplayName("Tout concorde (siteCode = données = clé) → site renvoyé, aucun rejet")
    void allConsistent_returnsSite() {
        when(resolver.resolve(eq("00064"), eq(UUID_A))).thenReturn(siteA);
        when(resolver.resolve(isNull(), eq(UUID_A))).thenReturn(siteA);

        Site out = guard.resolveAndGuard(batch("00064", UUID_A), "patients", keyFor(1L));

        assertSame(siteA, out);
    }

    @Test
    @DisplayName("locationUuid désigne un AUTRE site que siteCode → SITE_MISMATCH")
    void dataSiteDiffers_rejects() {
        when(resolver.resolve(eq("00064"), eq(UUID_B))).thenReturn(siteA); // siteCode gagne d'abord
        when(resolver.resolve(isNull(), eq(UUID_B))).thenReturn(siteB);    // mais données = site B

        assertThrows(SiteMismatchException.class,
                () -> guard.resolveAndGuard(batch("00064", UUID_B), "patients", keyFor(1L)));
    }

    @Test
    @DisplayName("La clé API est liée à un autre site que siteCode → SITE_MISMATCH")
    void keySiteDiffers_rejects() {
        when(resolver.resolve(eq("00064"), eq(UUID_A))).thenReturn(siteA);
        when(resolver.resolve(isNull(), eq(UUID_A))).thenReturn(siteA);

        assertThrows(SiteMismatchException.class,
                () -> guard.resolveAndGuard(batch("00064", UUID_A), "patients", keyFor(2L)));
    }

    @Test
    @DisplayName("locationUuid null (vieil agent) + clé concordante → accepté (garde dégradée)")
    void nullLocationUuid_stillChecksKey() {
        when(resolver.resolve(eq("00064"), isNull())).thenReturn(siteA);

        Site out = guard.resolveAndGuard(batch("00064", null), "patients", keyFor(1L));

        assertSame(siteA, out);
    }

    @Test
    @DisplayName("Principal null (profil dev) + données concordantes → accepté")
    void nullPrincipal_stillChecksData() {
        when(resolver.resolve(eq("00064"), eq(UUID_A))).thenReturn(siteA);
        when(resolver.resolve(isNull(), eq(UUID_A))).thenReturn(siteA);

        Site out = guard.resolveAndGuard(batch("00064", UUID_A), "patients", null);

        assertSame(siteA, out);
    }

    @Test
    @DisplayName("Ni siteCode ni locationUuid connus → UnknownSiteException propagée")
    void unknownSite_propagates() {
        when(resolver.resolve(anyString(), any()))
                .thenThrow(new UnknownSiteException("ZZZ", null));

        assertThrows(UnknownSiteException.class,
                () -> guard.resolveAndGuard(batch("ZZZ", null), "patients", null));
    }

    @Test
    @DisplayName("Rejet SITE_MISMATCH : réponse = tous rejetés, code SITE_MISMATCH")
    void rejection_countsAllRejected() {
        when(resolver.resolve(eq("00064"), eq(UUID_B))).thenReturn(siteA);
        when(resolver.resolve(isNull(), eq(UUID_B))).thenReturn(siteB);

        SiteMismatchException ex = assertThrows(SiteMismatchException.class,
                () -> guard.resolveAndGuard(batch("00064", UUID_B), "patients", null));

        assertEquals(2, ex.response().rejected());
        assertEquals(0, ex.response().accepted());
        assertEquals(SiteGuard.REJECT_CODE, ex.response().errors().get(0).code());
    }
}
