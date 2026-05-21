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
 * Treatment-initiation analytics — reads core.treatment_initiations and
 * (optionally) the pediatric companion table for paediatric KPIs.
 *
 * The "initiation" is the moment a patient enters ARV — captured by the
 * "fiche initiale" OpenMRS encounter. We surface counts in period,
 * porte d'entrée, régime initial, stade OMS initial, etc.
 */
@Service
public class InitiationService {

    private final JdbcTemplate jdbc;

    public InitiationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static String geoFilter(Long regionId, Long districtId, Long siteId) {
        if (siteId != null) {
            return " JOIN core.sites s ON s.id = i.site_id AND s.id = ?";
        }
        if (districtId != null) {
            return " JOIN core.sites s ON s.id = i.site_id AND s.district_id = ?";
        }
        if (regionId != null) {
            return " JOIN core.sites s ON s.id = i.site_id"
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

    public InitiationSummary summary(int months, Long regionId, Long districtId, Long siteId) {
        LocalDate since = LocalDate.now().minusMonths(months);
        String region = geoFilter(regionId, districtId, siteId);

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM core.treatment_initiations i" + region
                        + " WHERE i.voided = FALSE",
                Long.class, geoArgs(regionId, districtId, siteId));

        Long inPeriod = jdbc.queryForObject(
                "SELECT count(*) FROM core.treatment_initiations i" + region
                        + " WHERE i.voided = FALSE AND i.arv_init_date >= ?",
                Long.class, geoArgs(regionId, districtId, siteId, since));

        Long pediatric = jdbc.queryForObject(
                "SELECT count(*) FROM core.treatment_initiations i"
                        + " JOIN core.treatment_initiations_pediatric p ON p.initiation_id = i.id"
                        + (region.isEmpty() ? "" : region)
                        + " WHERE i.voided = FALSE AND i.arv_init_date >= ?",
                Long.class, geoArgs(regionId, districtId, siteId, since));

        // 'referred' is a free-form coded label coming from the upstream
        // form (Oui / Non / null) — match on a yes-ish pattern rather than
        // a boolean equality.
        Long referred = jdbc.queryForObject(
                "SELECT count(*) FROM core.treatment_initiations i" + region
                        + " WHERE i.voided = FALSE AND i.arv_init_date >= ?"
                        + "   AND lower(coalesce(i.referred, '')) IN ('oui', 'yes', 'true', '1')",
                Long.class, geoArgs(regionId, districtId, siteId, since));

        BigDecimal pediatricPct = inPeriod == null || inPeriod == 0 ? null
                : new BigDecimal(pediatric == null ? 0L : pediatric)
                        .multiply(new BigDecimal(100))
                        .divide(new BigDecimal(inPeriod), 1, RoundingMode.HALF_UP);

        List<YearBucket> yearly = jdbc.query(
                "SELECT EXTRACT(year FROM i.arv_init_date)::int AS yr, count(*) AS n"
                        + " FROM core.treatment_initiations i" + region
                        + " WHERE i.voided = FALSE AND i.arv_init_date >= ?"
                        + " GROUP BY yr ORDER BY yr",
                (rs, i) -> new YearBucket(rs.getInt("yr"), rs.getLong("n")),
                geoArgs(regionId, districtId, siteId, since));

        List<Bucket> entryPoints = jdbc.query(
                "SELECT COALESCE(i.entry_point, '(non renseigné)') AS k, count(*) AS n"
                        + " FROM core.treatment_initiations i" + region
                        + " WHERE i.voided = FALSE AND i.arv_init_date >= ?"
                        + " GROUP BY k ORDER BY n DESC LIMIT 15",
                (rs, i) -> new Bucket(rs.getString("k"), rs.getLong("n")),
                geoArgs(regionId, districtId, siteId, since));

        List<Bucket> regimens = jdbc.query(
                "SELECT COALESCE(i.arv_regimen_initial, '(non renseigné)') AS k, count(*) AS n"
                        + " FROM core.treatment_initiations i" + region
                        + " WHERE i.voided = FALSE AND i.arv_init_date >= ?"
                        + " GROUP BY k ORDER BY n DESC LIMIT 10",
                (rs, i) -> new Bucket(rs.getString("k"), rs.getLong("n")),
                geoArgs(regionId, districtId, siteId, since));

        List<Bucket> whoStages = jdbc.query(
                "SELECT COALESCE(i.who_stage_initial, '(non renseigné)') AS k, count(*) AS n"
                        + " FROM core.treatment_initiations i" + region
                        + " WHERE i.voided = FALSE AND i.arv_init_date >= ?"
                        + " GROUP BY k ORDER BY n DESC",
                (rs, i) -> new Bucket(rs.getString("k"), rs.getLong("n")),
                geoArgs(regionId, districtId, siteId, since));

        return new InitiationSummary(
                total == null ? 0L : total,
                inPeriod == null ? 0L : inPeriod,
                pediatric == null ? 0L : pediatric,
                referred == null ? 0L : referred,
                pediatricPct,
                yearly, entryPoints, regimens, whoStages, months);
    }

    private static final Map<String, String> INIT_SORTABLE = Map.of(
            "date",    "i.arv_init_date",
            "patient", "i.patient_id",
            "site",    "site.code",
            "regimen", "i.arv_regimen_initial",
            "stage",   "i.who_stage_initial"
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
                "SELECT count(*) FROM core.treatment_initiations i"
                        + " JOIN core.sites site ON site.id = i.site_id" + geoJoin
                        + " WHERE i.voided = FALSE AND i.arv_init_date >= ?",
                Long.class, args.toArray());

        List<Object> pagedArgs = new ArrayList<>(args);
        pagedArgs.add(safeSize);
        pagedArgs.add(offset);

        List<InitiationRecord> rows = jdbc.query(
                "SELECT i.id, i.patient_id, i.arv_init_date, i.enrollment_date,"
                        + "       i.entry_point, i.hiv_type, i.arv_regimen_initial,"
                        + "       i.who_stage_initial, i.cdc_stage_initial,"
                        + "       i.weight_initial_kg, i.karnofsky_score,"
                        + "       i.referred, i.referred_origin,"
                        + "       site.code AS site_code, site.name AS site_name,"
                        + "       (SELECT pi.identifier_value FROM core.patient_identifiers pi"
                        + "         WHERE pi.patient_id = i.patient_id"
                        + "         ORDER BY pi.id LIMIT 1) AS patient_code"
                        + " FROM core.treatment_initiations i"
                        + " JOIN core.sites site ON site.id = i.site_id" + geoJoin
                        + " WHERE i.voided = FALSE AND i.arv_init_date >= ?"
                        + SortSpec.orderBy(sort, dir, INIT_SORTABLE,
                                "i.arv_init_date DESC NULLS LAST, i.id DESC")
                        + " LIMIT ? OFFSET ?",
                (rs, i) -> new InitiationRecord(
                        rs.getLong("id"),
                        rs.getLong("patient_id"),
                        rs.getString("patient_code"),
                        toLocal(rs.getDate("arv_init_date")),
                        toLocal(rs.getDate("enrollment_date")),
                        rs.getString("entry_point"),
                        rs.getString("hiv_type"),
                        rs.getString("arv_regimen_initial"),
                        rs.getString("who_stage_initial"),
                        rs.getString("cdc_stage_initial"),
                        rs.getBigDecimal("weight_initial_kg"),
                        (Integer) rs.getObject("karnofsky_score"),
                        rs.getString("referred"),
                        rs.getString("referred_origin"),
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

    public record InitiationSummary(
            long totalAllTime,
            long inPeriod,
            long pediatric,
            long referred,
            BigDecimal pediatricPct,
            List<YearBucket> yearly,
            List<Bucket> entryPoints,
            List<Bucket> regimens,
            List<Bucket> whoStages,
            int periodMonths
    ) {}

    public record InitiationRecord(
            long id,
            long patientId,
            String patientCode,
            LocalDate arvInitDate,
            LocalDate enrollmentDate,
            String entryPoint,
            String hivType,
            String arvRegimenInitial,
            String whoStageInitial,
            String cdcStageInitial,
            BigDecimal weightInitialKg,
            Integer karnofskyScore,
            String referred,
            String referredOrigin,
            String siteCode,
            String siteName
    ) {}

    public record RecordPage(
            List<InitiationRecord> content,
            long total,
            int page,
            int size
    ) {}
}
