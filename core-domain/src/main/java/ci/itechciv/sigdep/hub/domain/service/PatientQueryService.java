package ci.itechciv.sigdep.hub.domain.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Read-only patient listing for the console. We deliberately don't expose
 * names, phone or address — those are filtered out before reaching the hub.
 */
@Service
public class PatientQueryService {

    private final JdbcTemplate jdbc;

    public PatientQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final Map<String, String> PATIENT_SORTABLE = Map.of(
            "id",          "p.id",
            "codeArv",     "code_arv",
            "upid",        "upid",
            "sex",         "p.sex",
            "birthDate",   "p.birth_date",
            "arvInitDate", "arv_init_date",
            "lastVisit",   "last_visit_date",
            "site",        "s.code"
    );

    public PatientPage list(String search, Long regionId, Long districtId, Long siteId,
                            String sort, String dir,
                            int page, int size) {
        int safeSize = Math.max(1, Math.min(500, size));
        int safePage = Math.max(0, page);
        int offset = safePage * safeSize;

        String like = search == null || search.isBlank() ? null : "%" + search.trim() + "%";

        StringBuilder where = new StringBuilder("WHERE p.voided = FALSE");
        List<Object> args = new ArrayList<>();
        if (like != null) {
            where.append(" AND (p.source_uuid::text ILIKE ? ")
                 .append("OR EXISTS (SELECT 1 FROM core.patient_identifiers pi ")
                 .append("WHERE pi.patient_id = p.id AND pi.identifier_value ILIKE ?))");
            args.add(like);
            args.add(like);
        }

        // Geo filter (tightest wins). Site/district join straight off the
        // existing s alias, region needs the districts table.
        String regionExtraJoin = "";
        if (siteId != null) {
            where.append(" AND s.id = ?");
            args.add(siteId);
        } else if (districtId != null) {
            where.append(" AND s.district_id = ?");
            args.add(districtId);
        } else if (regionId != null) {
            regionExtraJoin = " JOIN core.districts d ON d.id = s.district_id AND d.region_id = ?";
            args.add(regionId);
        }

        String fromAndJoins = "FROM core.patients p"
                + " JOIN core.sites s ON s.id = p.site_id"
                + regionExtraJoin;

        Long total = jdbc.queryForObject(
                "SELECT count(*) " + fromAndJoins + " " + where,
                Long.class, args.toArray());

        String sql = "SELECT p.id, p.source_uuid, p.sex, p.birth_date,"
                + "       s.code AS site_code, s.name AS site_name,"
                + "       (SELECT pi.identifier_value FROM core.patient_identifiers pi"
                + "          JOIN core.identifier_types it ON it.id = pi.identifier_type_id"
                + "          WHERE pi.patient_id = p.id AND it.code = 'CODE_ARV'"
                + "          ORDER BY pi.id LIMIT 1) AS code_arv,"
                + "       (SELECT pi.identifier_value FROM core.patient_identifiers pi"
                + "          JOIN core.identifier_types it ON it.id = pi.identifier_type_id"
                + "          WHERE pi.patient_id = p.id AND it.code = 'UPID'"
                + "          ORDER BY pi.id LIMIT 1) AS upid,"
                + "       (SELECT min(COALESCE(ti.arv_init_date, ti.enrollment_date))"
                + "          FROM core.treatment_initiations ti"
                + "          WHERE ti.patient_id = p.id AND ti.voided = FALSE) AS arv_init_date,"
                + "       (SELECT ti.arv_regimen_initial FROM core.treatment_initiations ti"
                + "          WHERE ti.patient_id = p.id AND ti.voided = FALSE"
                + "          ORDER BY COALESCE(ti.arv_init_date, ti.enrollment_date) ASC NULLS LAST"
                + "          LIMIT 1) AS arv_regimen_initial,"
                + "       (SELECT max(v.visit_date) FROM core.visits v"
                + "          WHERE v.patient_id = p.id AND v.voided = FALSE) AS last_visit_date,"
                + "       (SELECT v.arv_regimen FROM core.visits v"
                + "          WHERE v.patient_id = p.id AND v.voided = FALSE"
                + "            AND v.arv_regimen IS NOT NULL"
                + "          ORDER BY v.visit_date DESC NULLS LAST, v.id DESC"
                + "          LIMIT 1) AS last_arv_regimen"
                + " " + fromAndJoins + " " + where
                + SortSpec.orderBy(sort, dir, PATIENT_SORTABLE, "p.id DESC")
                + " LIMIT ? OFFSET ?";

        List<Object> pagedArgs = new ArrayList<>(args);
        pagedArgs.add(safeSize);
        pagedArgs.add(offset);

        List<PatientRow> rows = jdbc.query(sql, this::mapRow, pagedArgs.toArray());
        return new PatientPage(rows, total == null ? 0L : total, safePage, safeSize);
    }

