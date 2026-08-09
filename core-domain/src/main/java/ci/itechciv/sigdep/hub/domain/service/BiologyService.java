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
 * Read-only biology analytics for the console. Filtered by:
 *  - period: number of months back from today;
 *  - region: optional core.regions.id, joined through districts/sites.
 *
 * The two tests that drive the page are viral load (UUID 856…) and any
 * test whose name matches CD4 (the source data has French and English
 * variants, so we filter on test_name ILIKE '%CD4%').
 */
@Service
public class BiologyService {

    private static final String VL_UUID = "856AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String CD4_NAME_LIKE = "%CD4%";
    private static final BigDecimal SUPPRESSED = new BigDecimal("1000");

    /**
     * SQL fragment that scopes a query by region / district / site. Always
     * a JOIN on core.sites (alias "s"), so it can be inserted right after
     * the FROM table without disturbing the WHERE clause downstream. The
     * tightest filter wins: site > district > region.
     */
    private static String geoFilter(Long regionId, Long districtId, Long siteId) {
        if (siteId != null) {
            return " JOIN core.sites s ON s.id = lr.site_id AND s.id = ?";
        }
        if (districtId != null) {
            return " JOIN core.sites s ON s.id = lr.site_id AND s.district_id = ?";
        }
        if (regionId != null) {
            return " JOIN core.sites s ON s.id = lr.site_id"
                 + " JOIN core.districts d ON d.id = s.district_id AND d.region_id = ?";
        }
        return "";
    }

    /** Picks the single ID matching the geoFilter (or null if no scope). */
    private static Long geoArg(Long regionId, Long districtId, Long siteId) {
        if (siteId != null)     return siteId;
        if (districtId != null) return districtId;
        return regionId;
    }

    /**
     * Builds a JDBC arg array. The geoFilter contributes 0 or 1 leading
     * argument (its ID), the rest are appended in order.
     */
    private static Object[] geoArgs(Long regionId, Long districtId, Long siteId, Object... rest) {
        Long g = geoArg(regionId, districtId, siteId);
        if (g == null) return rest;
        Object[] out = new Object[rest.length + 1];
        out[0] = g;
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }

    private final JdbcTemplate jdbc;

    public BiologyService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public BiologySummary summary(PeriodRange period, Long regionId, Long districtId, Long siteId) {
        LocalDate since = period.from();
        LocalDate until = period.to();
        String region = geoFilter(regionId, districtId, siteId);

        // 1. Cards
        Long examsPeriod = jdbc.queryForObject(
                "SELECT count(*) FROM core.lab_results lr" + region
                        + " WHERE lr.voided = FALSE AND lr.exam_date >= ? AND lr.exam_date <= ?",
                Long.class,
                geoArgs(regionId, districtId, siteId, since, until));

        Long examsAllTime = jdbc.queryForObject(
                "SELECT count(*) FROM core.lab_results lr" + region
                        + " WHERE lr.voided = FALSE",
                Long.class,
                geoArgs(regionId, districtId, siteId));

        LocalDate lastExamDate = jdbc.query(
                "SELECT max(exam_date) FROM core.lab_results lr" + region
                        + " WHERE lr.voided = FALSE",
                rs -> {
                    if (!rs.next()) return null;
                    java.sql.Date d = rs.getDate(1);
                    return d == null ? null : d.toLocalDate();
                },
                geoArgs(regionId, districtId, siteId));

        // 2. Viral suppression % over the period
        BigDecimal viralSuppression = jdbc.query(
                "SELECT count(*) FILTER (WHERE value_numeric < ?) AS suppressed,"
                        + "       count(*) AS total"
                        + " FROM core.lab_results lr" + region
                        + " WHERE lr.voided = FALSE AND lr.test_uuid = ?"
                        + "   AND lr.value_numeric IS NOT NULL"
                        + "   AND lr.exam_date >= ? AND lr.exam_date <= ?",
                BiologyService::ratioPct,
                geoArgs(regionId, districtId, siteId, SUPPRESSED, VL_UUID, since, until));

        // 3. Monthly viral-suppression series (months couvrant la période)
        List<MonthlySuppression> series = monthlySuppression(period, regionId, districtId, siteId);

        // 4. CD4 distribution (period)
        Cd4Distribution cd4 = jdbc.query(
                "SELECT"
                        + "  count(*) FILTER (WHERE value_numeric <  200)                          AS b1,"
                        + "  count(*) FILTER (WHERE value_numeric >= 200 AND value_numeric <  350) AS b2,"
                        + "  count(*) FILTER (WHERE value_numeric >= 350 AND value_numeric <  500) AS b3,"
                        + "  count(*) FILTER (WHERE value_numeric >= 500)                          AS b4,"
                        + "  count(*)                                                              AS total"
                        + " FROM core.lab_results lr" + region
                        + " WHERE lr.voided = FALSE"
                        + "   AND lr.test_name ILIKE ?"
                        + "   AND lr.value_numeric IS NOT NULL"
                        + "   AND lr.exam_date >= ? AND lr.exam_date <= ?",
                rs -> {
                    if (!rs.next()) return new Cd4Distribution(0, 0, 0, 0, 0);
                    return new Cd4Distribution(
                            rs.getLong("b1"), rs.getLong("b2"),
                            rs.getLong("b3"), rs.getLong("b4"),
                            rs.getLong("total"));
                },
                geoArgs(regionId, districtId, siteId, CD4_NAME_LIKE, since, until));

        // 5. Top tests (period)
        List<TopTest> topTests = jdbc.query(
                "SELECT lr.test_name, count(*) AS n FROM core.lab_results lr" + region
                        + " WHERE lr.voided = FALSE AND lr.exam_date >= ? AND lr.exam_date <= ?"
                        + " GROUP BY lr.test_name ORDER BY n DESC LIMIT 10",
                (rs, i) -> new TopTest(rs.getString("test_name"), rs.getLong("n")),
                geoArgs(regionId, districtId, siteId, since, until));

        return new BiologySummary(
                examsPeriod == null ? 0L : examsPeriod,
                examsAllTime == null ? 0L : examsAllTime,
                lastExamDate,
                viralSuppression,
                series,
                cd4,
                topTests,
                since,
                until);
    }

