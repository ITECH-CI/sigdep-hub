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
 * Closure analytics — reads core.closures. A closure marks the end of
 * the active care episode for a patient: décès, transfert, arrêt
 * volontaire, perdu de vue, négatif sorti, etc. (encoded in closure_type).
 */
@Service
public class ClosureService {

    private final JdbcTemplate jdbc;

    public ClosureService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Upstream emits closure_type as an English enum-like code
     * (DEATH / TRANSFER / VOLUNTARY_STOP / HIV_NEGATIVE / LOST / …). We
     * decode it inline in SQL so the entire surface — KPIs, distribution,
     * listing, CSV — speaks French without touching the ingested rows.
     *
     * Anything we don't recognise is passed through as-is.
     */
    private static final String CLOSURE_TYPE_LABEL =
            "CASE upper(coalesce(c.closure_type, ''))"
                    + "  WHEN 'DEATH'           THEN 'Décès'"
                    + "  WHEN 'TRANSFER'        THEN 'Transfert'"
                    + "  WHEN 'TRANSFER_OUT'    THEN 'Transfert sortant'"
                    + "  WHEN 'TRANSFER_IN'     THEN 'Transfert entrant'"
                    + "  WHEN 'VOLUNTARY_STOP'  THEN 'Arrêt volontaire'"
                    + "  WHEN 'HIV_NEGATIVE'    THEN 'Confirmé négatif'"
                    + "  WHEN 'LOST'            THEN 'Perdu de vue'"
                    + "  WHEN 'LOST_TO_FOLLOWUP' THEN 'Perdu de vue'"
                    + "  WHEN 'REFERRED'        THEN 'Référé'"
                    + "  WHEN ''                THEN '(non renseigné)'"
                    + "  ELSE c.closure_type END";

