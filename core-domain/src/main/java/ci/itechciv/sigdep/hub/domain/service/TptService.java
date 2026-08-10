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
 * TPT (TB Preventive Therapy) analytics. Reads core.tpt_records.
 *
 * In the current ingestion only FOLLOWUP records are emitted by the
 * source (no INITIATION/END), so we treat each row as the lifecycle
 * record for one patient × course: record_date is the start, tpt_end_date
 * the end (if any), tpt_outcome the closing label.
 */
@Service
public class TptService {

    private final JdbcTemplate jdbc;

    public TptService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static String geoFilter(Long regionId, Long districtId, Long siteId) {
        if (siteId != null) {
            return " JOIN core.sites s ON s.id = t.site_id AND s.id = ?";
        }
        if (districtId != null) {
            return " JOIN core.sites s ON s.id = t.site_id AND s.district_id = ?";
        }
        if (regionId != null) {
            return " JOIN core.sites s ON s.id = t.site_id"
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

    public TptSummary summary(PeriodRange period, Long regionId, Long districtId, Long siteId) {
        LocalDate since = period.from();
        LocalDate until = period.to();
        String region = geoFilter(regionId, districtId, siteId);

        // Cards
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM core.tpt_records t" + region
                        + " WHERE t.voided = FALSE",
                Long.class, geoArgs(regionId, districtId, siteId));

        Long inPeriod = jdbc.queryForObject(
                "SELECT count(*) FROM core.tpt_records t" + region
                        + " WHERE t.voided = FALSE AND t.record_date >= ? AND t.record_date <= ?",
                Long.class, geoArgs(regionId, districtId, siteId, since, until));

        Long completed = jdbc.queryForObject(
                "SELECT count(*) FROM core.tpt_records t" + region
                        + " WHERE t.voided = FALSE AND t.tpt_outcome IS NOT NULL"
                        + "   AND t.record_date >= ? AND t.record_date <= ?",
                Long.class, geoArgs(regionId, districtId, siteId, since, until));

        Long ongoing = jdbc.queryForObject(
                "SELECT count(*) FROM core.tpt_records t" + region
                        + " WHERE t.voided = FALSE"
                        + "   AND (t.tpt_outcome IS NULL OR t.tpt_end_date IS NULL"
                        + "        OR t.tpt_end_date > CURRENT_DATE)",
                Long.class, geoArgs(regionId, districtId, siteId));

        BigDecimal completionPct = inPeriod == null || inPeriod == 0 ? null
                : new BigDecimal(completed == null ? 0L : completed)
                        .multiply(new BigDecimal(100))
                        .divide(new BigDecimal(inPeriod), 1, RoundingMode.HALF_UP);

        // Yearly series (période from → to bucketed by year)
        List<YearBucket> yearly = jdbc.query(
                "SELECT EXTRACT(year FROM t.record_date)::int AS yr, count(*) AS n"
                        + " FROM core.tpt_records t" + region
                        + " WHERE t.voided = FALSE AND t.record_date >= ? AND t.record_date <= ?"
                        + " GROUP BY yr ORDER BY yr",
                (rs, i) -> new YearBucket(rs.getInt("yr"), rs.getLong("n")),
                geoArgs(regionId, districtId, siteId, since, until));

        // Outcome distribution
        List<Bucket> outcomes = jdbc.query(
                "SELECT COALESCE(t.tpt_outcome, '(non clos)') AS k, count(*) AS n"
                        + " FROM core.tpt_records t" + region
                        + " WHERE t.voided = FALSE AND t.record_date >= ? AND t.record_date <= ?"
                        + " GROUP BY k ORDER BY n DESC",
                (rs, i) -> new Bucket(rs.getString("k"), rs.getLong("n")),
                geoArgs(regionId, districtId, siteId, since, until));

        // Adherence distribution
        List<Bucket> adherence = jdbc.query(
                "SELECT COALESCE(t.adherence, '(non renseigné)') AS k, count(*) AS n"
                        + " FROM core.tpt_records t" + region
                        + " WHERE t.voided = FALSE AND t.record_date >= ? AND t.record_date <= ?"
                        + " GROUP BY k ORDER BY n DESC",
                (rs, i) -> new Bucket(rs.getString("k"), rs.getLong("n")),
                geoArgs(regionId, districtId, siteId, since, until));

        // Status distribution (Début / En cours / Fin / Pas de TPT)
        List<Bucket> statuses = jdbc.query(
                "SELECT COALESCE(t.tpt_status, '(non renseigné)') AS k, count(*) AS n"
                        + " FROM core.tpt_records t" + region
                        + " WHERE t.voided = FALSE AND t.record_date >= ? AND t.record_date <= ?"
                        + " GROUP BY k ORDER BY n DESC",
                (rs, i) -> new Bucket(rs.getString("k"), rs.getLong("n")),
                geoArgs(regionId, districtId, siteId, since, until));

        // Regimen distribution (3HP / 6H / INH / …)
        List<Bucket> regimens = jdbc.query(
                "SELECT COALESCE(t.tpt_regimen, '(non renseigné)') AS k, count(*) AS n"
                        + " FROM core.tpt_records t" + region
                        + " WHERE t.voided = FALSE AND t.record_date >= ? AND t.record_date <= ?"
                        + " GROUP BY k ORDER BY n DESC",
                (rs, i) -> new Bucket(rs.getString("k"), rs.getLong("n")),
                geoArgs(regionId, districtId, siteId, since, until));

        return new TptSummary(
                total == null ? 0L : total,
                inPeriod == null ? 0L : inPeriod,
                completed == null ? 0L : completed,
                ongoing == null ? 0L : ongoing,
                completionPct,
                yearly,
                outcomes,
                adherence,
                statuses,
                regimens,
                since,
                until);
    }

    private static final Map<String, String> TPT_SORTABLE = Map.of(
            "date",       "t.record_date",
            "patient",    "t.patient_id",
            "site",       "site.code",
            "tptStatus",  "t.tpt_status",
            "tptRegimen", "t.tpt_regimen"
    );

    public RecordPage records(PeriodRange period, Long regionId, Long districtId, Long siteId,
                              String sort, String dir,
                              int page, int size) {
        int safeSize = Math.max(1, Math.min(size > 500 ? 100_000 : 500, size));
        int safePage = Math.max(0, page);
        int offset = safePage * safeSize;
        LocalDate since = period.from();
        LocalDate until = period.to();

        // The query already joins core.sites for code/name; tack the geo
        // filter on that join.
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
        args.add(until);

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM core.tpt_records t"
                        + " JOIN core.sites site ON site.id = t.site_id" + geoJoin
                        + " WHERE t.voided = FALSE AND t.record_date >= ? AND t.record_date <= ?",
                Long.class, args.toArray());

        List<Object> pagedArgs = new ArrayList<>(args);
        pagedArgs.add(safeSize);
        pagedArgs.add(offset);

        List<TptRecord> rows = jdbc.query(
                "SELECT t.id, t.record_date, t.tpt_followup_date, t.tpt_end_date,"
                        + "       t.tpt_outcome, t.tpt_order_number,"
                        + "       t.tpt_status, t.tpt_regimen,"
                        + "       t.adherence, t.weight_kg, t.next_visit_date, t.patient_id,"
                        + "       site.code AS site_code, site.name AS site_name,"
                        + "       (SELECT pi.identifier_value FROM core.patient_identifiers pi"
                        + "         WHERE pi.patient_id = t.patient_id"
                        + "         ORDER BY pi.id LIMIT 1) AS patient_code"
                        + " FROM core.tpt_records t"
                        + " JOIN core.sites site ON site.id = t.site_id" + geoJoin
                        + " WHERE t.voided = FALSE AND t.record_date >= ? AND t.record_date <= ?"
                        + SortSpec.orderBy(sort, dir, TPT_SORTABLE,
                                "t.record_date DESC NULLS LAST, t.id DESC")
                        + " LIMIT ? OFFSET ?",
                (rs, i) -> new TptRecord(
                        rs.getLong("id"),
                        toLocal(rs.getDate("record_date")),
                        toLocal(rs.getDate("tpt_followup_date")),
                        toLocal(rs.getDate("tpt_end_date")),
                        rs.getString("tpt_outcome"),
                        rs.getString("tpt_order_number"),
                        rs.getString("tpt_status"),
                        rs.getString("tpt_regimen"),
                        rs.getString("adherence"),
                        rs.getBigDecimal("weight_kg"),
                        toLocal(rs.getDate("next_visit_date")),
                        rs.getLong("patient_id"),
                        rs.getString("patient_code"),
                        rs.getString("site_code"),
                        rs.getString("site_name")),
                pagedArgs.toArray());

        return new RecordPage(rows, total == null ? 0L : total, safePage, safeSize);
    }

    private static LocalDate toLocal(java.sql.Date d) {
        return d == null ? null : d.toLocalDate();
    }

    public record TptSummary(
            long totalAllTime,
            long startedInPeriod,
            long completedInPeriod,
            long ongoing,
            BigDecimal completionPct,
            List<YearBucket> yearly,
            List<Bucket> outcomes,
            List<Bucket> adherence,
            List<Bucket> statuses,
            List<Bucket> regimens,
            LocalDate periodFrom,
            LocalDate periodTo
    ) {}

    public record YearBucket(int year, long count) {}
    public record Bucket(String label, long count) {}

    public record TptRecord(
            long id,
            LocalDate recordDate,
            LocalDate followupDate,
            LocalDate endDate,
            String outcome,
            String orderNumber,
            String tptStatus,
            String tptRegimen,
            String adherence,
            BigDecimal weightKg,
            LocalDate nextVisitDate,
            long patientId,
            String patientCode,
            String siteCode,
            String siteName
    ) {}

    public record RecordPage(
            List<TptRecord> content,
            long total,
            int page,
            int size
    ) {}
}