    private List<MonthlySuppression> monthlySuppression(PeriodRange period, Long regionId, Long districtId, Long siteId) {
        String region = geoFilter(regionId, districtId, siteId);
        Long g = geoArg(regionId, districtId, siteId);
        // Two correlated subqueries (total / suppressed) per month bucket.
        // Aliases are deliberately reused inside each subquery — the outer
        // generate_series is in its own scope.
        // L'axe des mois couvre du mois de `from` au mois de `to` (inclus).
        String sql = "WITH months AS ("
                + "  SELECT generate_series("
                + "    date_trunc('month', ?::date),"
                + "    date_trunc('month', ?::date),"
                + "    INTERVAL '1 month'"
                + "  )::date AS month_start"
                + ")"
                + " SELECT to_char(m.month_start, 'YYYY-MM') AS label,"
                + "   (SELECT count(*) FROM core.lab_results lr" + region
                + "    WHERE lr.voided = FALSE AND lr.test_uuid = ?"
                + "      AND lr.value_numeric IS NOT NULL"
                + "      AND lr.exam_date >= m.month_start"
                + "      AND lr.exam_date <  m.month_start + INTERVAL '1 month') AS total,"
                + "   (SELECT count(*) FROM core.lab_results lr" + region
                + "    WHERE lr.voided = FALSE AND lr.test_uuid = ?"
                + "      AND lr.value_numeric IS NOT NULL"
                + "      AND lr.value_numeric < ?"
                + "      AND lr.exam_date >= m.month_start"
                + "      AND lr.exam_date <  m.month_start + INTERVAL '1 month') AS suppressed"
                + " FROM months m"
                + " ORDER BY m.month_start";

        List<Object> args = new ArrayList<>();
        args.add(period.from());
        args.add(period.to());
        if (g != null) args.add(g);
        args.add(VL_UUID);
        if (g != null) args.add(g);
        args.add(VL_UUID);
        args.add(SUPPRESSED);

        return jdbc.query(sql, (rs, i) -> {
            long total = rs.getLong("total");
            long sup = rs.getLong("suppressed");
            BigDecimal pct = total == 0 ? null
                    : new BigDecimal(sup).multiply(new BigDecimal(100))
                            .divide(new BigDecimal(total), 1, RoundingMode.HALF_UP);
            return new MonthlySuppression(rs.getString("label"), total, sup, pct);
        }, args.toArray());
    }

    private static BigDecimal ratioPct(java.sql.ResultSet rs) throws java.sql.SQLException {
        if (!rs.next()) return null;
        long sup = rs.getLong("suppressed");
        long tot = rs.getLong("total");
        return tot == 0 ? null
                : new BigDecimal(sup).multiply(new BigDecimal(100))
                        .divide(new BigDecimal(tot), 1, RoundingMode.HALF_UP);
    }

    /**
     * Filtered exam list. test = "vl" returns one row per viral-load result,
     * test = "cd4" returns one row per (patient, date) pair with both the
     * absolute count and the percentage when present (the source data
     * captures them as two separate rows). Anything else returns each row
     * unchanged.
     */
    private static final Map<String, String> EXAM_SORTABLE = Map.of(
            "date",     "lr.exam_date",
            "patient",  "lr.patient_id",
            "site",     "site.code",
            "testName", "lr.test_name",
            "value",    "lr.value_numeric"
    );