    private PatientRow mapRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        java.sql.Date bd = rs.getDate("birth_date");
        java.sql.Date initDate = rs.getDate("arv_init_date");
        java.sql.Date lastVisit = rs.getDate("last_visit_date");
        return new PatientRow(
                rs.getLong("id"),
                UUID.fromString(rs.getString("source_uuid")),
                rs.getString("sex"),
                bd == null ? null : bd.toLocalDate(),
                rs.getString("site_code"),
                rs.getString("site_name"),
                rs.getString("code_arv"),
                rs.getString("upid"),
                initDate == null ? null : initDate.toLocalDate(),
                rs.getString("arv_regimen_initial"),
                lastVisit == null ? null : lastVisit.toLocalDate(),
                rs.getString("last_arv_regimen"));
    }

    public record PatientRow(
            long id,
            UUID sourceUuid,
            String sex,
            LocalDate birthDate,
            String siteCode,
            String siteName,
            String codeArv,
            String upid,
            LocalDate arvInitDate,
            String arvRegimenInitial,
            LocalDate lastVisitDate,
            String lastArvRegimen
    ) {}

    public record PatientPage(
            List<PatientRow> content,
            long total,
            int page,
            int size
    ) {}

    /**
     * True if the patient is within the supplied geo scope. Returns true when
     * no scope constraint is active (national-level user), and false on a
     * voided/missing patient.
     */
    public boolean isVisibleTo(long patientId, Long regionId, Long districtId, Long siteId) {
        if (regionId == null && districtId == null && siteId == null) return true;
        StringBuilder sql = new StringBuilder(
                "SELECT count(*) FROM core.patients p"
              + " JOIN core.sites s ON s.id = p.site_id");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (regionId != null) {
            sql.append(" JOIN core.districts d ON d.id = s.district_id AND d.region_id = ?");
            args.add(regionId);
        }
        sql.append(" WHERE p.id = ? AND p.voided = FALSE");
        args.add(patientId);
        if (siteId != null) {
            sql.append(" AND s.id = ?");
            args.add(siteId);
        }
        if (districtId != null) {
            sql.append(" AND s.district_id = ?");
            args.add(districtId);
        }
        Long n = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return n != null && n > 0;
    }

    public PatientDetail get(long id) {
        List<PatientDetail> rows = jdbc.query(
                """
                SELECT p.id, p.source_uuid, p.sex, p.birth_date,
                       p.profession, p.education_level, p.marital_status,
                       p.birth_place, p.religion,
                       s.code AS site_code, s.name AS site_name
                FROM core.patients p
                JOIN core.sites s ON s.id = p.site_id
                WHERE p.id = ? AND p.voided = FALSE
                """,
                (rs, i) -> {
                    java.sql.Date bd = rs.getDate("birth_date");
                    return new PatientDetail(
                            rs.getLong("id"),
                            UUID.fromString(rs.getString("source_uuid")),
                            rs.getString("sex"),
                            bd == null ? null : bd.toLocalDate(),
                            rs.getString("profession"),
                            rs.getString("education_level"),
                            rs.getString("marital_status"),
                            rs.getString("birth_place"),
                            rs.getString("religion"),
                            rs.getString("site_code"),
                            rs.getString("site_name"),
                            jdbc.queryForList(
                                    "SELECT identifier_value FROM core.patient_identifiers WHERE patient_id = ? ORDER BY id",
                                    String.class, rs.getLong("id")));
                },
                id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public record PatientDetail(
            long id,
            UUID sourceUuid,
            String sex,
            LocalDate birthDate,
            String profession,
            String educationLevel,
            String maritalStatus,
            String birthPlace,
            String religion,
            String siteCode,
            String siteName,
            List<String> identifiers
    ) {}

    /**
     * Patient encounters grouped by date. For each date we surface one or
     * more "blocks" — a clinical visit, an ARV initiation, a closure, and
     * a bundle of lab results — each carrying its own list of human-readable
     * observations. Ordered most-recent first.
     */
    public List<EncounterDay> encounters(long patientId) {
        // 1. visits — one block per visit, observations promoted from columns
        List<DateBlock> visits = jdbc.query(
                "SELECT visit_date, source_form,"
                        + "       weight_kg, height_cm, bmi, temperature_c,"
                        + "       pulse, respiratory_rate, bp_systolic, bp_diastolic,"
                        + "       mid_upper_arm_circumference,"
                        + "       who_stage, cdc_stage,"
                        + "       arv_regimen, arv_treatment_days, cotrim_treatment_days,"
                        + "       viral_load, viral_load_date, cd4_count, cd4_date,"
                        + "       tb_screening_result, tb_diagnosed, tb_treatment_status,"
                        + "       ctx_prescribed,"
                        + "       is_pregnant, is_breastfeeding, breastfeeding_status,"
                        + "       next_visit_date"
                        + " FROM core.visits"
                        + " WHERE patient_id = ? AND voided = FALSE AND visit_date IS NOT NULL",
                (rs, i) -> {
                    LocalDate d = rs.getDate("visit_date").toLocalDate();
                    String label = rs.getString("source_form");
                    if (label == null || label.isBlank()) label = "Visite";
                    List<Observation> obs = new ArrayList<>();
                    addNum(obs, "Poids", rs.getBigDecimal("weight_kg"), "kg");
                    addNum(obs, "Taille", rs.getBigDecimal("height_cm"), "cm");
                    addNum(obs, "IMC", rs.getBigDecimal("bmi"), "kg/m²");
                    addNum(obs, "Température", rs.getBigDecimal("temperature_c"), "°C");
                    addNum(obs, "Pouls", rs.getBigDecimal("pulse"), "bpm");
                    addNum(obs, "Fréq. resp.", rs.getBigDecimal("respiratory_rate"), "/min");
                    addBp(obs, rs.getObject("bp_systolic"), rs.getObject("bp_diastolic"));
                    addNum(obs, "Périm. brachial", rs.getBigDecimal("mid_upper_arm_circumference"), "cm");
                    addText(obs, "Stade OMS", rs.getString("who_stage"));
                    addText(obs, "Stade CDC", rs.getString("cdc_stage"));
                    addText(obs, "Régime ARV", rs.getString("arv_regimen"));
                    addInt(obs, "ARV (jours dispensés)", rs.getObject("arv_treatment_days"));
                    addInt(obs, "Cotrim (jours dispensés)", rs.getObject("cotrim_treatment_days"));
                    addNum(obs, "Charge virale", rs.getBigDecimal("viral_load"), "copies/mL");
                    addDate(obs, "Date CV", rs.getDate("viral_load_date"));
                    addNum(obs, "CD4", rs.getBigDecimal("cd4_count"), "cell/µL");
                    addDate(obs, "Date CD4", rs.getDate("cd4_date"));
                    addText(obs, "Dépistage TB", rs.getString("tb_screening_result"));
                    addBool(obs, "TB diagnostiquée", rs.getObject("tb_diagnosed"));
                    addText(obs, "Statut traitement TB", rs.getString("tb_treatment_status"));
                    addBool(obs, "Cotrim prescrit", rs.getObject("ctx_prescribed"));
                    addBool(obs, "Enceinte", rs.getObject("is_pregnant"));
                    addBool(obs, "Allaitante", rs.getObject("is_breastfeeding"));
                    addText(obs, "Allaitement", rs.getString("breastfeeding_status"));
                    addDate(obs, "Prochain RDV", rs.getDate("next_visit_date"));
                    return new DateBlock(d, "visit", label, obs);
                },
                patientId);

        // 2. ARV initiations
        List<DateBlock> inits = jdbc.query(
                "SELECT COALESCE(arv_init_date, enrollment_date) AS d,"
                        + "       enrollment_date, arv_init_date, hiv_test_date, hiv_type,"
                        + "       entry_point, arv_regimen_initial,"
                        + "       weight_initial_kg, who_stage_initial, cdc_stage_initial,"
                        + "       cd4_initial, cd4_pct_initial, karnofsky_score,"
                        + "       referred, referred_origin, treatment_motive,"
                        + "       partner_hiv_status, tb_history, arv_history,"
                        + "       transfusion_history, ptme_history, ptme_regimen_history,"
                        + "       ptme_history_date"
                        + " FROM core.treatment_initiations"
                        + " WHERE patient_id = ? AND voided = FALSE"
                        + "   AND COALESCE(arv_init_date, enrollment_date) IS NOT NULL",
                (rs, i) -> {
                    LocalDate d = rs.getDate("d").toLocalDate();
                    List<Observation> obs = new ArrayList<>();
                    addDate(obs, "Date enrôlement", rs.getDate("enrollment_date"));
                    addDate(obs, "Date initiation ARV", rs.getDate("arv_init_date"));
                    addDate(obs, "Date test VIH", rs.getDate("hiv_test_date"));
                    addText(obs, "Type VIH", rs.getString("hiv_type"));
                    addText(obs, "Porte d'entrée", rs.getString("entry_point"));
                    addText(obs, "Régime ARV initial", rs.getString("arv_regimen_initial"));
                    addNum(obs, "Poids initial", rs.getBigDecimal("weight_initial_kg"), "kg");
                    addText(obs, "Stade OMS initial", rs.getString("who_stage_initial"));
                    addText(obs, "Stade CDC initial", rs.getString("cdc_stage_initial"));
                    addNum(obs, "CD4 initial", rs.getBigDecimal("cd4_initial"), "cell/µL");
                    addNum(obs, "CD4 % initial", rs.getBigDecimal("cd4_pct_initial"), "%");
                    addInt(obs, "Karnofsky", rs.getObject("karnofsky_score"));
                    addBool(obs, "Référé", rs.getObject("referred"));
                    addText(obs, "Origine référence", rs.getString("referred_origin"));
                    addText(obs, "Motif traitement", rs.getString("treatment_motive"));
                    addText(obs, "Statut VIH partenaire", rs.getString("partner_hiv_status"));
                    addText(obs, "Antécédent TB", rs.getString("tb_history"));
                    addText(obs, "Antécédent ARV", rs.getString("arv_history"));
                    addText(obs, "Antécédent transfusion", rs.getString("transfusion_history"));
                    addText(obs, "Antécédent PTME", rs.getString("ptme_history"));
                    addText(obs, "Régime PTME", rs.getString("ptme_regimen_history"));
                    addDate(obs, "Date PTME", rs.getDate("ptme_history_date"));
                    return new DateBlock(d, "initiation", "Initiation ARV", obs);
                },
                patientId);

        // 3. Closures (file closure / transfer / death / etc.)
        List<DateBlock> closures = jdbc.query(
                "SELECT closure_date, closure_type,"
                        + "       transfer_date, transfer_destination, transfer_reason,"
                        + "       death_date, actual_death_date, death_cause_code, death_cause_text,"
                        + "       voluntary_stop_date, hiv_negative_date"
                        + " FROM core.closures"
                        + " WHERE patient_id = ? AND voided = FALSE"
                        + "   AND closure_date IS NOT NULL",
                (rs, i) -> {
                    LocalDate d = rs.getDate("closure_date").toLocalDate();
                    List<Observation> obs = new ArrayList<>();
                    addText(obs, "Type", rs.getString("closure_type"));
                    addDate(obs, "Date transfert", rs.getDate("transfer_date"));
                    addText(obs, "Destination", rs.getString("transfer_destination"));
                    addText(obs, "Motif transfert", rs.getString("transfer_reason"));
                    addDate(obs, "Date décès", rs.getDate("death_date"));
                    addDate(obs, "Date réelle décès", rs.getDate("actual_death_date"));
                    addText(obs, "Cause décès (code)", rs.getString("death_cause_code"));
                    addText(obs, "Cause décès", rs.getString("death_cause_text"));
                    addDate(obs, "Date arrêt volontaire", rs.getDate("voluntary_stop_date"));
                    addDate(obs, "Date séronégativation", rs.getDate("hiv_negative_date"));
                    return new DateBlock(d, "closure", "Clôture", obs);
                },
                patientId);

        // 4. Lab results bundled per day
        List<Observation> labRows = jdbc.query(
                "SELECT exam_date, test_name, value_numeric, value_text, unit"
                        + " FROM core.lab_results"
                        + " WHERE patient_id = ? AND voided = FALSE AND exam_date IS NOT NULL"
                        + " ORDER BY exam_date DESC, test_name",
                (rs, i) -> {
                    String label = rs.getString("test_name");
                    String value;
                    java.math.BigDecimal n = rs.getBigDecimal("value_numeric");
                    String txt = rs.getString("value_text");
                    String unit = rs.getString("unit");
                    if (n != null) {
                        value = unit == null ? n.toPlainString() : n.toPlainString() + " " + unit;
                    } else {
                        value = txt == null ? "—" : txt;
                    }
                    Observation o = new Observation(label, value);
                    // smuggle the date in the label prefix so we can group below
                    return new Observation(rs.getDate("exam_date").toLocalDate().toString() + "|" + o.label(), o.value());
                },
                patientId);

        java.util.Map<LocalDate, List<Observation>> byDate = new java.util.TreeMap<>();
        for (Observation o : labRows) {
            int sep = o.label().indexOf('|');
            LocalDate d = LocalDate.parse(o.label().substring(0, sep));
            byDate.computeIfAbsent(d, k -> new ArrayList<>())
                    .add(new Observation(o.label().substring(sep + 1), o.value()));
        }
        List<DateBlock> labs = new ArrayList<>();
        for (var e : byDate.entrySet()) {
            labs.add(new DateBlock(e.getKey(), "lab", "Examens biologiques", e.getValue()));
        }

        // Group everything by date
        java.util.Map<LocalDate, List<DateBlock>> grouped = new java.util.TreeMap<>(java.util.Comparator.reverseOrder());
        for (DateBlock b : visits)   grouped.computeIfAbsent(b.date(), k -> new ArrayList<>()).add(b);
        for (DateBlock b : inits)    grouped.computeIfAbsent(b.date(), k -> new ArrayList<>()).add(b);
        for (DateBlock b : closures) grouped.computeIfAbsent(b.date(), k -> new ArrayList<>()).add(b);
        for (DateBlock b : labs)     grouped.computeIfAbsent(b.date(), k -> new ArrayList<>()).add(b);

        List<EncounterDay> out = new ArrayList<>(grouped.size());
        for (var e : grouped.entrySet()) out.add(new EncounterDay(e.getKey(), e.getValue()));
        return out;
    }

    private static void addText(List<Observation> obs, String label, String value) {
        if (value != null && !value.isBlank()) obs.add(new Observation(label, value));
    }

    private static void addNum(List<Observation> obs, String label, java.math.BigDecimal v, String unit) {
        if (v == null) return;
        obs.add(new Observation(label, unit == null ? v.toPlainString() : v.toPlainString() + " " + unit));
    }

    private static void addInt(List<Observation> obs, String label, Object v) {
        if (v == null) return;
        obs.add(new Observation(label, v.toString()));
    }

    private static void addDate(List<Observation> obs, String label, java.sql.Date d) {
        if (d == null) return;
        obs.add(new Observation(label, d.toLocalDate().toString()));
    }

    private static void addBool(List<Observation> obs, String label, Object v) {
        if (v == null) return;
        if (v instanceof Boolean b) obs.add(new Observation(label, b ? "Oui" : "Non"));
    }

    private static void addBp(List<Observation> obs, Object sys, Object dia) {
        if (sys == null && dia == null) return;
        obs.add(new Observation("Tension artérielle", (sys == null ? "?" : sys) + " / " + (dia == null ? "?" : dia)));
    }

    public record EncounterDay(
            LocalDate date,
            List<DateBlock> blocks
    ) {}

    public record DateBlock(
            LocalDate date,
            String kind,    // visit | initiation | closure | lab
            String label,
            List<Observation> observations
    ) {}

    public record Observation(String label, String value) {}
}
