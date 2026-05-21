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
 * PTME (mother-to-child transmission prevention) analytics — reads
 * core.ptme_mothers and core.ptme_children. Visits are not summarised
 * here; the listings on those tables are reached via dedicated endpoints
 * when needed.
 */
@Service
public class PtmeService {

    private final JdbcTemplate jdbc;

    public PtmeService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // --- Geo scoping helpers (same shape as the other thematic services) ---

    private static String geoFilter(String tableAlias, Long regionId, Long districtId, Long siteId) {
        if (siteId != null) {
            return " JOIN core.sites s ON s.id = " + tableAlias + ".site_id AND s.id = ?";
        }
        if (districtId != null) {
            return " JOIN core.sites s ON s.id = " + tableAlias + ".site_id AND s.district_id = ?";
        }
        if (regionId != null) {
            return " JOIN core.sites s ON s.id = " + tableAlias + ".site_id"
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

    // --- Mother track ----------------------------------------------------

    public MotherSummary motherSummary(int months, Long regionId, Long districtId, Long siteId) {
        LocalDate since = LocalDate.now().minusMonths(months);
        String region = geoFilter("m", regionId, districtId, siteId);

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM core.ptme_mothers m" + region
                        + " WHERE m.voided = FALSE",
                Long.class, geoArgs(regionId, districtId, siteId));

        Long inPeriod = jdbc.queryForObject(
                "SELECT count(*) FROM core.ptme_mothers m" + region
                        + " WHERE m.voided = FALSE AND m.start_date >= ?",
                Long.class, geoArgs(regionId, districtId, siteId, since));

        Long spousalScreened = jdbc.queryForObject(
                "SELECT count(*) FROM core.ptme_mothers m" + region
                        + " WHERE m.voided = FALSE AND m.start_date >= ?"
                        + "   AND m.spousal_screening_result IS NOT NULL",
                Long.class, geoArgs(regionId, districtId, siteId, since));

        Long spousalPos = jdbc.queryForObject(
                "SELECT count(*) FROM core.ptme_mothers m" + region
                        + " WHERE m.voided = FALSE AND m.start_date >= ?"
                        + "   AND m.spousal_screening_result = 'POS'",
                Long.class, geoArgs(regionId, districtId, siteId, since));

        BigDecimal spousalCoveragePct = inPeriod == null || inPeriod == 0 ? null
                : new BigDecimal(spousalScreened == null ? 0L : spousalScreened)
                        .multiply(new BigDecimal(100))
                        .divide(new BigDecimal(inPeriod), 1, RoundingMode.HALF_UP);

        List<YearBucket> yearly = jdbc.query(
                "SELECT EXTRACT(year FROM m.start_date)::int AS yr, count(*) AS n"
                        + " FROM core.ptme_mothers m" + region
                        + " WHERE m.voided = FALSE AND m.start_date >= ?"
                        + " GROUP BY yr ORDER BY yr",
                (rs, i) -> new YearBucket(rs.getInt("yr"), rs.getLong("n")),
                geoArgs(regionId, districtId, siteId, since));

        List<Bucket> outcomes = jdbc.query(
                "SELECT COALESCE(m.pregnancy_outcome, '(non renseigné)') AS k, count(*) AS n"
                        + " FROM core.ptme_mothers m" + region
                        + " WHERE m.voided = FALSE AND m.start_date >= ?"
                        + " GROUP BY k ORDER BY n DESC",
                (rs, i) -> new Bucket(rs.getString("k"), rs.getLong("n")),
                geoArgs(regionId, districtId, siteId, since));

        List<Bucket> arvAtRegistering = jdbc.query(
                "SELECT COALESCE(m.arv_status_at_registering, '(non renseigné)') AS k, count(*) AS n"
                        + " FROM core.ptme_mothers m" + region
                        + " WHERE m.voided = FALSE AND m.start_date >= ?"
                        + " GROUP BY k ORDER BY n DESC",
                (rs, i) -> new Bucket(rs.getString("k"), rs.getLong("n")),
                geoArgs(regionId, districtId, siteId, since));

        return new MotherSummary(
                total == null ? 0L : total,
                inPeriod == null ? 0L : inPeriod,
                spousalScreened == null ? 0L : spousalScreened,
                spousalPos == null ? 0L : spousalPos,
                spousalCoveragePct,
                yearly, outcomes, arvAtRegistering, months);
    }

    private static final Map<String, String> MOTHER_SORTABLE = Map.of(
            "date",    "m.start_date",
            "code",    "m.pregnant_number",
            "site",    "site.code",
            "outcome", "m.pregnancy_outcome",
            "arv",     "m.arv_status_at_registering"
    );

    public MotherPage motherRecords(int months, Long regionId, Long districtId, Long siteId,
                                    String sort, String dir, int page, int size) {
        int safeSize = Math.max(1, Math.min(500, size));
        int safePage = Math.max(0, page);
        int offset = safePage * safeSize;
        LocalDate since = LocalDate.now().minusMonths(months);

        String geoJoin;
        if (siteId != null)        geoJoin = " AND site.id = ?";
        else if (districtId != null) geoJoin = " AND site.district_id = ?";
        else if (regionId != null) geoJoin = " JOIN core.districts d ON d.id = site.district_id AND d.region_id = ?";
        else                       geoJoin = "";

        List<Object> args = new ArrayList<>();
        Long g = geoArg(regionId, districtId, siteId);
        if (g != null) args.add(g);
        args.add(since);

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM core.ptme_mothers m"
                        + " JOIN core.sites site ON site.id = m.site_id" + geoJoin
                        + " WHERE m.voided = FALSE AND m.start_date >= ?",
                Long.class, args.toArray());

        List<Object> pagedArgs = new ArrayList<>(args);
        pagedArgs.add(safeSize);
        pagedArgs.add(offset);

        List<MotherRecord> rows = jdbc.query(
                "SELECT m.id, m.source_uuid, m.pregnant_number, m.hiv_care_number, m.screening_number,"
                        + "       m.age, m.marital_status,"
                        + "       m.start_date, m.end_date, m.estimated_delivery_date,"
                        + "       m.arv_status_at_registering, m.pregnancy_outcome,"
                        + "       m.spousal_screening, m.spousal_screening_result,"
                        + "       m.delivery_type,"
                        + "       site.code AS site_code, site.name AS site_name"
                        + " FROM core.ptme_mothers m"
                        + " JOIN core.sites site ON site.id = m.site_id" + geoJoin
                        + " WHERE m.voided = FALSE AND m.start_date >= ?"
                        + SortSpec.orderBy(sort, dir, MOTHER_SORTABLE,
                                "m.start_date DESC NULLS LAST, m.id DESC")
                        + " LIMIT ? OFFSET ?",
                (rs, i) -> new MotherRecord(
                        rs.getLong("id"),
                        rs.getString("source_uuid"),
                        rs.getString("pregnant_number"),
                        rs.getString("hiv_care_number"),
                        rs.getString("screening_number"),
                        (Integer) rs.getObject("age"),
                        rs.getString("marital_status"),
                        toLocal(rs.getDate("start_date")),
                        toLocal(rs.getDate("end_date")),
                        toLocal(rs.getDate("estimated_delivery_date")),
                        rs.getString("arv_status_at_registering"),
                        rs.getString("pregnancy_outcome"),
                        rs.getString("spousal_screening"),
                        rs.getString("spousal_screening_result"),
                        rs.getString("delivery_type"),
                        rs.getString("site_code"),
                        rs.getString("site_name")),
                pagedArgs.toArray());

        return new MotherPage(rows, total == null ? 0L : total, safePage, safeSize);
    }

