package ci.itechciv.sigdep.hub.domain.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * PEPFAR indicator computations against core.*.
 *
 * Quarter convention: USAID fiscal year (Q1 = Oct-Dec, Q2 = Jan-Mar,
 * Q3 = Apr-Jun, Q4 = Jul-Sep). The fiscal year is the calendar year of
 * the September that ends it (FY2025 = 2024-10-01 to 2025-09-30).
 *
 * Age is computed at the *end* of the quarter, per PEPFAR MER convention.
 * Age bands: <15, 15-24, 25-49, 50+.
 *
 * Indicators implemented in this v1:
 *  - TX_NEW: new ARV initiations during the quarter.
 *  - TX_CURR: patients on treatment at end of quarter — has an initiation
 *    on or before Q, no closure on or before Q, and the last visit's
 *    arv_treatment_days dispensation either still covers Q or expired
 *    no more than 28 days before Q. If no visit with a dispensation is
 *    found, we fall back to "has init + no closure" (permissive proxy).
 *  - TX_PVLS denominator: patients on ARV ≥ 6 months at Q with an
 *    eligible viral load test in the past 12 months.
 *  - TX_PVLS numerator: subset of the denominator with VL < 1000
 *    copies/mL on the most recent test.
 *
 * Out of scope here: TX_RTT, TX_ML (require an interruption-in-treatment
 * model we don't have yet).
 */
@Service
public class PepfarService {

    private static final String VL_UUID = "856AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final BigDecimal SUPPRESSED = new BigDecimal("1000");
    private static final int CURR_GRACE_DAYS = 28;
    private static final int PVLS_ON_ARV_MONTHS = 6;

    private final JdbcTemplate jdbc;

    public PepfarService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Maps "FY2025-Q1" to the [start, end] dates of that quarter. */
    public static QuarterRange quarter(int fiscalYear, int q) {
        if (q < 1 || q > 4) throw new IllegalArgumentException("quarter must be 1..4");
        // Q1: Oct-Dec of FY-1 ; Q2: Jan-Mar FY ; Q3: Apr-Jun FY ; Q4: Jul-Sep FY
        LocalDate start = switch (q) {
            case 1 -> LocalDate.of(fiscalYear - 1, 10, 1);
            case 2 -> LocalDate.of(fiscalYear, 1, 1);
            case 3 -> LocalDate.of(fiscalYear, 4, 1);
            case 4 -> LocalDate.of(fiscalYear, 7, 1);
            default -> throw new IllegalStateException();
        };
        LocalDate end = start.plusMonths(3).minusDays(1);
        return new QuarterRange(fiscalYear, q, start, end);
    }

    public PepfarReport report(int fiscalYear, int q, Long regionId, Long districtId, Long siteId) {
        QuarterRange qr = quarter(fiscalYear, q);
        return new PepfarReport(qr,
                txNew(qr, regionId, districtId, siteId),
                txCurr(qr, regionId, districtId, siteId),
                txPvls(qr, regionId, districtId, siteId),
                hts(qr, regionId, districtId, siteId),
                pmtct(qr, regionId, districtId, siteId),
                tbPrev(qr, regionId, districtId, siteId),
                txCurrByMsd(qr, regionId, districtId, siteId));
    }

    // ---------- TX_CURR by MSD (modèle de soin) ------------------------------

    /**
     * File active désagrégée par modèle de soins différenciés (MSD), tel
     * que captée sur la dernière visite avant la fin du trimestre :
     * Standard / IVSA / Échec thérapeutique / (non renseigné).
     *
     * Donne une vue stratégique du portefeuille de prise en charge : quel
     * % de la cohorte est sur le modèle IVSA (patients non stables) vs
     * stable (Standard) vs en échec.
     */
    private List<MsdBucket> txCurrByMsd(QuarterRange qr, Long regionId, Long districtId, Long siteId) {
        String region = geoJoinFor("p", regionId, districtId, siteId);

        String sql =
                "WITH last_visit AS ("
                + "  SELECT DISTINCT ON (v.patient_id)"
                + "         v.patient_id, v.ivsa_msd_code"
                + "  FROM core.visits v"
                + "  WHERE v.voided = FALSE AND v.visit_date <= ?"
                + "  ORDER BY v.patient_id, v.visit_date DESC"
                + ")"
                + " SELECT COALESCE(lv.ivsa_msd_code, '(non renseigné)') AS msd,"
                + "        count(*) AS n"
                + " FROM core.patients p"
                + " JOIN core.treatment_initiations ti ON ti.patient_id = p.id"
                + "   AND ti.voided = FALSE"
                + "   AND COALESCE(ti.arv_init_date, ti.enrollment_date) <= ?"
                + " LEFT JOIN last_visit lv ON lv.patient_id = p.id"
                + region
                + " WHERE p.voided = FALSE"
                + "   AND NOT EXISTS ("
                + "     SELECT 1 FROM core.closures c"
                + "     WHERE c.patient_id = p.id AND c.voided = FALSE"
                + "       AND c.closure_date <= ?)"
                + " GROUP BY msd"
                + " ORDER BY n DESC";

        List<Object> args = new ArrayList<>();
        args.add(qr.end()); // last_visit watermark
        args.add(qr.end()); // init <= end
        Long g = geoArg(regionId, districtId, siteId);
        if (g != null) args.add(g);
        args.add(qr.end()); // closure <= end

        // RowMapper rather than RowCallbackHandler to disambiguate the
        // overloaded jdbc.query(sql, ..., args[]) — the lambda's empty
        // return makes javac unable to pick between the two otherwise.
        return jdbc.query(sql,
                (rs, i) -> new MsdBucket(rs.getString("msd"), rs.getLong("n")),
                args.toArray());
    }

    // ---------- TX_NEW --------------------------------------------------------

    private Disaggregated txNew(QuarterRange qr, Long regionId, Long districtId, Long siteId) {
        // age at end of quarter — DATE_PART would be one option, but
        // birth dates that are just years (born "1971-01-01" placeholder)
        // give consistent results with year-of-month-of-day arithmetic.
        String ageExpr = ageExpr(qr.end());
        String region = regionJoin(regionId, districtId, siteId);

        String sql = "SELECT p.sex AS sex, " + ageBandExpr(ageExpr) + " AS band, count(*) AS n"
                + " FROM core.treatment_initiations ti"
                + " JOIN core.patients p ON p.id = ti.patient_id" + region
                + " WHERE ti.voided = FALSE"
                + "   AND ti.arv_init_date BETWEEN ? AND ?"
                + " GROUP BY p.sex, " + ageBandExpr(ageExpr);

        return aggregate(sql, buildArgs(regionId, districtId, siteId, qr.start(), qr.end()));
    }

    // ---------- TX_CURR -------------------------------------------------------

    private Disaggregated txCurr(QuarterRange qr, Long regionId, Long districtId, Long siteId) {
        // "Active" subquery: patients with init <= Q.end, no closure <= Q.end,
        // and last visit before Q.end with arv_treatment_days has remaining
        // coverage (or expired ≤ 28 days). Patients with init but no visits
        // at all are kept (permissive fallback).
        String activeSql =
                "WITH active_patients AS ("
                + "  SELECT p.id, p.sex, p.birth_date, p.site_id"
                + "  FROM core.patients p"
                + "  JOIN core.treatment_initiations ti ON ti.patient_id = p.id"
                + "    AND ti.voided = FALSE"
                + "    AND COALESCE(ti.arv_init_date, ti.enrollment_date) <= ?"
                + "  WHERE p.voided = FALSE"
                + "    AND NOT EXISTS ("
                + "      SELECT 1 FROM core.closures c"
                + "      WHERE c.patient_id = p.id AND c.voided = FALSE"
                + "        AND c.closure_date <= ?)"
                + "    AND ("
                + "      NOT EXISTS ("
                + "        SELECT 1 FROM core.visits v"
                + "        WHERE v.patient_id = p.id AND v.voided = FALSE"
                + "          AND v.visit_date <= ? AND v.arv_treatment_days IS NOT NULL)"
                + "      OR EXISTS ("
                + "        SELECT 1 FROM ("
                + "          SELECT v.visit_date, v.arv_treatment_days,"
                + "                 row_number() OVER (PARTITION BY v.patient_id"
                + "                                    ORDER BY v.visit_date DESC) AS rn"
                + "          FROM core.visits v"
                + "          WHERE v.patient_id = p.id AND v.voided = FALSE"
                + "            AND v.visit_date <= ? AND v.arv_treatment_days IS NOT NULL"
                + "        ) lv"
                + "        WHERE lv.rn = 1"
                + "          AND lv.visit_date + lv.arv_treatment_days + ? >= ?))"
                + "  GROUP BY p.id, p.sex, p.birth_date, p.site_id"
                + ")";

        // join to sites/districts only if a geo filter applied
        String regionFromActive = regionJoinAp(regionId, districtId, siteId);

        String ageExprAp = "DATE_PART('year', AGE(DATE '" + qr.end() + "', ap.birth_date))";

        String sql = activeSql
                + " SELECT ap.sex AS sex, " + ageBandExpr(ageExprAp) + " AS band, count(*) AS n"
                + " FROM active_patients ap" + regionFromActive
                + " GROUP BY ap.sex, " + ageBandExpr(ageExprAp);

        // Args order: <= ?, <= ?, <= ?, <= ?, grace, end, [geoId]
        List<Object> args = new ArrayList<>();
        args.add(qr.end());                      // init <= end
        args.add(qr.end());                      // closure <= end
        args.add(qr.end());                      // visit <= end (NOT EXISTS)
        args.add(qr.end());                      // visit <= end (latest)
        args.add(CURR_GRACE_DAYS);               // grace
        args.add(qr.end());                      // last visit + days + grace >= end
        Long g = geoArg(regionId, districtId, siteId);
        if (g != null) args.add(g);

        return aggregate(sql, args.toArray());
    }

    // ---------- TX_PVLS -------------------------------------------------------

    private TxPvls txPvls(QuarterRange qr, Long regionId, Long districtId, Long siteId) {
        // Eligible: on ARV ≥ 6 months at Q.end (init <= Q.end - 6 months),
        // no closure on or before Q.end, has at least one VL test with
        // numeric value in [Q.end - 12 months, Q.end].
        LocalDate sinceArv = qr.end().minusMonths(PVLS_ON_ARV_MONTHS);
        LocalDate sinceVl  = qr.end().minusMonths(12);

        // Pull every (patient, sex, age_band, latest_vl_value) triplet that
        // qualifies as denominator; the numerator is the subset with VL < 1000.
        String sql =
                "WITH eligible AS ("
                + "  SELECT p.id, p.sex, p.birth_date, p.site_id"
                + "  FROM core.patients p"
                + "  JOIN core.treatment_initiations ti ON ti.patient_id = p.id"
                + "    AND ti.voided = FALSE"
                + "    AND COALESCE(ti.arv_init_date, ti.enrollment_date) <= ?"
                + "  WHERE p.voided = FALSE"
                + "    AND NOT EXISTS ("
                + "      SELECT 1 FROM core.closures c"
                + "      WHERE c.patient_id = p.id AND c.voided = FALSE"
                + "        AND c.closure_date <= ?)"
                + "  GROUP BY p.id, p.sex, p.birth_date, p.site_id"
                + "), latest_vl AS ("
                + "  SELECT DISTINCT ON (lr.patient_id)"
                + "         lr.patient_id, lr.value_numeric, lr.exam_date"
                + "  FROM core.lab_results lr"
                + "  WHERE lr.voided = FALSE AND lr.test_uuid = ?"
                + "    AND lr.value_numeric IS NOT NULL"
                + "    AND lr.exam_date BETWEEN ? AND ?"
                + "  ORDER BY lr.patient_id, lr.exam_date DESC"
                + ")"
                + " SELECT e.sex AS sex, " + ageBandExpr("DATE_PART('year', AGE(DATE '" + qr.end() + "', e.birth_date))") + " AS band,"
                + "        count(*) AS denom,"
                + "        count(*) FILTER (WHERE lv.value_numeric < ?) AS numer"
                + " FROM eligible e"
                + " JOIN latest_vl lv ON lv.patient_id = e.id"
                + regionJoinE(regionId, districtId, siteId)
                + " GROUP BY e.sex, " + ageBandExpr("DATE_PART('year', AGE(DATE '" + qr.end() + "', e.birth_date))");

        List<Object> args = new ArrayList<>();
        args.add(sinceArv);     // init eligibility
        args.add(qr.end());     // no closure ≤ end
        args.add(VL_UUID);      // latest VL: test
        args.add(sinceVl);      // VL window start
        args.add(qr.end());     // VL window end
        args.add(SUPPRESSED);   // numerator threshold
        Long g = geoArg(regionId, districtId, siteId);
        if (g != null) args.add(g);

        List<DisaggCell> denomCells = new ArrayList<>();
        List<DisaggCell> numerCells = new ArrayList<>();

        jdbc.query(sql, rs -> {
            String sex = rs.getString("sex");
            String band = rs.getString("band");
            long d = rs.getLong("denom");
            long n = rs.getLong("numer");
            denomCells.add(new DisaggCell(sex, band, d));
            numerCells.add(new DisaggCell(sex, band, n));
        }, args.toArray());

        Disaggregated denom = new Disaggregated(sumCells(denomCells), denomCells);
        Disaggregated numer = new Disaggregated(sumCells(numerCells), numerCells);

        BigDecimal pct = denom.total() == 0 ? null
                : new BigDecimal(numer.total()).multiply(new BigDecimal(100))
                        .divide(new BigDecimal(denom.total()), 1, RoundingMode.HALF_UP);

        return new TxPvls(denom, numer, pct);
    }

    // ---------- HTS_TST / HTS_POS --------------------------------------------

    /**
     * HTS = screenings registered during the quarter, disaggregated by
     * sex and age band. The screenings stream is anonymous (no
     * patient_id), so age comes from the record's own {@code age}
     * column captured at testing time.
     *
     * - HTS_TST : tested in the quarter (count of voided=false rows
     *             with screening_date in [start, end])
     * - HTS_POS : subset with final_result = 'POS'
     */
    private Hts hts(QuarterRange qr, Long regionId, Long districtId, Long siteId) {
        String region = anonGeoJoin("sc", regionId, districtId, siteId);
        String bandExpr = ageBandExpr("sc.age");

        String sql = "SELECT sc.gender AS sex, " + bandExpr + " AS band,"
                + "        count(*) AS tst,"
                + "        count(*) FILTER (WHERE sc.final_result = 'POS') AS pos"
                + " FROM core.screenings sc" + region
                + " WHERE sc.voided = FALSE"
                + "   AND sc.screening_date BETWEEN ? AND ?"
                + " GROUP BY sc.gender, " + bandExpr;

        List<DisaggCell> tstCells = new ArrayList<>();
        List<DisaggCell> posCells = new ArrayList<>();
        jdbc.query(sql, rs -> {
            String sex = normalizeSex(rs.getString("sex"));
            String band = rs.getString("band");
            tstCells.add(new DisaggCell(sex, band, rs.getLong("tst")));
            posCells.add(new DisaggCell(sex, band, rs.getLong("pos")));
        }, buildArgs(regionId, districtId, siteId, qr.start(), qr.end()));

        Disaggregated tst = new Disaggregated(sumCells(tstCells), tstCells);
        Disaggregated pos = new Disaggregated(sumCells(posCells), posCells);
        BigDecimal pct = tst.total() == 0 ? null
                : new BigDecimal(pos.total()).multiply(new BigDecimal(100))
                        .divide(new BigDecimal(tst.total()), 1, RoundingMode.HALF_UP);
        return new Hts(tst, pos, pct);
    }

    // ---------- PMTCT (STAT / ART / EID) -------------------------------------

    /**
     * PMTCT — three indicators bundled together because the data lives
     * on the same upstream forms. All are anonymous (no patient join).
     *
     * - PMTCT_STAT (numer) : pregnant women whose HIV status is known
     *   at entry to ANC during the quarter. We use any spousal screening
     *   result + arv status at registering as a proxy for "status known".
     *   denom = total women started in the quarter, numer = subset with
     *   a known status (status_at_registering OR test_result present).
     * - PMTCT_ART : pregnant women living with HIV who received ARV.
     *   denom = women started in Q with arv_status_at_registering not
     *   null (i.e. HIV-confirmed), numer = subset where the status
     *   indicates ARV (label contains 'ARV' or 'sous').
     * - PMTCT_EID : HIV-exposed infants with a PCR1 sample collected
     *   within 2 months of birth. denom = exposed infants born in Q,
     *   numer = subset with pcr1_sampling_date within 60 days of birth.
     */
    private Pmtct pmtct(QuarterRange qr, Long regionId, Long districtId, Long siteId) {
        Pair stat = pmtctStat(qr, regionId, districtId, siteId);
        Pair art  = pmtctArt(qr, regionId, districtId, siteId);
        Pair eid  = pmtctEid(qr, regionId, districtId, siteId);
        return new Pmtct(stat, art, eid);
    }

    private Pair pmtctStat(QuarterRange qr, Long regionId, Long districtId, Long siteId) {
        String region = anonGeoJoin("m", regionId, districtId, siteId);
        String bandExpr = ageBandExpr("m.age");

        // "Status known" = the form captured either an ARV status at
        // registering (already HIV-confirmed) or a spousal screening
        // result was recorded (test offered). We collapse these into one
        // disaggregated count where denom = all, numer = status-known.
        String sql = "SELECT 'F' AS sex, " + bandExpr + " AS band,"
                + "        count(*) AS denom,"
                + "        count(*) FILTER (WHERE m.arv_status_at_registering IS NOT NULL"
                + "                          OR m.spousal_screening_result IS NOT NULL) AS numer"
                + " FROM core.ptme_mothers m" + region
                + " WHERE m.voided = FALSE"
                + "   AND m.start_date BETWEEN ? AND ?"
                + " GROUP BY band";
        return runDenomNumer(sql, buildArgs(regionId, districtId, siteId, qr.start(), qr.end()));
    }

    private Pair pmtctArt(QuarterRange qr, Long regionId, Long districtId, Long siteId) {
        String region = anonGeoJoin("m", regionId, districtId, siteId);
        String bandExpr = ageBandExpr("m.age");

        // denom = HIV-confirmed pregnant women in Q ; numer = subset
        // already on ARV at entry or newly initiated (label heuristic).
        String sql = "SELECT 'F' AS sex, " + bandExpr + " AS band,"
                + "        count(*) AS denom,"
                + "        count(*) FILTER (WHERE m.arv_status_at_registering ILIKE '%ARV%'"
                + "                          OR m.arv_status_at_registering ILIKE '%diagnostiqu%') AS numer"
                + " FROM core.ptme_mothers m" + region
                + " WHERE m.voided = FALSE"
                + "   AND m.start_date BETWEEN ? AND ?"
                + "   AND m.arv_status_at_registering IS NOT NULL"
                + " GROUP BY band";
        return runDenomNumer(sql, buildArgs(regionId, districtId, siteId, qr.start(), qr.end()));
    }

    private Pair pmtctEid(QuarterRange qr, Long regionId, Long districtId, Long siteId) {
        String region = anonGeoJoin("c", regionId, districtId, siteId);

        // Infants born in the quarter, grouped by sex. Age band is forced
        // to '<15' since every infant lands there anyway — keeps the
        // disaggregation table layout consistent with the rest of the
        // cascade. Numerator = subset with a PCR1 sample collected
        // within 60 days of birth (early infant diagnosis).
        String sql = "SELECT c.gender AS sex,"
                + "        '<15' AS band,"
                + "        count(*) AS denom,"
                + "        count(*) FILTER (WHERE c.pcr1_sampling_date IS NOT NULL"
                + "                          AND c.pcr1_sampling_date - c.birth_date <= 60) AS numer"
                + " FROM core.ptme_children c" + region
                + " WHERE c.voided = FALSE"
                + "   AND c.birth_date BETWEEN ? AND ?"
                + " GROUP BY c.gender";
        return runDenomNumer(sql, buildArgs(regionId, districtId, siteId, qr.start(), qr.end()));
    }

    // ---------- TB_PREV ------------------------------------------------------

    /**
     * TB_PREV — patients who completed TB Preventive Treatment during
     * the quarter. We treat a TPT record as "completed" when its
     * tpt_outcome is non-null (the source emits an outcome only on
     * the closing TPT encounter).
     *
     * - denom : patients with a TPT record whose end_date falls in Q
     * - numer : subset with a non-null outcome (= completion observed)
     *
     * Age is taken from core.patients at the patient join, since
     * tpt_records carry patient_id (unlike screenings/PTME).
     */
    private Pair tbPrev(QuarterRange qr, Long regionId, Long districtId, Long siteId) {
        String region = geoJoinFor("p", regionId, districtId, siteId);
        String ageExpr = "DATE_PART('year', AGE(DATE '" + qr.end() + "', p.birth_date))";
        String bandExpr = ageBandExpr(ageExpr);

        String sql = "SELECT p.sex AS sex, " + bandExpr + " AS band,"
                + "        count(*) AS denom,"
                + "        count(*) FILTER (WHERE t.tpt_outcome IS NOT NULL) AS numer"
                + " FROM core.tpt_records t"
                + " JOIN core.patients p ON p.id = t.patient_id" + region
                + " WHERE t.voided = FALSE AND p.voided = FALSE"
                + "   AND t.tpt_end_date BETWEEN ? AND ?"
                + " GROUP BY p.sex, " + bandExpr;
        return runDenomNumer(sql, buildArgs(regionId, districtId, siteId, qr.start(), qr.end()));
    }

    // ---------- helpers -------------------------------------------------------

    private Disaggregated aggregate(String sql, Object[] args) {
        List<DisaggCell> cells = new ArrayList<>();
        jdbc.query(sql, rs -> {
            cells.add(new DisaggCell(
                    rs.getString("sex"),
                    rs.getString("band"),
                    rs.getLong("n")));
        }, args);
        return new Disaggregated(sumCells(cells), cells);
    }

    private static long sumCells(List<DisaggCell> cells) {
        long s = 0;
        for (DisaggCell c : cells) s += c.count();
        return s;
    }

    /**
     * Geo filter for queries with a {@code core.patients p} table in scope.
     * Joins core.sites/districts off {@code p.site_id} unless the filter is
     * site-level (no extra join needed beyond a comparison).
     */
    private static String regionJoin(Long regionId, Long districtId, Long siteId) {
        return geoJoinFor("p", regionId, districtId, siteId);
    }

    /** Same but for the {@code active_patients ap} CTE alias used by TX_CURR. */
    private static String regionJoinAp(Long regionId, Long districtId, Long siteId) {
        return geoJoinFor("ap", regionId, districtId, siteId);
    }

    /** Same but for the {@code eligible e} CTE alias used by TX_PVLS. */
    private static String regionJoinE(Long regionId, Long districtId, Long siteId) {
        return geoJoinFor("e", regionId, districtId, siteId);
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

    /**
     * Geo join for anonymous streams (screenings, ptme_*) where site_id
     * sits directly on the alias table. Same semantics as
     * {@link #geoJoinFor(String, Long, Long, Long)} but kept explicit
     * for readability where the TX_* indicators join through
     * core.patients first.
     */
    private static String anonGeoJoin(String alias, Long regionId, Long districtId, Long siteId) {
        return geoJoinFor(alias, regionId, districtId, siteId);
    }

    /**
     * Run a denom/numer SQL where each row carries (sex, band, denom,
     * numer). Used by HTS_POS, PMTCT_* and TB_PREV.
     */
    private Pair runDenomNumer(String sql, Object[] args) {
        List<DisaggCell> denomCells = new ArrayList<>();
        List<DisaggCell> numerCells = new ArrayList<>();
        jdbc.query(sql, rs -> {
            String sex = normalizeSex(rs.getString("sex"));
            String band = rs.getString("band");
            denomCells.add(new DisaggCell(sex, band, rs.getLong("denom")));
            numerCells.add(new DisaggCell(sex, band, rs.getLong("numer")));
        }, args);
        Disaggregated denom = new Disaggregated(sumCells(denomCells), denomCells);
        Disaggregated numer = new Disaggregated(sumCells(numerCells), numerCells);
        BigDecimal pct = denom.total() == 0 ? null
                : new BigDecimal(numer.total()).multiply(new BigDecimal(100))
                        .divide(new BigDecimal(denom.total()), 1, RoundingMode.HALF_UP);
        return new Pair(denom, numer, pct);
    }

    /** Normalises upstream sex codes to the {M, F, unknown} alphabet
     *  the front-end matrix expects. */
    private static String normalizeSex(String raw) {
        if (raw == null) return "unknown";
        return switch (raw.trim().toUpperCase()) {
            case "M", "MALE",   "MASCULIN"  -> "M";
            case "F", "FEMALE", "FEMININ", "FÉMININ" -> "F";
            default -> "unknown";
        };
    }

    private static Long geoArg(Long regionId, Long districtId, Long siteId) {
        if (siteId != null)     return siteId;
        if (districtId != null) return districtId;
        return regionId;
    }

    /** Age in completed years at the given as-of date. */
    private static String ageExpr(LocalDate asOf) {
        return "DATE_PART('year', AGE(DATE '" + asOf + "', p.birth_date))";
    }

    private static String ageBandExpr(String yearsExpr) {
        return "CASE"
                + "  WHEN " + yearsExpr + " IS NULL THEN 'unknown'"
                + "  WHEN " + yearsExpr + " < 15 THEN '<15'"
                + "  WHEN " + yearsExpr + " < 25 THEN '15-24'"
                + "  WHEN " + yearsExpr + " < 50 THEN '25-49'"
                + "  ELSE '50+'"
                + " END";
    }

    /**
     * The geo JOIN sits in the FROM clause *before* the WHERE — so its
     * parameter must come before the WHERE-clause args.
     */
    private static Object[] buildArgs(Long regionId, Long districtId, Long siteId, Object... rest) {
        Long g = geoArg(regionId, districtId, siteId);
        if (g == null) return rest;
        Object[] out = new Object[rest.length + 1];
        out[0] = g;
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }

    // ---------- records -------------------------------------------------------

    public record QuarterRange(int fiscalYear, int quarter, LocalDate start, LocalDate end) {}

    public record DisaggCell(String sex, String ageBand, long count) {}

    public record Disaggregated(long total, List<DisaggCell> cells) {}

    public record TxPvls(Disaggregated denominator, Disaggregated numerator, BigDecimal pct) {}

    /**
     * Generic denom/numer pair with the computed pct, reused by every
     * indicator that has a "X out of Y" shape: HTS_POS (over HTS_TST),
     * PMTCT_STAT, PMTCT_ART, PMTCT_EID, TB_PREV.
     */
    public record Pair(Disaggregated denominator, Disaggregated numerator, BigDecimal pct) {}

    /** HTS bundle — TST is the universe, POS is the subset. */
    public record Hts(Disaggregated tst, Disaggregated pos, BigDecimal positivityPct) {}

    /** PMTCT bundle — STAT (status known), ART (under ARV), EID (PCR1 <= 2 mo). */
    public record Pmtct(Pair stat, Pair art, Pair eid) {}

    /** File active désagrégée par modèle de soins différenciés (MSD). */
    public record MsdBucket(String msd, long count) {}

    public record PepfarReport(
            QuarterRange period,
            Disaggregated txNew,
            Disaggregated txCurr,
            TxPvls txPvls,
            Hts hts,
            Pmtct pmtct,
            Pair tbPrev,
            List<MsdBucket> txCurrByMsd
    ) {}
}
