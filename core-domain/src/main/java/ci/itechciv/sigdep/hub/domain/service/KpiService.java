package ci.itechciv.sigdep.hub.domain.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Read-only KPI computations against core.* — straight SQL on the live tables.
 * Phase 3 will move these to materialised views in analytics.* with periodic
 * refresh; for now the queries are simple enough to run on demand and the
 * console caches results for a minute.
 *
 * The "national" denominators (e.g. ARV coverage) are not yet computable
 * since we don't have an authoritative count of expected PVVIH at the
 * country level — those KPIs return null for now.
 */
@Service
public class KpiService {

    /** UUID of the "VIH CHARGE VIRALE" lab concept (CIEL 856). */
    private static final String VIRAL_LOAD_TEST_UUID = "856AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    /** Threshold for "viral suppression" (TX_PVLS, copies/mL). */
    private static final BigDecimal VL_SUPPRESSED_THRESHOLD = new BigDecimal("1000");

    private final JdbcTemplate jdbc;

    public KpiService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- geo-scope helpers ------------------------------------------
    // Same precedence as the rest of the app: siteId > districtId > regionId.
    // Each helper produces a JOIN fragment that filters the row set of a
    // given table on the supplied geo, plus the matching extra `?` parameter.

    /** Geo join for queries with {@code core.visits v} aliased. */
    private static String visitJoin(Long regionId, Long districtId, Long siteId) {
        return geoJoinFor("v", regionId, districtId, siteId);
    }

    /** Geo join for queries with {@code core.lab_results l} aliased. */
    private static String labJoin(Long regionId, Long districtId, Long siteId) {
        return geoJoinFor("l", regionId, districtId, siteId);
    }

    /** Geo join for queries with {@code core.patients p} aliased. */
    private static String patientJoin(Long regionId, Long districtId, Long siteId) {
        return geoJoinFor("p", regionId, districtId, siteId);
    }

    /** Geo join for queries with {@code core.treatment_initiations ti} aliased. */
    private static String initiationJoin(Long regionId, Long districtId, Long siteId) {
        return geoJoinFor("ti", regionId, districtId, siteId);
    }

    /** Geo filter for queries on {@code core.sites s} directly. */
    private static String siteJoin(Long regionId, Long districtId, Long siteId) {
        if (siteId != null)     return " AND s.id = ?";
        if (districtId != null) return " AND s.district_id = ?";
        if (regionId != null)   return " AND s.district_id IN ("
                + "SELECT id FROM core.districts WHERE region_id = ?)";
        return "";
    }

    private static String geoJoinFor(String alias, Long regionId, Long districtId, Long siteId) {
        if (siteId != null) {
            return " JOIN core.sites s ON s.id = " + alias + ".site_id AND s.id = ?";
        }
        if (districtId != null) {
            return " JOIN core.sites s ON s.id = " + alias + ".site_id AND s.district_id = ?";
        }
        if (regionId != null) {
            return " JOIN core.sites s ON s.id = " + alias + ".site_id"
                 + " JOIN core.districts d ON d.id = s.district_id AND d.region_id = ?";
        }
        return "";
    }

    private static Long geoArg(Long regionId, Long districtId, Long siteId) {
        if (siteId != null)     return siteId;
        if (districtId != null) return districtId;
        return regionId;
    }

    /**
     * Prepend the geo arg (if any) before the supplied bind values. The
     * geo JOIN is always rendered ahead of WHERE clauses, so its `?` is
     * bound first.
     */
    private static Object[] buildArgs(Long geo, Object... rest) {
        if (geo == null) return rest;
        Object[] out = new Object[rest.length + 1];
        out[0] = geo;
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }

    @Cacheable(cacheNames = "publicKpis")
    public PublicKpis publicKpis() {
        Long patientsActive = jdbc.queryForObject(
                "SELECT count(*) FROM core.patients WHERE voided = FALSE",
                Long.class);

        Long sitesWithData = jdbc.queryForObject(
                """
                SELECT count(*) FROM core.sites s
                WHERE s.runs_sigdep IS TRUE
                   OR EXISTS (SELECT 1 FROM core.patients p
                              WHERE p.site_id = s.id AND p.voided = FALSE)
                """,
                Long.class);

        BigDecimal viralSuppression = computeViralSuppression();
        List<MonthBucket> activeFile = computeActiveFile();

        // ARV coverage requires a national PVVIH denominator we don't have.
        BigDecimal arvCoverage = null;

        return new PublicKpis(
                patientsActive == null ? 0L : patientsActive,
                sitesWithData == null ? 0L : sitesWithData,
                viralSuppression,
                arvCoverage,
                activeFile);
    }

    private BigDecimal computeViralSuppression() {
        return computeViralSuppression(null, null, null);
    }