    private static final Map<String, String> CD4_SORTABLE = Map.of(
            "date",    "lr.exam_date",
            "patient", "lr.patient_id",
            "site",    "s.code"
    );

    public ExamPage exams(String test, PeriodRange period, Long regionId, Long districtId, Long siteId,
                          String sort, String dir,
                          int page, int size) {
        if ("cd4".equals(test)) {
            return cd4Exams(period, regionId, districtId, siteId, sort, dir, page, size);
        }

        int safeSize = Math.max(1, Math.min(500, size));
        int safePage = Math.max(0, page);
        int offset = safePage * safeSize;
        String region = geoFilter(regionId, districtId, siteId);
        Long g = geoArg(regionId, districtId, siteId);

        StringBuilder where = new StringBuilder(
                " WHERE lr.voided = FALSE AND lr.exam_date >= ? AND lr.exam_date <= ?");
        List<Object> args = new ArrayList<>();
        if (g != null) args.add(g);
        args.add(period.from());
        args.add(period.to());

        if ("vl".equals(test)) {
            where.append(" AND lr.test_uuid = ?");
            args.add(VL_UUID);
        }

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM core.lab_results lr" + region + where,
                Long.class, args.toArray());

        List<Object> pagedArgs = new ArrayList<>(args);
        pagedArgs.add(safeSize);
        pagedArgs.add(offset);

        List<ExamRow> rows = jdbc.query(
                "SELECT lr.id, lr.exam_date, lr.test_name,"
                        + "       lr.value_numeric, lr.value_text, lr.unit,"
                        + "       lr.patient_id,"
                        + "       site.code AS site_code, site.name AS site_name,"
                        + "       (SELECT pi.identifier_value FROM core.patient_identifiers pi"
                        + "         WHERE pi.patient_id = lr.patient_id"
                        + "         ORDER BY pi.id LIMIT 1) AS patient_code"
                        + " FROM core.lab_results lr" + region
                        + " JOIN core.sites site ON site.id = lr.site_id"
                        + where
                        + SortSpec.orderBy(sort, dir, EXAM_SORTABLE,
                                "lr.exam_date DESC NULLS LAST, lr.id DESC")
                        + " LIMIT ? OFFSET ?",
                BiologyService::mapExam,
                pagedArgs.toArray());

