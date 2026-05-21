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
 * HIV screening analytics. Reads core.screenings.
 *
 * Screenings are anonymous (no patient_id) — the screening_code is the
 * local opaque identifier kept by the source site. Everything here is
 * scoped through core.sites just like the other thematic services.
 */
@Service
public class ScreeningService {

    private final JdbcTemplate jdbc;

    public ScreeningService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static String geoFilter(Long regionId, Long districtId, Long siteId) {
        if (siteId != null) {
            return " JOIN core.sites s ON s.id = sc.site_id AND s.id = ?";
        }
        if (districtId != null) {
            return " JOIN core.sites s ON s.id = sc.site_id AND s.district_id = ?";
        }
        if (regionId != null) {
            return " JOIN core.sites s ON s.id = sc.site_id"
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

    public ScreeningSummary summary(int months, Long regionId, Long districtId, Long siteId) {
        LocalDate since = LocalDate.now().minusMonths(months);
        String region = geoFilter(regionId, districtId, siteId);

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM core.screenings sc" + region
                        + " WHERE sc.voided = FALSE",
                Long.class, geoArgs(regionId, districtId, siteId));

        Long inPeriod = jdbc.queryForObject(
                "SELECT count(*) FROM core.screenings sc" + region
                        + " WHERE sc.voided = FALSE AND sc.screening_date >= ?",
                Long.class, geoArgs(regionId, districtId, siteId, since));

        Long pos = jdbc.queryForObject(
                "SELECT count(*) FROM core.screenings sc" + region
                        + " WHERE sc.voided = FALSE AND sc.screening_date >= ?"
                        + "   AND sc.final_result = 'POS'",
                Long.class, geoArgs(regionId, districtId, siteId, since));

        Long neg = jdbc.queryForObject(
                "SELECT count(*) FROM core.screenings sc" + region
                        + " WHERE sc.voided = FALSE AND sc.screening_date >= ?"
                        + "   AND sc.final_result = 'NEG'",
                Long.class, geoArgs(regionId, districtId, siteId, since));

        BigDecimal positivityPct = inPeriod == null || inPeriod == 0 ? null
                : new BigDecimal(pos == null ? 0L : pos)
                        .multiply(new BigDecimal(100))
                        .divide(new BigDecimal(inPeriod), 1, RoundingMode.HALF_UP);

        List<YearBucket> yearly = jdbc.query(
                "SELECT EXTRACT(year FROM sc.screening_date)::int AS yr, count(*) AS n"
                        + " FROM core.screenings sc" + region
                        + " WHERE sc.voided = FALSE AND sc.screening_date >= ?"
                        + " GROUP BY yr ORDER BY yr",
                (rs, i) -> new YearBucket(rs.getInt("yr"), rs.getLong("n")),
                geoArgs(regionId, districtId, siteId, since));

        List<Bucket> results = jdbc.query(
                "SELECT COALESCE(sc.final_result, '(non renseigné)') AS k, count(*) AS n"
                        + " FROM core.screenings sc" + region
                        + " WHERE sc.voided = FALSE AND sc.screening_date >= ?"
                        + " GROUP BY k ORDER BY n DESC",
                (rs, i) -> new Bucket(rs.getString("k"), rs.getLong("n")),
                geoArgs(regionId, districtId, siteId, since));

        List<Bucket> populations = jdbc.query(
                "SELECT COALESCE(sc.population_type, '(non renseigné)') AS k, count(*) AS n"
                        + " FROM core.screenings sc" + region
                        + " WHERE sc.voided = FALSE AND sc.screening_date >= ?"
                        + " GROUP BY k ORDER BY n DESC",
                (rs, i) -> new Bucket(rs.getString("k"), rs.getLong("n")),
                geoArgs(regionId, districtId, siteId, since));

        List<Bucket> reasons = jdbc.query(
                "SELECT COALESCE(sc.screening_reason, '(non renseigné)') AS k, count(*) AS n"
                        + " FROM core.screenings sc" + region
                        + " WHERE sc.voided = FALSE AND sc.screening_date >= ?"
                        + " GROUP BY k ORDER BY n DESC",
                (rs, i) -> new Bucket(rs.getString("k"), rs.getLong("n")),
                geoArgs(regionId, districtId, siteId, since));

        List<Bucket> genders = jdbc.query(
                "SELECT COALESCE(sc.gender, '(non renseigné)') AS k, count(*) AS n"
                        + " FROM core.screenings sc" + region
                        + " WHERE sc.voided = FALSE AND sc.screening_date >= ?"
                        + " GROUP BY k ORDER BY n DESC",
                (rs, i) -> new Bucket(rs.getString("k"), rs.getLong("n")),
                geoArgs(regionId, districtId, siteId, since));

        // Porte d'entrée — for each screening_site_type, total screened +
        // positives + positivity rate. Programmatically structuring data:
        // shows which entry points yield the most positives.
        List<SiteTypeStat> siteTypes = jdbc.query(
                "SELECT COALESCE(sc.screening_site_type, '(non renseigné)') AS k,"
                        + "       count(*) AS n,"
                        + "       count(*) FILTER (WHERE sc.final_result = 'POS') AS pos,"
                        + "       count(*) FILTER (WHERE sc.final_result = 'NEG') AS neg"
                        + " FROM core.screenings sc" + region
                        + " WHERE sc.voided = FALSE AND sc.screening_date >= ?"
                        + " GROUP BY k ORDER BY n DESC",
                (rs, i) -> {
                    long n = rs.getLong("n");
                    long p = rs.getLong("pos");
                    BigDecimal rate = n == 0 ? null
                            : new BigDecimal(p).multiply(new BigDecimal(100))
                                    .divide(new BigDecimal(n), 1, RoundingMode.HALF_UP);
                    return new SiteTypeStat(rs.getString("k"), n, p, rs.getLong("neg"), rate);
                },
                geoArgs(regionId, districtId, siteId, since));

        return new ScreeningSummary(
                total == null ? 0L : total,
                inPeriod == null ? 0L : inPeriod,
                pos == null ? 0L : pos,
                neg == null ? 0L : neg,
                positivityPct,
                yearly,
                results,
                populations,
                reasons,
                genders,
                siteTypes,
                months);
    }

    private static final Map<String, String> SCREENING_SORTABLE = Map.of(
            "date",       "sc.screening_date",
            "code",       "sc.screening_code",
            "site",       "site.code",
            "result",     "sc.final_result",
            "population", "sc.population_type",
            "reason",     "sc.screening_reason"
    );

    public RecordPage records(int months, Long regionId, Long districtId, Long siteId,
                              String sort, String dir,
                              int page, int size) {
        int safeSize = Math.max(1, Math.min(500, size));
        int safePage = Math.max(0, page);
        int offset = safePage * safeSize;
        LocalDate since = LocalDate.now().minusMonths(months);

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
                "SELECT count(*) FROM core.screenings sc"
                        + " JOIN core.sites site ON site.id = sc.site_id" + geoJoin
                        + " WHERE sc.voided = FALSE AND sc.screening_date >= ?",
                Long.class, args.toArray());

        List<Object> pagedArgs = new ArrayList<>(args);
        pagedArgs.add(safeSize);
        pagedArgs.add(offset);

        List<ScreeningRecord> rows = jdbc.query(
                "SELECT sc.id, sc.screening_code, sc.screening_date, sc.result_announcing_date,"
                        + "       sc.gender, sc.age, sc.population_type, sc.screening_reason,"
                        + "       sc.final_result, sc.retesting,"
                        + "       sc.screening_site_type, sc.screening_post,"
                        + "       site.code AS site_code, site.name AS site_name"
                        + " FROM core.screenings sc"
                        + " JOIN core.sites site ON site.id = sc.site_id" + geoJoin
                        + " WHERE sc.voided = FALSE AND sc.screening_date >= ?"
                        + SortSpec.orderBy(sort, dir, SCREENING_SORTABLE,
                                "sc.screening_date DESC NULLS LAST, sc.id DESC")
                        + " LIMIT ? OFFSET ?",
                (rs, i) -> new ScreeningRecord(
                        rs.getLong("id"),
                        rs.getString("screening_code"),
                        toLocal(rs.getDate("screening_date")),
                        toLocal(rs.getDate("result_announcing_date")),
                        rs.getString("gender"),
                        (Integer) rs.getObject("age"),
                        rs.getString("population_type"),
                        rs.getString("screening_reason"),
                        rs.getString("final_result"),
                        (Boolean) rs.getObject("retesting"),
                        rs.getString("screening_site_type"),
                        rs.getString("screening_post"),
                        rs.getString("site_code"),
                        rs.getString("site_name")),
                pagedArgs.toArray());

        return new RecordPage(rows, total == null ? 0L : total, safePage, safeSize);
    }

    private static LocalDate toLocal(java.sql.Date d) {
        return d == null ? null : d.toLocalDate();
    }

    public record ScreeningSummary(
            long totalAllTime,
            long screenedInPeriod,
            long positiveInPeriod,
            long negativeInPeriod,
            BigDecimal positivityPct,
            List<YearBucket> yearly,
            List<Bucket> results,
            List<Bucket> populations,
            List<Bucket> reasons,
            List<Bucket> genders,
            List<SiteTypeStat> siteTypes,
            int periodMonths
    ) {}

    public record YearBucket(int year, long count) {}
    public record Bucket(String label, long count) {}
    public record SiteTypeStat(String label, long screened, long positive, long negative,
                               BigDecimal positivityPct) {}

    public record ScreeningRecord(
            long id,
            String screeningCode,
            LocalDate screeningDate,
            LocalDate resultAnnouncingDate,
            String gender,
            Integer age,
            String populationType,
            String screeningReason,
            String finalResult,
            Boolean retesting,
            String screeningSiteType,
            String screeningPost,
            String siteCode,
            String siteName
    ) {}

    public record RecordPage(
            List<ScreeningRecord> content,
            long total,
            int page,
            int size
    ) {}
}