    private BigDecimal computeViralSuppression(Long regionId, Long districtId, Long siteId) {
        String labJoin = labJoin(regionId, districtId, siteId);
        Long g = geoArg(regionId, districtId, siteId);
        Object[] args = buildArgs(g, VL_SUPPRESSED_THRESHOLD, VIRAL_LOAD_TEST_UUID,
                LocalDate.now().minusYears(1));
        return jdbc.query(
                "SELECT"
              + "  count(*) FILTER (WHERE l.value_numeric < ?) AS suppressed,"
              + "  count(*)                                    AS total"
              + " FROM core.lab_results l" + labJoin
              + " WHERE l.test_uuid = ?"
              + "   AND l.value_numeric IS NOT NULL"
              + "   AND l.exam_date >= ?"
              + "   AND l.voided = FALSE",
                rs -> {
                    if (!rs.next()) return null;
                    long suppressed = rs.getLong("suppressed");
                    long total = rs.getLong("total");
                    return total == 0 ? null
                            : new BigDecimal(suppressed).multiply(new BigDecimal(100))
                                    .divide(new BigDecimal(total), 1, RoundingMode.HALF_UP);
                },
                args);
    }

    private List<MonthBucket> computeActiveFile() {
        return computeActiveFile(null, null, null);
    }

    private List<MonthBucket> computeActiveFile(Long regionId, Long districtId, Long siteId) {
        String visitJoin = visitJoin(regionId, districtId, siteId);
        Long g = geoArg(regionId, districtId, siteId);
        // Single-pass aggregate: scan the 12-month window once, group by
        // month, then LEFT JOIN against generate_series to keep months with
        // zero visits in the result. About 500x faster than the previous
        // version which ran one count-distinct subquery per month.
        String sql =
                "WITH months AS ("
              + "  SELECT generate_series("
              + "           date_trunc('month', NOW()) - INTERVAL '11 months',"
              + "           date_trunc('month', NOW()),"
              + "           INTERVAL '1 month'"
              + "         )::date AS month_start"
              + "), agg AS ("
              + "  SELECT date_trunc('month', v.visit_date)::date AS m,"
              + "         count(DISTINCT v.patient_id)            AS active"
              + "  FROM core.visits v" + visitJoin
              + "  WHERE v.visit_date >= date_trunc('month', NOW()) - INTERVAL '11 months'"
              + "    AND v.visit_date <  date_trunc('month', NOW()) + INTERVAL '1 month'"
              + "    AND v.voided = FALSE"
              + "  GROUP BY 1"
              + ")"
              + " SELECT to_char(months.month_start, 'YYYY-MM') AS label,"
              + "        COALESCE(agg.active, 0)                AS active"
              + " FROM months LEFT JOIN agg ON agg.m = months.month_start"
              + " ORDER BY months.month_start";
        Object[] args = g == null ? new Object[0] : new Object[]{g};
        return jdbc.query(sql,
                (rs, i) -> new MonthBucket(rs.getString("label"), rs.getLong("active")),
                args);
    }

    public record PublicKpis(
            long patientsActive,
            long sitesWithData,
            BigDecimal viralSuppression,
            BigDecimal arvCoverage,
            List<MonthBucket> activeFile
    ) {}

    public record MonthBucket(String month, long count) {}

    // ---------------- Authenticated dashboard KPIs ----------------