    // --- Child track -----------------------------------------------------

    public ChildSummary childSummary(int months, Long regionId, Long districtId, Long siteId) {
        LocalDate since = LocalDate.now().minusMonths(months);
        String region = geoFilter("c", regionId, districtId, siteId);

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM core.ptme_children c" + region
                        + " WHERE c.voided = FALSE",
                Long.class, geoArgs(regionId, districtId, siteId));

        Long inPeriod = jdbc.queryForObject(
                "SELECT count(*) FROM core.ptme_children c" + region
                        + " WHERE c.voided = FALSE AND c.birth_date >= ?",
                Long.class, geoArgs(regionId, districtId, siteId, since));

        // Any positive PCR or serology counts the child as HIV positive.
        Long anyPositive = jdbc.queryForObject(
                "SELECT count(*) FROM core.ptme_children c" + region
                        + " WHERE c.voided = FALSE AND c.birth_date >= ?"
                        + "   AND ('POS' IN (c.pcr1_result, c.pcr2_result, c.pcr3_result,"
                        + "                  c.hiv_serology1_result, c.hiv_serology2_result))",
                Long.class, geoArgs(regionId, districtId, siteId, since));

        Long prophylaxisGiven = jdbc.queryForObject(
                "SELECT count(*) FROM core.ptme_children c" + region
                        + " WHERE c.voided = FALSE AND c.birth_date >= ?"
                        + "   AND c.arv_prophylaxis_given = 'Oui'",
                Long.class, geoArgs(regionId, districtId, siteId, since));

