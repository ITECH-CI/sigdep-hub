package ci.itechciv.sigdep.hub.domain.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Read-only clinical follow-up analytics. Reads core.visits, joining
 * sites/districts for the region filter.
 *
 * Two obs the source captures with non-canonical CIEL codes are still in
 * extra_data and read on-the-fly here:
 *  - 164487 → WHO stage (text, e.g. "WHO STAGE 1 ADULT")
 *  - 160108 → TB screening qualitative result
 */
@Service
public class ClinicService {

    private static final String WHO_STAGE_KEY = "164487AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String TB_SCREEN_KEY = "160108AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    private final JdbcTemplate jdbc;

    public ClinicService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static String geoFilter(Long regionId, Long districtId, Long siteId) {
        if (siteId != null) {
            return " JOIN core.sites s ON s.id = v.site_id AND s.id = ?";
        }
        if (districtId != null) {
            return " JOIN core.sites s ON s.id = v.site_id AND s.district_id = ?";
        }
        if (regionId != null) {
            return " JOIN core.sites s ON s.id = v.site_id"
                 + " JOIN core.districts d ON d.id = s.district_id AND d.region_id = ?";
        }
        return "";
    }

    private static Long geoArg(Long regionId, Long districtId, Long siteId) {
        if (siteId != null)     return siteId;
        if (districtId != null) return districtId;
        return regionId;
    }

    private static Object[] geoArgs(Long regionId, Long districtId, Long siteId, Object... rest) {
        Long g = geoArg(regionId, districtId, siteId);
        if (g == null) return rest;
        Object[] out = new Object[rest.length + 1];
        out[0] = g;
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }

    public ClinicSummary summary(int months, Long regionId, Long districtId, Long siteId) {
        LocalDate since = LocalDate.now().minusMonths(months);
        String region = geoFilter(regionId, districtId, siteId);

        Long visitsTotal = jdbc.queryForObject(
                "SELECT count(*) FROM core.visits v" + region
                        + " WHERE v.voided = FALSE",
                Long.class, geoArgs(regionId, districtId, siteId));

        Long visitsInPeriod = jdbc.queryForObject(
                "SELECT count(*) FROM core.visits v" + region
                        + " WHERE v.voided = FALSE AND v.visit_date >= ?",
                Long.class, geoArgs(regionId, districtId, siteId, since));

        Long withTbScreen = jdbc.queryForObject(
                "SELECT count(*) FROM core.visits v" + region
                        + " WHERE v.voided = FALSE AND v.visit_date >= ?"
                        + "   AND v.extra_data ?? ?",
                Long.class, geoArgs(regionId, districtId, siteId, since, TB_SCREEN_KEY));

        Long withWhoStage = jdbc.queryForObject(
                "SELECT count(*) FROM core.visits v" + region
                        + " WHERE v.voided = FALSE AND v.visit_date >= ?"
                        + "   AND v.extra_data ?? ?",
                Long.class, geoArgs(regionId, districtId, siteId, since, WHO_STAGE_KEY));

        BigDecimal tbScreenPct = pct(withTbScreen, visitsInPeriod);
        BigDecimal whoStagePct = pct(withWhoStage, visitsInPeriod);

        // Distributions
        // Concept UUIDs are hardcoded constants — inline them rather than
        // binding, so the geo arg (a Long) keeps its single `?` slot in the
        // JOIN clause without conflicting with a string `?` in the SELECT.
        List<Bucket> whoStageDist = jdbc.query(
                "SELECT v.extra_data->>'" + WHO_STAGE_KEY + "' AS k, count(*) AS n"
                        + " FROM core.visits v" + region
                        + " WHERE v.voided = FALSE AND v.visit_date >= ?"
                        + "   AND v.extra_data ?? '" + WHO_STAGE_KEY + "'"
                        + " GROUP BY k ORDER BY n DESC",
                (rs, i) -> new Bucket(rs.getString("k"), rs.getLong("n")),
                geoArgs(regionId, districtId, siteId, since));

        List<Bucket> tbScreenDist = jdbc.query(
                "SELECT v.extra_data->>'" + TB_SCREEN_KEY + "' AS k, count(*) AS n"
                        + " FROM core.visits v" + region
                        + " WHERE v.voided = FALSE AND v.visit_date >= ?"
                        + "   AND v.extra_data ?? '" + TB_SCREEN_KEY + "'"
                        + " GROUP BY k ORDER BY n DESC",
                (rs, i) -> new Bucket(rs.getString("k"), rs.getLong("n")),
                geoArgs(regionId, districtId, siteId, since));

        List<Bucket> arvDist = jdbc.query(
                "SELECT v.arv_regimen AS k, count(*) AS n FROM core.visits v" + region
                        + " WHERE v.voided = FALSE AND v.visit_date >= ?"
                        + "   AND v.arv_regimen IS NOT NULL"
                        + " GROUP BY k ORDER BY n DESC LIMIT 10",
                (rs, i) -> new Bucket(rs.getString("k"), rs.getLong("n")),
                geoArgs(regionId, districtId, siteId, since));

        // Monthly volumes for the Visites tab. Three series per month:
        //  - n_visits     : every clinical visit
        //  - n_disp       : visits that *also* carry an ARV dispensation
        //                   (arv_treatment_days IS NOT NULL). In SIGDEP
        //                   the dispensation is captured on the visit
        //                   itself, not as a separate encounter, so we
        //                   source the metric from core.visits rather
        //                   than the (legacy) core.dispensations table
        //                   which the agent never populates.
        //  - n_expected   : RDV planifiés tombant dans le mois (dérivés
        //                   de visits.next_visit_date), comparés aux
        //                   venus pour repérer les perdus de vue précoces.
        String monthlySql = "WITH months AS ("
                + " SELECT generate_series("
                + "   date_trunc('month', NOW()) - make_interval(months => ? - 1),"
                + "   date_trunc('month', NOW()),"
                + "   INTERVAL '1 month'"
                + " )::date AS month_start"
                + ") SELECT to_char(m.month_start, 'YYYY-MM') AS label,"
                + "   (SELECT count(*) FROM core.visits v" + region
                + "    WHERE v.voided = FALSE"
                + "      AND v.visit_date >= m.month_start"
                + "      AND v.visit_date <  m.month_start + INTERVAL '1 month') AS n_visits,"
                + "   (SELECT count(*) FROM core.visits v" + region
                + "    WHERE v.voided = FALSE"
                + "      AND v.arv_treatment_days IS NOT NULL"
                + "      AND v.visit_date >= m.month_start"
                + "      AND v.visit_date <  m.month_start + INTERVAL '1 month') AS n_disp,"
                + "   (SELECT count(*) FROM core.visits v" + region
                + "    WHERE v.voided = FALSE"
                + "      AND v.next_visit_date >= m.month_start"
                + "      AND v.next_visit_date <  m.month_start + INTERVAL '1 month') AS n_expected"
                + " FROM months m ORDER BY m.month_start";

        Long g = geoArg(regionId, districtId, siteId);
        List<Object> mArgs = new ArrayList<>();
        mArgs.add(months);
        if (g != null) mArgs.add(g);            // visits sub-query
        if (g != null) mArgs.add(g);            // dispensations sub-query (sur visits)
        if (g != null) mArgs.add(g);            // expected (visits.next_visit_date) sub-query

        List<MonthlyCount> monthly = jdbc.query(monthlySql,
                (rs, i) -> new MonthlyCount(
                        rs.getString("label"),
                        rs.getLong("n_visits"),
                        rs.getLong("n_disp"),
                        rs.getLong("n_expected")),
                mArgs.toArray());

        return new ClinicSummary(
                visitsTotal == null ? 0L : visitsTotal,
                visitsInPeriod == null ? 0L : visitsInPeriod,
                withTbScreen == null ? 0L : withTbScreen,
                withWhoStage == null ? 0L : withWhoStage,
                tbScreenPct,
                whoStagePct,
                monthly,
                whoStageDist,
                tbScreenDist,
                arvDist,
                months);
    }

    private static BigDecimal pct(Long n, Long d) {
        if (n == null || d == null || d == 0) return null;
        return new BigDecimal(n).multiply(new BigDecimal(100))
                .divide(new BigDecimal(d), 1, RoundingMode.HALF_UP);
    }

    private static final Map<String, String> VISIT_SORTABLE = Map.of(
            "date",       "v.visit_date",
            "patient",    "v.patient_id",
            "site",       "site.code",
            "weight",     "v.weight_kg",
            "bmi",        "v.bmi",
            "arvRegimen", "v.arv_regimen"
    );