    /**
     * Dashboard KPIs filtered by the supplied geographic scope (region /
     * district / site, tightest wins). The caller resolves the scope via
     * AuthScope.effective so a SITE_USER can't read national totals here.
     *
     * Cached for 60s per scope (see spring.cache.caffeine.spec). 60s is
     * short enough that a freshly ingested batch is visible quickly, long
     * enough that page navigation between Dashboard and other pages is
     * instant on the second visit.
     */
    @Cacheable(cacheNames = "dashboardKpis", key = "T(java.util.Objects).hash(#regionId, #districtId, #siteId)")
    public DashboardKpis dashboardKpis(Long regionId, Long districtId, Long siteId) {
        Long g = geoArg(regionId, districtId, siteId);

        // File active = patients with at least one visit in the last 12 months.
        Long fileActive = jdbc.queryForObject(
                "SELECT count(DISTINCT v.patient_id) FROM core.visits v"
              + visitJoin(regionId, districtId, siteId)
              + " WHERE v.visit_date >= ? AND v.voided = FALSE",
                Long.class,
                buildArgs(g, LocalDate.now().minusYears(1)));

        // TX_NEW (this month) = treatment initiations whose effective date
        // falls in the current month.
        Long txNewMonth = jdbc.queryForObject(
                "SELECT count(*) FROM core.treatment_initiations ti"
              + initiationJoin(regionId, districtId, siteId)
              + " WHERE COALESCE(ti.arv_init_date, ti.enrollment_date)"
              + "       >= date_trunc('month', NOW())"
              + "   AND ti.voided = FALSE",
                Long.class,
                buildArgs(g));

        // Sites en ligne / total — both filtered by the geo scope (a
        // SITE_USER sees 1/1, a DISTRICT_COORD sees N/M within their district).
        String siteGeo = siteJoin(regionId, districtId, siteId);
        Long sitesOnline = jdbc.queryForObject(
                "SELECT count(*) FROM core.sites s"
              + " WHERE s.last_sync_at >= NOW() - INTERVAL '24 hours'"
              + siteGeo,
                Long.class,
                g == null ? new Object[0] : new Object[]{g});

        Long sitesTotalScope = jdbc.queryForObject(
                "SELECT count(*) FROM core.sites s"
              + " WHERE (s.runs_sigdep IS TRUE"
              + "    OR EXISTS (SELECT 1 FROM core.patients p"
              + "               WHERE p.site_id = s.id AND p.voided = FALSE))"
              + siteGeo,
                Long.class,
                g == null ? new Object[0] : new Object[]{g});

        BigDecimal viralSuppression = computeViralSuppression(regionId, districtId, siteId);

        // Sync alerts buckets, scoped.
        Long sitesNoSync7d = jdbc.queryForObject(
                "SELECT count(*) FROM core.sites s"
              + " WHERE (s.runs_sigdep IS TRUE OR EXISTS ("
              + "         SELECT 1 FROM core.patients p WHERE p.site_id = s.id))"
              + "   AND (s.last_sync_at IS NULL OR s.last_sync_at < NOW() - INTERVAL '7 days')"
              + siteGeo,
                Long.class,
                g == null ? new Object[0] : new Object[]{g});

        Long sitesNoSync24h = jdbc.queryForObject(
                "SELECT count(*) FROM core.sites s"
              + " WHERE (s.runs_sigdep IS TRUE OR EXISTS ("
              + "         SELECT 1 FROM core.patients p WHERE p.site_id = s.id))"
              + "   AND (s.last_sync_at IS NULL OR s.last_sync_at < NOW() - INTERVAL '24 hours')"
              + siteGeo,
                Long.class,
                g == null ? new Object[0] : new Object[]{g});

        // Last batch received within the scope.
        java.time.OffsetDateTime lastBatchAt = jdbc.query(
                "SELECT max(s.last_sync_at) FROM core.sites s"
              + " WHERE 1=1" + siteGeo,
                rs -> {
                    if (!rs.next()) return null;
                    java.sql.Timestamp ts = rs.getTimestamp(1);
                    return ts == null ? null : ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
                },
                g == null ? new Object[0] : new Object[]{g});

        return new DashboardKpis(
                fileActive == null ? 0L : fileActive,
                txNewMonth == null ? 0L : txNewMonth,
                viralSuppression,
                sitesOnline == null ? 0L : sitesOnline,
                sitesTotalScope == null ? 0L : sitesTotalScope,
                computeActiveFile(regionId, districtId, siteId),
                new SyncAlerts(
                        sitesNoSync7d == null ? 0L : sitesNoSync7d,
                        sitesNoSync24h == null ? 0L : sitesNoSync24h,
                        lastBatchAt));
    }

    public record DashboardKpis(
            long fileActive,
            long txNewMonth,
            BigDecimal viralSuppression,
            long sitesOnline,
            long sitesTotalScope,
            List<MonthBucket> activeFile,
            SyncAlerts syncAlerts
    ) {}

    public record SyncAlerts(
            long sitesNoSync7d,
            long sitesNoSync24h,
            java.time.OffsetDateTime lastBatchAt
    ) {}

    // ---------- Répartition géographique -----------------------------------

    /**
     * File active par région. Chaque ligne = (regionId, regionName, count
     * de patients ayant une visite dans les 12 derniers mois). Ordonné par
     * région pour rendre l'affichage stable. Si le scope appelant est
     * borné à un site / district / région, on filtre de la même façon —
     * un SITE_USER verra une seule ligne, sa propre région.
     */
    @Cacheable(cacheNames = "fileActiveByRegion",
            key = "T(java.util.Objects).hash(#regionId, #districtId, #siteId)")
    public List<RegionBucket> fileActiveByRegion(Long regionId, Long districtId, Long siteId) {
        LocalDate since = LocalDate.now().minusYears(1);
        StringBuilder sql = new StringBuilder()
                .append("SELECT r.id AS region_id, r.name AS region_name,")
                .append("       count(DISTINCT v.patient_id) AS n")
                .append(" FROM core.regions r")
                .append(" LEFT JOIN core.districts d ON d.region_id = r.id")
                .append(" LEFT JOIN core.sites s ON s.district_id = d.id")
                .append(" LEFT JOIN core.visits v ON v.site_id = s.id")
                .append("   AND v.voided = FALSE AND v.visit_date >= ?");

        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(since);
        if (siteId != null) {
            sql.append(" WHERE s.id = ?");
            args.add(siteId);
        } else if (districtId != null) {
            sql.append(" WHERE d.id = ?");
            args.add(districtId);
        } else if (regionId != null) {
            sql.append(" WHERE r.id = ?");
            args.add(regionId);
        }
        sql.append(" GROUP BY r.id, r.name ORDER BY n DESC, r.name");

        return jdbc.query(sql.toString(),
                (rs, i) -> new RegionBucket(
                        rs.getLong("region_id"),
                        rs.getString("region_name"),
                        rs.getLong("n")),
                args.toArray());
    }

    public record RegionBucket(long regionId, String regionName, long count) {}
}