        BigDecimal positivityPct = inPeriod == null || inPeriod == 0 ? null
                : new BigDecimal(anyPositive == null ? 0L : anyPositive)
                        .multiply(new BigDecimal(100))
                        .divide(new BigDecimal(inPeriod), 1, RoundingMode.HALF_UP);

        List<YearBucket> yearly = jdbc.query(
                "SELECT EXTRACT(year FROM c.birth_date)::int AS yr, count(*) AS n"
                        + " FROM core.ptme_children c" + region
                        + " WHERE c.voided = FALSE AND c.birth_date >= ?"
                        + " GROUP BY yr ORDER BY yr",
                (rs, i) -> new YearBucket(rs.getInt("yr"), rs.getLong("n")),
                geoArgs(regionId, districtId, siteId, since));

        List<Bucket> followupResults = jdbc.query(
                "SELECT COALESCE(c.followup_result, '(en cours)') AS k, count(*) AS n"
                        + " FROM core.ptme_children c" + region
                        + " WHERE c.voided = FALSE AND c.birth_date >= ?"
                        + " GROUP BY k ORDER BY n DESC",
                (rs, i) -> new Bucket(rs.getString("k"), rs.getLong("n")),
                geoArgs(regionId, districtId, siteId, since));

        // PCR1 results — most informative cascade step
        List<Bucket> pcr1 = jdbc.query(
                "SELECT COALESCE(c.pcr1_result, '(non fait)') AS k, count(*) AS n"
                        + " FROM core.ptme_children c" + region
                        + " WHERE c.voided = FALSE AND c.birth_date >= ?"
                        + " GROUP BY k ORDER BY n DESC",
                (rs, i) -> new Bucket(rs.getString("k"), rs.getLong("n")),
                geoArgs(regionId, districtId, siteId, since));