        return new ExamPage(rows, total == null ? 0L : total, safePage, safeSize);
    }

    /**
     * CD4 results consolidated per (patient_id, exam_date). Both the
     * absolute count ("Numération des lymphocytes CD4") and the percentage
     * ("CD4%") are surfaced as separate columns; either can be NULL.
     */
    private ExamPage cd4Exams(PeriodRange period, Long regionId, Long districtId, Long siteId,
                              String sort, String dir,
                              int page, int size) {
        int safeSize = Math.max(1, Math.min(500, size));
        int safePage = Math.max(0, page);
        int offset = safePage * safeSize;
        LocalDate since = period.from();
        LocalDate until = period.to();
        String region = geoFilter(regionId, districtId, siteId);

        // Aggregate two rows (abs + pct) into one. There can also be a
        // single row of either kind on a given date — coalesce handles that.
        String baseFrom =
                " FROM core.lab_results lr" + region
                + " WHERE lr.voided = FALSE"
                + "   AND lr.test_name ILIKE ?"
                + "   AND lr.value_numeric IS NOT NULL"
                + "   AND lr.exam_date >= ? AND lr.exam_date <= ?";

        Object[] args = geoArgs(regionId, districtId, siteId, CD4_NAME_LIKE, since, until);

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM (SELECT lr.patient_id, lr.exam_date" + baseFrom
                        + " GROUP BY lr.patient_id, lr.exam_date) AS pairs",
                Long.class, args);

        Object[] pagedArgs = new Object[args.length + 2];
        System.arraycopy(args, 0, pagedArgs, 0, args.length);
        pagedArgs[args.length] = safeSize;
        pagedArgs[args.length + 1] = offset;

        // The detailed CD4 query needs a sites JOIN for code/name, plus the
        // optional geo filter. We fold the geo filter into the same JOIN
        // for site-level scoping, otherwise we keep the standard chain.
        String detailedFrom = " FROM core.lab_results lr";
        if (siteId != null) {
            detailedFrom += " JOIN core.sites s ON s.id = lr.site_id AND s.id = ?";
        } else if (districtId != null) {
            detailedFrom += " JOIN core.sites s ON s.id = lr.site_id AND s.district_id = ?";
        } else if (regionId != null) {
            detailedFrom += " JOIN core.sites s ON s.id = lr.site_id"
                    + " JOIN core.districts d ON d.id = s.district_id AND d.region_id = ?";
        } else {
            detailedFrom += " JOIN core.sites s ON s.id = lr.site_id";
        }

        List<ExamRow> rows = jdbc.query(
                "SELECT lr.patient_id, lr.exam_date,"
                        // "CD4%" exam name contains a literal '%' — match it
                        // with ESCAPE '\\'. The absolute count is any other
                        // CD4 test name.
                        + "  max(CASE WHEN lr.test_name ILIKE '%CD4%' AND lr.test_name NOT LIKE '%\\%%' ESCAPE '\\'"
                        + "           THEN lr.value_numeric END) AS cd4_abs,"
                        + "  max(CASE WHEN lr.test_name ILIKE '%CD4%' AND lr.test_name LIKE '%\\%%' ESCAPE '\\'"
                        + "           THEN lr.value_numeric END) AS cd4_pct,"
                        + "  s.code AS site_code, s.name AS site_name,"
                        + "  (SELECT pi.identifier_value FROM core.patient_identifiers pi"
                        + "     WHERE pi.patient_id = lr.patient_id"
                        + "     ORDER BY pi.id LIMIT 1) AS patient_code"
                        + detailedFrom
                        + " WHERE lr.voided = FALSE"
                        + "   AND lr.test_name ILIKE ?"
                        + "   AND lr.value_numeric IS NOT NULL"
                        + "   AND lr.exam_date >= ? AND lr.exam_date <= ?"
                        + " GROUP BY lr.patient_id, lr.exam_date, s.id, s.code, s.name"
                        + SortSpec.orderBy(sort, dir, CD4_SORTABLE,
                                "lr.exam_date DESC NULLS LAST, lr.patient_id DESC")
                        + " LIMIT ? OFFSET ?",
                (rs, i) -> {
                    java.sql.Date d = rs.getDate("exam_date");
                    return new ExamRow(
                            // synthetic id: combine patient + epoch day, both unsigned
                            rs.getLong("patient_id") * 1_000_000L
                                    + (d == null ? 0L : d.toLocalDate().toEpochDay()),
                            d == null ? null : d.toLocalDate(),
                            "CD4",
                            rs.getBigDecimal("cd4_abs"),
                            rs.getBigDecimal("cd4_pct"),
                            null,
                            "cellules/µL",
                            rs.getString("site_code"),
                            rs.getString("site_name"),
                            rs.getLong("patient_id"),
                            rs.getString("patient_code"));
                },
                pagedArgs);

        // site_id IS NOT NULL filter above means lr.site_id may be null; in
        // practice there is a NOT NULL constraint, but the join-by-site below
        // would otherwise be needed. We resolved site through min() above so
        // this works without the extra join.
        return new ExamPage(rows, total == null ? 0L : total, safePage, safeSize);
    }

    private static ExamRow mapExam(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        java.sql.Date d = rs.getDate("exam_date");
        return new ExamRow(
                rs.getLong("id"),
                d == null ? null : d.toLocalDate(),
                rs.getString("test_name"),
                rs.getBigDecimal("value_numeric"),
                null,
                rs.getString("value_text"),
                rs.getString("unit"),
                rs.getString("site_code"),
                rs.getString("site_name"),
                rs.getLong("patient_id"),
                rs.getString("patient_code"));
    }

    public record BiologySummary(
            long examsInPeriod,
            long examsAllTime,
            LocalDate lastExamDate,
            BigDecimal viralSuppressionPct,
            List<MonthlySuppression> monthlySuppression,
            Cd4Distribution cd4Distribution,
            List<TopTest> topTests,
            LocalDate periodFrom,
            LocalDate periodTo
    ) {}

    public record MonthlySuppression(
            String month,
            long total,
            long suppressed,
            BigDecimal pct
    ) {}

    public record Cd4Distribution(
            long lt200,
            long b200_350,
            long b350_500,
            long ge500,
            long total
    ) {}

    public record TopTest(String testName, long count) {}

    /**
     * One exam row. For viral load and other tests, only valueNumeric (or
     * valueText) is set. For consolidated CD4 rows, valueNumeric holds the
     * absolute count (cells/µL) and valuePct holds the percentage; either
     * can be null.
     */
    public record ExamRow(
            long id,
            LocalDate examDate,
            String testName,
            BigDecimal valueNumeric,
            BigDecimal valuePct,
            String valueText,
            String unit,
            String siteCode,
            String siteName,
            long patientId,
            String patientCode
    ) {}

    public record ExamPage(
            List<ExamRow> content,
            long total,
            int page,
            int size
    ) {}
}