    public VisitPage visits(int months, Long regionId, Long districtId, Long siteId,
                            String sort, String dir,
                            int page, int size) {
        int safeSize = Math.max(1, Math.min(500, size));
        int safePage = Math.max(0, page);
        int offset = safePage * safeSize;
        LocalDate since = LocalDate.now().minusMonths(months);

        // We already JOIN core.sites for the site code/name; piggy-back the
        // geo filter on that join when possible.
        String geoJoin;
        if (siteId != null) {
            geoJoin = " AND site.id = ?";
        } else if (districtId != null) {
            geoJoin = " AND site.district_id = ?";
        } else if (regionId != null) {
            geoJoin = " JOIN core.districts d ON d.id = site.district_id AND d.region_id = ?";
        } else {
            geoJoin = "";
        }

        List<Object> args = new ArrayList<>();
        Long g = geoArg(regionId, districtId, siteId);
        if (g != null) args.add(g);
        args.add(since);

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM core.visits v"
                        + " JOIN core.sites site ON site.id = v.site_id" + geoJoin
                        + " WHERE v.voided = FALSE AND v.visit_date >= ?",
                Long.class, args.toArray());

        List<Object> pagedArgs = new ArrayList<>(args);
        pagedArgs.add(safeSize);
        pagedArgs.add(offset);

        List<VisitRow> rows = jdbc.query(
                "SELECT v.id, v.visit_date, v.next_visit_date, v.source_form,"
                        + "       v.arv_regimen, v.arv_treatment_days, v.cotrim_treatment_days,"
                        + "       v.weight_kg, v.height_cm, v.bmi,"
                        + "       v.viral_load, v.cd4_count,"
                        + "       v.tpt_status, v.tpt_regimen,"
                        + "       v.extra_data->>'" + WHO_STAGE_KEY + "' AS who_stage,"
                        + "       v.extra_data->>'" + TB_SCREEN_KEY + "' AS tb_screen,"
                        + "       v.patient_id,"
                        + "       site.code AS site_code, site.name AS site_name,"
                        + "       (SELECT pi.identifier_value FROM core.patient_identifiers pi"
                        + "         WHERE pi.patient_id = v.patient_id"
                        + "         ORDER BY pi.id LIMIT 1) AS patient_code"
                        + " FROM core.visits v"
                        + " JOIN core.sites site ON site.id = v.site_id" + geoJoin
                        + " WHERE v.voided = FALSE AND v.visit_date >= ?"
                        + SortSpec.orderBy(sort, dir, VISIT_SORTABLE,
                                "v.visit_date DESC NULLS LAST, v.id DESC")
                        + " LIMIT ? OFFSET ?",
                (rs, i) -> {
                    short arvDays = rs.getShort("arv_treatment_days");
                    boolean arvDaysNull = rs.wasNull();
                    short ctxDays = rs.getShort("cotrim_treatment_days");
                    boolean ctxDaysNull = rs.wasNull();
                    int cd4 = rs.getInt("cd4_count");
                    boolean cd4Null = rs.wasNull();
                    return new VisitRow(
                            rs.getLong("id"),
                            rs.getDate("visit_date") == null ? null : rs.getDate("visit_date").toLocalDate(),
                            rs.getDate("next_visit_date") == null ? null : rs.getDate("next_visit_date").toLocalDate(),
                            rs.getString("source_form"),
                            rs.getString("arv_regimen"),
                            arvDaysNull ? null : arvDays,
                            ctxDaysNull ? null : ctxDays,
                            rs.getBigDecimal("weight_kg"),
                            rs.getBigDecimal("height_cm"),
                            rs.getBigDecimal("bmi"),
                            rs.getBigDecimal("viral_load"),
                            cd4Null ? null : cd4,
                            rs.getString("tpt_status"),
                            rs.getString("tpt_regimen"),
                            rs.getString("who_stage"),
                            rs.getString("tb_screen"),
                            rs.getLong("patient_id"),
                            rs.getString("patient_code"),
                            rs.getString("site_code"),
                            rs.getString("site_name"));
                },
                pagedArgs.toArray());