        return new ChildSummary(
                total == null ? 0L : total,
                inPeriod == null ? 0L : inPeriod,
                anyPositive == null ? 0L : anyPositive,
                prophylaxisGiven == null ? 0L : prophylaxisGiven,
                positivityPct,
                yearly, followupResults, pcr1, months);
    }

    private static final Map<String, String> CHILD_SORTABLE = Map.of(
            "date",    "c.birth_date",
            "code",    "c.child_followup_number",
            "site",    "site.code",
            "result",  "c.followup_result"
    );

    public ChildPage childRecords(int months, Long regionId, Long districtId, Long siteId,
                                  String sort, String dir, int page, int size) {
        int safeSize = Math.max(1, Math.min(500, size));
        int safePage = Math.max(0, page);
        int offset = safePage * safeSize;
        LocalDate since = LocalDate.now().minusMonths(months);

        String geoJoin;
        if (siteId != null)        geoJoin = " AND site.id = ?";
        else if (districtId != null) geoJoin = " AND site.district_id = ?";
        else if (regionId != null) geoJoin = " JOIN core.districts d ON d.id = site.district_id AND d.region_id = ?";
        else                       geoJoin = "";

        List<Object> args = new ArrayList<>();
        Long g = geoArg(regionId, districtId, siteId);
        if (g != null) args.add(g);
        args.add(since);

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM core.ptme_children c"
                        + " JOIN core.sites site ON site.id = c.site_id" + geoJoin
                        + " WHERE c.voided = FALSE AND c.birth_date >= ?",
                Long.class, args.toArray());

        List<Object> pagedArgs = new ArrayList<>(args);
        pagedArgs.add(safeSize);
        pagedArgs.add(offset);

        List<ChildRecord> rows = jdbc.query(
                "SELECT c.id, c.source_uuid, c.mother_source_uuid, c.child_followup_number,"
                        + "       c.birth_date, c.gender,"
                        + "       c.arv_prophylaxis_given, c.arv_prophylaxis_given_date,"
                        + "       c.pcr1_result, c.pcr2_result, c.pcr3_result,"
                        + "       c.hiv_serology1_result, c.hiv_serology2_result,"
                        + "       c.followup_result, c.followup_result_date,"
                        + "       site.code AS site_code, site.name AS site_name"
                        + " FROM core.ptme_children c"
                        + " JOIN core.sites site ON site.id = c.site_id" + geoJoin
                        + " WHERE c.voided = FALSE AND c.birth_date >= ?"
                        + SortSpec.orderBy(sort, dir, CHILD_SORTABLE,
                                "c.birth_date DESC NULLS LAST, c.id DESC")
                        + " LIMIT ? OFFSET ?",
                (rs, i) -> new ChildRecord(
                        rs.getLong("id"),
                        rs.getString("source_uuid"),
                        rs.getString("mother_source_uuid"),
                        rs.getString("child_followup_number"),
                        toLocal(rs.getDate("birth_date")),
                        rs.getString("gender"),
                        rs.getString("arv_prophylaxis_given"),
                        toLocal(rs.getDate("arv_prophylaxis_given_date")),
                        rs.getString("pcr1_result"),
                        rs.getString("pcr2_result"),
                        rs.getString("pcr3_result"),
                        rs.getString("hiv_serology1_result"),
                        rs.getString("hiv_serology2_result"),
                        rs.getString("followup_result"),
                        toLocal(rs.getDate("followup_result_date")),
                        rs.getString("site_code"),
                        rs.getString("site_name")),
                pagedArgs.toArray());

        return new ChildPage(rows, total == null ? 0L : total, safePage, safeSize);
    }

    private static LocalDate toLocal(java.sql.Date d) {
        return d == null ? null : d.toLocalDate();
    }

    // --- DTOs ------------------------------------------------------------

    public record YearBucket(int year, long count) {}
    public record Bucket(String label, long count) {}

    public record MotherSummary(
            long totalAllTime,
            long inPeriod,
            long spousalScreened,
            long spousalPositive,
            BigDecimal spousalCoveragePct,
            List<YearBucket> yearly,
            List<Bucket> outcomes,
            List<Bucket> arvAtRegistering,
            int periodMonths
    ) {}

    public record MotherRecord(
            long id,
            String sourceUuid,
            String pregnantNumber,
            String hivCareNumber,
            String screeningNumber,
            Integer age,
            String maritalStatus,
            LocalDate startDate,
            LocalDate endDate,
            LocalDate estimatedDeliveryDate,
            String arvStatusAtRegistering,
            String pregnancyOutcome,
            String spousalScreening,
            String spousalScreeningResult,
            String deliveryType,
            String siteCode,
            String siteName
    ) {}

    public record MotherPage(
            List<MotherRecord> content,
            long total,
            int page,
            int size
    ) {}

    public record ChildSummary(
            long totalAllTime,
            long inPeriod,
            long anyPositive,
            long prophylaxisGiven,
            BigDecimal positivityPct,
            List<YearBucket> yearly,
            List<Bucket> followupResults,
            List<Bucket> pcr1,
            int periodMonths
    ) {}

    public record ChildRecord(
            long id,
            String sourceUuid,
            String motherSourceUuid,
            String childFollowupNumber,
            LocalDate birthDate,
            String gender,
            String arvProphylaxisGiven,
            LocalDate arvProphylaxisGivenDate,
            String pcr1Result,
            String pcr2Result,
            String pcr3Result,
            String hivSerology1Result,
            String hivSerology2Result,
            String followupResult,
            LocalDate followupResultDate,
            String siteCode,
            String siteName
    ) {}

    public record ChildPage(
            List<ChildRecord> content,
            long total,
            int page,
            int size
    ) {}
}