    private static String geoFilter(Long regionId, Long districtId, Long siteId) {
        if (siteId != null) {
            return " JOIN core.sites s ON s.id = c.site_id AND s.id = ?";
        }
        if (districtId != null) {
            return " JOIN core.sites s ON s.id = c.site_id AND s.district_id = ?";
        }
        if (regionId != null) {
            return " JOIN core.sites s ON s.id = c.site_id"
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

    public ClosureSummary summary(int months, Long regionId, Long districtId, Long siteId) {
        LocalDate since = LocalDate.now().minusMonths(months);
        String region = geoFilter(regionId, districtId, siteId);

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM core.closures c" + region
                        + " WHERE c.voided = FALSE",
                Long.class, geoArgs(regionId, districtId, siteId));

        Long inPeriod = jdbc.queryForObject(
                "SELECT count(*) FROM core.closures c" + region
                        + " WHERE c.voided = FALSE AND c.closure_date >= ?",
                Long.class, geoArgs(regionId, districtId, siteId, since));

        // Upstream codes are upper-case English enums (DEATH, TRANSFER, …),
        // but a legacy payload could be lower-cased or already translated —
        // so we normalise to upper-case and match on a stable English
        // substring rather than the localised label.
        Long deaths = jdbc.queryForObject(
                "SELECT count(*) FROM core.closures c" + region
                        + " WHERE c.voided = FALSE AND c.closure_date >= ?"
                        + "   AND upper(coalesce(c.closure_type, '')) LIKE '%DEATH%'",
                Long.class, geoArgs(regionId, districtId, siteId, since));

        Long transfers = jdbc.queryForObject(
                "SELECT count(*) FROM core.closures c" + region
                        + " WHERE c.voided = FALSE AND c.closure_date >= ?"
                        + "   AND upper(coalesce(c.closure_type, '')) LIKE '%TRANSFER%'",
                Long.class, geoArgs(regionId, districtId, siteId, since));

        BigDecimal mortalityPct = inPeriod == null || inPeriod == 0 ? null
                : new BigDecimal(deaths == null ? 0L : deaths)
                        .multiply(new BigDecimal(100))
                        .divide(new BigDecimal(inPeriod), 1, RoundingMode.HALF_UP);

        List<YearBucket> yearly = jdbc.query(
                "SELECT EXTRACT(year FROM c.closure_date)::int AS yr, count(*) AS n"
                        + " FROM core.closures c" + region
                        + " WHERE c.voided = FALSE AND c.closure_date >= ?"
                        + " GROUP BY yr ORDER BY yr",
                (rs, i) -> new YearBucket(rs.getInt("yr"), rs.getLong("n")),
                geoArgs(regionId, districtId, siteId, since));

        List<Bucket> types = jdbc.query(
                "SELECT " + CLOSURE_TYPE_LABEL + " AS k, count(*) AS n"
                        + " FROM core.closures c" + region
                        + " WHERE c.voided = FALSE AND c.closure_date >= ?"
                        + " GROUP BY k ORDER BY n DESC",
                (rs, i) -> new Bucket(rs.getString("k"), rs.getLong("n")),
                geoArgs(regionId, districtId, siteId, since));

        List<Bucket> deathCauses = jdbc.query(
                "SELECT COALESCE(c.death_cause_text, c.death_cause_code, '(non renseigné)') AS k, count(*) AS n"
                        + " FROM core.closures c" + region
                        + " WHERE c.voided = FALSE AND c.closure_date >= ?"
                        + "   AND upper(coalesce(c.closure_type, '')) LIKE '%DEATH%'"
                        + " GROUP BY k ORDER BY n DESC LIMIT 10",
                (rs, i) -> new Bucket(rs.getString("k"), rs.getLong("n")),
                geoArgs(regionId, districtId, siteId, since));

        return new ClosureSummary(
                total == null ? 0L : total,
                inPeriod == null ? 0L : inPeriod,
                deaths == null ? 0L : deaths,
                transfers == null ? 0L : transfers,
                mortalityPct,
                yearly, types, deathCauses, months);
    }

    private static final Map<String, String> CLOSURE_SORTABLE = Map.of(
            "date",    "c.closure_date",
            "patient", "c.patient_id",
            "site",    "site.code",
            "type",    "c.closure_type"
    );

    public RecordPage records(int months, Long regionId, Long districtId, Long siteId,
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
                "SELECT count(*) FROM core.closures c"
                        + " JOIN core.sites site ON site.id = c.site_id" + geoJoin
                        + " WHERE c.voided = FALSE AND c.closure_date >= ?",
                Long.class, args.toArray());

        List<Object> pagedArgs = new ArrayList<>(args);
        pagedArgs.add(safeSize);
        pagedArgs.add(offset);

        List<ClosureRecord> rows = jdbc.query(
                "SELECT c.id, c.patient_id, c.closure_date,"
                        + "       " + CLOSURE_TYPE_LABEL + " AS closure_type,"
                        + "       c.death_date, c.actual_death_date,"
                        + "       c.death_cause_code, c.death_cause_text,"
                        + "       c.transfer_date, c.transfer_destination, c.transfer_reason,"
                        + "       c.voluntary_stop_date, c.hiv_negative_date,"
                        + "       site.code AS site_code, site.name AS site_name,"
                        + "       (SELECT pi.identifier_value FROM core.patient_identifiers pi"
                        + "         WHERE pi.patient_id = c.patient_id"
                        + "         ORDER BY pi.id LIMIT 1) AS patient_code"
                        + " FROM core.closures c"
                        + " JOIN core.sites site ON site.id = c.site_id" + geoJoin
                        + " WHERE c.voided = FALSE AND c.closure_date >= ?"
                        + SortSpec.orderBy(sort, dir, CLOSURE_SORTABLE,
                                "c.closure_date DESC NULLS LAST, c.id DESC")
                        + " LIMIT ? OFFSET ?",
                (rs, i) -> new ClosureRecord(
                        rs.getLong("id"),
                        rs.getLong("patient_id"),
                        rs.getString("patient_code"),
                        toLocal(rs.getDate("closure_date")),
                        rs.getString("closure_type"),
                        toLocal(rs.getDate("death_date")),
                        toLocal(rs.getDate("actual_death_date")),
                        rs.getString("death_cause_code"),
                        rs.getString("death_cause_text"),
                        toLocal(rs.getDate("transfer_date")),
                        rs.getString("transfer_destination"),
                        rs.getString("transfer_reason"),
                        toLocal(rs.getDate("voluntary_stop_date")),
                        toLocal(rs.getDate("hiv_negative_date")),
                        rs.getString("site_code"),
                        rs.getString("site_name")),
                pagedArgs.toArray());

        return new RecordPage(rows, total == null ? 0L : total, safePage, safeSize);
    }

    private static LocalDate toLocal(java.sql.Date d) {
        return d == null ? null : d.toLocalDate();
    }

    public record YearBucket(int year, long count) {}
    public record Bucket(String label, long count) {}

    public record ClosureSummary(
            long totalAllTime,
            long inPeriod,
            long deaths,
            long transfers,
            BigDecimal mortalityPct,
            List<YearBucket> yearly,
            List<Bucket> types,
            List<Bucket> deathCauses,
            int periodMonths
    ) {}

    public record ClosureRecord(
            long id,
            long patientId,
            String patientCode,
            LocalDate closureDate,
            String closureType,
            LocalDate deathDate,
            LocalDate actualDeathDate,
            String deathCauseCode,
            String deathCauseText,
            LocalDate transferDate,
            String transferDestination,
            String transferReason,
            LocalDate voluntaryStopDate,
            LocalDate hivNegativeDate,
            String siteCode,
            String siteName
    ) {}

    public record RecordPage(
            List<ClosureRecord> content,
            long total,
            int page,
            int size
    ) {}
}