        return new VisitPage(rows, total == null ? 0L : total, safePage, safeSize);
    }

    // ---------------------------------------------------------------------
    // IVSA (Initiative pour la Visite Sans rendez-vous Améliorée) — sub-set
    // of the routine follow-up. A visit belongs to IVSA when its MSD code
    // is the "IVSA" track (concept 165063 = 2). We surface counts and a
    // dedicated paginated listing so the Clinique page can offer it as a
    // distinct tab without forking another module.
    // ---------------------------------------------------------------------

    public IvsaSummary ivsaSummary(int months, Long regionId, Long districtId, Long siteId) {
        LocalDate since = LocalDate.now().minusMonths(months);
        String region = geoFilter(regionId, districtId, siteId);

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM core.visits v" + region
                        + " WHERE v.voided = FALSE AND v.ivsa_msd_code = 'IVSA'",
                Long.class, geoArgs(regionId, districtId, siteId));

        Long inPeriod = jdbc.queryForObject(
                "SELECT count(*) FROM core.visits v" + region
                        + " WHERE v.voided = FALSE AND v.ivsa_msd_code = 'IVSA'"
                        + "   AND v.visit_date >= ?",
                Long.class, geoArgs(regionId, districtId, siteId, since));

        Long success = jdbc.queryForObject(
                "SELECT count(*) FROM core.visits v" + region
                        + " WHERE v.voided = FALSE AND v.ivsa_msd_code = 'IVSA'"
                        + "   AND v.ivsa_success_confirmation_date IS NOT NULL"
                        + "   AND v.ivsa_success_confirmation_date >= ?",
                Long.class, geoArgs(regionId, districtId, siteId, since));

        Long withAlertSigns = jdbc.queryForObject(
                "SELECT count(*) FROM core.visits v" + region
                        + " WHERE v.voided = FALSE AND v.ivsa_msd_code = 'IVSA'"
                        + "   AND v.visit_date >= ?"
                        + "   AND COALESCE(v.ivsa_alert_signs_count, 0) > 0",
                Long.class, geoArgs(regionId, districtId, siteId, since));

        BigDecimal successPct = pct(success, inPeriod);

        List<Bucket> msd = jdbc.query(
                "SELECT COALESCE(v.ivsa_msd_code, '(non renseigné)') AS k, count(*) AS n"
                        + " FROM core.visits v" + region
                        + " WHERE v.voided = FALSE AND v.visit_date >= ?"
                        + "   AND v.ivsa_msd_code IS NOT NULL"
                        + " GROUP BY k ORDER BY n DESC",
                (rs, i) -> new Bucket(rs.getString("k"), rs.getLong("n")),
                geoArgs(regionId, districtId, siteId, since));

        return new IvsaSummary(
                total == null ? 0L : total,
                inPeriod == null ? 0L : inPeriod,
                success == null ? 0L : success,
                withAlertSigns == null ? 0L : withAlertSigns,
                successPct,
                msd,
                months);
    }

    private static final Map<String, String> IVSA_SORTABLE = Map.of(
            "date",    "v.visit_date",
            "patient", "v.patient_id",
            "site",    "site.code",
            "alerts",  "v.ivsa_alert_signs_count",
            "neuro",   "v.ivsa_neuro_signs_count"
    );

    public IvsaPage ivsaVisits(int months, Long regionId, Long districtId, Long siteId,
                               String sort, String dir, int page, int size) {
        int safeSize = Math.max(1, Math.min(500, size));
        int safePage = Math.max(0, page);
        int offset = safePage * safeSize;
        LocalDate since = LocalDate.now().minusMonths(months);

        String geoJoin;
        if (siteId != null)          geoJoin = " AND site.id = ?";
        else if (districtId != null) geoJoin = " AND site.district_id = ?";
        else if (regionId != null)   geoJoin = " JOIN core.districts d ON d.id = site.district_id AND d.region_id = ?";
        else                         geoJoin = "";

        List<Object> args = new ArrayList<>();
        Long g = geoArg(regionId, districtId, siteId);
        if (g != null) args.add(g);
        args.add(since);

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM core.visits v"
                        + " JOIN core.sites site ON site.id = v.site_id" + geoJoin
                        + " WHERE v.voided = FALSE AND v.ivsa_msd_code = 'IVSA'"
                        + "   AND v.visit_date >= ?",
                Long.class, args.toArray());

        List<Object> pagedArgs = new ArrayList<>(args);
        pagedArgs.add(safeSize);
        pagedArgs.add(offset);

        List<IvsaRow> rows = jdbc.query(
                "SELECT v.id, v.visit_date, v.next_visit_date,"
                        + "       v.ivsa_msd_code, v.ivsa_success_confirmation_date,"
                        + "       v.ivsa_alert_signs_count, v.ivsa_neuro_signs_count,"
                        + "       v.weight_kg, v.temperature_c,"
                        + "       v.patient_id,"
                        + "       site.code AS site_code, site.name AS site_name,"
                        + "       (SELECT pi.identifier_value FROM core.patient_identifiers pi"
                        + "         WHERE pi.patient_id = v.patient_id"
                        + "         ORDER BY pi.id LIMIT 1) AS patient_code"
                        + " FROM core.visits v"
                        + " JOIN core.sites site ON site.id = v.site_id" + geoJoin
                        + " WHERE v.voided = FALSE AND v.ivsa_msd_code = 'IVSA'"
                        + "   AND v.visit_date >= ?"
                        + SortSpec.orderBy(sort, dir, IVSA_SORTABLE,
                                "v.visit_date DESC NULLS LAST, v.id DESC")
                        + " LIMIT ? OFFSET ?",
                (rs, i) -> {
                    short alerts = rs.getShort("ivsa_alert_signs_count");
                    boolean alertsNull = rs.wasNull();
                    short neuro = rs.getShort("ivsa_neuro_signs_count");
                    boolean neuroNull = rs.wasNull();
                    return new IvsaRow(
                            rs.getLong("id"),
                            rs.getDate("visit_date") == null ? null : rs.getDate("visit_date").toLocalDate(),
                            rs.getDate("next_visit_date") == null ? null : rs.getDate("next_visit_date").toLocalDate(),
                            rs.getString("ivsa_msd_code"),
                            rs.getDate("ivsa_success_confirmation_date") == null
                                    ? null : rs.getDate("ivsa_success_confirmation_date").toLocalDate(),
                            alertsNull ? null : alerts,
                            neuroNull ? null : neuro,
                            rs.getBigDecimal("weight_kg"),
                            rs.getBigDecimal("temperature_c"),
                            rs.getLong("patient_id"),
                            rs.getString("patient_code"),
                            rs.getString("site_code"),
                            rs.getString("site_name"));
                },
                pagedArgs.toArray());

        return new IvsaPage(rows, total == null ? 0L : total, safePage, safeSize);
    }

    public record IvsaSummary(
            long totalAllTime,
            long inPeriod,
            long successConfirmed,
            long withAlertSigns,
            BigDecimal successPct,
            List<Bucket> msdDistribution,
            int periodMonths
    ) {}

    public record IvsaRow(
            long id,
            LocalDate visitDate,
            LocalDate nextVisitDate,
            String msdCode,
            LocalDate successConfirmationDate,
            Short alertSignsCount,
            Short neuroSignsCount,
            BigDecimal weightKg,
            BigDecimal temperatureC,
            long patientId,
            String patientCode,
            String siteCode,
            String siteName
    ) {}

    public record IvsaPage(
            List<IvsaRow> content,
            long total,
            int page,
            int size
    ) {}

    public record ClinicSummary(
            long visitsAllTime,
            long visitsInPeriod,
            long withTbScreening,
            long withWhoStage,
            BigDecimal tbScreeningPct,
            BigDecimal whoStagePct,
            List<MonthlyCount> monthly,
            List<Bucket> whoStageDistribution,
            List<Bucket> tbScreeningDistribution,
            List<Bucket> arvRegimenDistribution,
            int periodMonths
    ) {}

    /**
     * Monthly volume bucket. {@code count} is the visits volume (kept
     * named like this for backward compatibility with the chart) ;
     * {@code dispensations} is the corresponding ARV refill volume so
     * the Clinique page can plot the two side-by-side and detect
     * coverage gaps. {@code expected} is the number of visits planned
     * for this month (derived from a previous visit's next_visit_date)
     * so the page can compare expected vs attended — a precocious
     * lost-to-follow-up signal.
     */
    public record MonthlyCount(String month, long count, long dispensations, long expected) {}
    public record Bucket(String label, long count) {}

    public record VisitRow(
            long id,
            LocalDate visitDate,
            LocalDate nextVisitDate,
            String sourceForm,
            String arvRegimen,
            Short arvTreatmentDays,
            Short cotrimTreatmentDays,
            BigDecimal weightKg,
            BigDecimal heightCm,
            BigDecimal bmi,
            BigDecimal viralLoad,
            Integer cd4Count,
            String tptStatus,
            String tptRegimen,
            String whoStage,
            String tbScreening,
            long patientId,
            String patientCode,
            String siteCode,
            String siteName
    ) {}

    public record VisitPage(
            List<VisitRow> content,
            long total,
            int page,
            int size
    ) {}
}
