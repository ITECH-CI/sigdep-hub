package ci.itechciv.sigdep.hub.ingestion.writer;

import ci.itechciv.sigdep.contracts.SyncBatchResponse;
import ci.itechciv.sigdep.contracts.SyncBatchResponse.RecordError;
import ci.itechciv.sigdep.contracts.dto.PediatricInitiationDto;
import ci.itechciv.sigdep.contracts.dto.TreatmentInitiationDto;
import ci.itechciv.sigdep.hub.domain.service.PatientResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Date;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes a treatment initiation row, plus propagates four profile fields
 * (marital_status, birth_place, education_level, religion) to core.patients.
 * Each record is upserted in its own short transaction so a single bad row
 * doesn't poison the batch.
 */
@Repository
public class InitiationWriter {

    private static final Logger log = LoggerFactory.getLogger(InitiationWriter.class);

    private final JdbcTemplate jdbc;
    private final PatientResolver patientResolver;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public InitiationWriter(JdbcTemplate jdbc,
                            PatientResolver patientResolver,
                            ObjectMapper json,
                            PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.patientResolver = patientResolver;
        this.json = json;
        this.tx = new TransactionTemplate(txManager);
    }

    public BatchResult upsertBatch(long siteId, List<TreatmentInitiationDto> batch) {
        int accepted = 0;
        int rejected = 0;
        List<RecordError> errors = new ArrayList<>();

        for (TreatmentInitiationDto t : batch) {
            try {
                Long patientId = patientResolver.resolveId(siteId, t.patientSourceUuid());
                if (patientId == null) {
                    rejected++;
                    errors.add(new RecordError(t.sourceUuid(), "UNKNOWN_PATIENT",
                            "Patient " + t.patientSourceUuid() + " not yet ingested"));
                    continue;
                }
                final long pid = patientId;
                tx.executeWithoutResult(status -> {
                    long initiationId = upsertOne(siteId, pid, t);
                    propagateProfile(pid, t);
                    if (t.pediatric() != null) {
                        upsertPediatric(initiationId, t.pediatric());
                    }
                });
                accepted++;
            } catch (RuntimeException e) {
                rejected++;
                errors.add(new RecordError(t.sourceUuid(), "UPSERT_FAILED",
                        e.getClass().getSimpleName() + ": " + e.getMessage()));
                log.warn("Failed to upsert initiation {}: {}", t.sourceUuid(), e.getMessage());
            }
        }
        return new BatchResult(accepted, rejected, errors);
    }

    private long upsertOne(long siteId, long patientId, TreatmentInitiationDto t) {
        String extraJson = t.extraData() == null ? null : writeJson(t.extraData());

        return jdbc.queryForObject(
                """
                INSERT INTO core.treatment_initiations (
                  patient_id, site_id, source_uuid,
                  enrollment_date, arv_init_date, hiv_test_date,
                  hiv_type, entry_point,
                  who_stage_initial, cdc_stage_initial, arv_regimen_initial,
                  weight_initial_kg, cd4_initial, cd4_pct_initial, karnofsky_score,
                  referred, referred_origin, treatment_motive,
                  partner_hiv_status,
                  tb_history, arv_history, transfusion_history,
                  ptme_history, ptme_regimen_history, ptme_history_date,
                  extra_data, voided, created_at, updated_at
                ) VALUES (
                  ?, ?, ?,
                  ?, ?, ?,
                  ?, ?,
                  ?, ?, ?,
                  ?, ?, ?, ?,
                  ?, ?, ?,
                  ?,
                  ?, ?, ?,
                  ?, ?, ?,
                  ?::jsonb, ?, NOW(), NOW()
                )
                ON CONFLICT (site_id, source_uuid) DO UPDATE SET
                  enrollment_date      = EXCLUDED.enrollment_date,
                  arv_init_date        = EXCLUDED.arv_init_date,
                  hiv_test_date        = EXCLUDED.hiv_test_date,
                  hiv_type             = EXCLUDED.hiv_type,
                  entry_point          = EXCLUDED.entry_point,
                  who_stage_initial    = EXCLUDED.who_stage_initial,
                  cdc_stage_initial    = EXCLUDED.cdc_stage_initial,
                  arv_regimen_initial  = EXCLUDED.arv_regimen_initial,
                  weight_initial_kg    = EXCLUDED.weight_initial_kg,
                  cd4_initial          = EXCLUDED.cd4_initial,
                  cd4_pct_initial      = EXCLUDED.cd4_pct_initial,
                  karnofsky_score      = EXCLUDED.karnofsky_score,
                  referred             = EXCLUDED.referred,
                  referred_origin      = EXCLUDED.referred_origin,
                  treatment_motive     = EXCLUDED.treatment_motive,
                  partner_hiv_status   = EXCLUDED.partner_hiv_status,
                  tb_history           = EXCLUDED.tb_history,
                  arv_history          = EXCLUDED.arv_history,
                  transfusion_history  = EXCLUDED.transfusion_history,
                  ptme_history         = EXCLUDED.ptme_history,
                  ptme_regimen_history = EXCLUDED.ptme_regimen_history,
                  ptme_history_date    = EXCLUDED.ptme_history_date,
                  extra_data           = EXCLUDED.extra_data,
                  voided               = EXCLUDED.voided,
                  updated_at           = NOW()
                RETURNING id
                """,
                Long.class,
                patientId, siteId, t.sourceUuid(),
                t.enrollmentDate() == null ? null : Date.valueOf(t.enrollmentDate()),
                t.arvInitDate()    == null ? null : Date.valueOf(t.arvInitDate()),
                t.hivTestDate()    == null ? null : Date.valueOf(t.hivTestDate()),
                t.hivType(), t.entryPoint(),
                t.whoStageInitial(), t.cdcStageInitial(), t.arvRegimenInitial(),
                t.weightInitialKg(), t.cd4Initial(), t.cd4PctInitial(), t.karnofskyScore(),
                t.referred(), t.referredOrigin(), t.treatmentMotive(),
                t.partnerHivStatus(),
                t.tbHistory(), t.arvHistory(), t.transfusionHistory(),
                t.ptmeHistory(), t.ptmeRegimenHistory(),
                t.ptmeHistoryDate() == null ? null : Date.valueOf(t.ptmeHistoryDate()),
                extraJson,
                Boolean.TRUE.equals(t.voided()));
    }

    /**
     * Upsert the pediatric extension. INSERT...ON CONFLICT (initiation_id)
     * keeps the table 1-1 with the parent and idempotent on replay.
     */
    private void upsertPediatric(long initiationId, PediatricInitiationDto p) {
        jdbc.update(
                """
                INSERT INTO core.treatment_initiations_pediatric (
                  initiation_id,
                  birth_weight_kg, birth_length_cm, head_circumference_cm,
                  apgar_score, delivery_mode, delivered_at_facility,
                  mother_received_ptme, mother_hiv_status, mother_vital_status,
                  mother_ptme_regimen, infant_arv_prophylaxis_given, infant_arv_protocol,
                  feeding_mode, weaning_date, vaccinations,
                  father_vital_status, father_education_level, father_activity_type,
                  mother_education_level, mother_activity_type,
                  guardian_vital_status, guardian_education_level, guardian_activity_type,
                  guardian_hiv_status,
                  admission_date, schooling_status, screening_code,
                  created_at, updated_at
                ) VALUES (
                  ?,
                  ?, ?, ?,
                  ?, ?, ?,
                  ?, ?, ?,
                  ?, ?, ?,
                  ?, ?, ?,
                  ?, ?, ?,
                  ?, ?,
                  ?, ?, ?,
                  ?,
                  ?, ?, ?,
                  NOW(), NOW()
                )
                ON CONFLICT (initiation_id) DO UPDATE SET
                  birth_weight_kg              = EXCLUDED.birth_weight_kg,
                  birth_length_cm              = EXCLUDED.birth_length_cm,
                  head_circumference_cm        = EXCLUDED.head_circumference_cm,
                  apgar_score                  = EXCLUDED.apgar_score,
                  delivery_mode                = EXCLUDED.delivery_mode,
                  delivered_at_facility        = EXCLUDED.delivered_at_facility,
                  mother_received_ptme         = EXCLUDED.mother_received_ptme,
                  mother_hiv_status            = EXCLUDED.mother_hiv_status,
                  mother_vital_status          = EXCLUDED.mother_vital_status,
                  mother_ptme_regimen          = EXCLUDED.mother_ptme_regimen,
                  infant_arv_prophylaxis_given = EXCLUDED.infant_arv_prophylaxis_given,
                  infant_arv_protocol          = EXCLUDED.infant_arv_protocol,
                  feeding_mode                 = EXCLUDED.feeding_mode,
                  weaning_date                 = EXCLUDED.weaning_date,
                  vaccinations                 = EXCLUDED.vaccinations,
                  father_vital_status          = EXCLUDED.father_vital_status,
                  father_education_level       = EXCLUDED.father_education_level,
                  father_activity_type         = EXCLUDED.father_activity_type,
                  mother_education_level       = EXCLUDED.mother_education_level,
                  mother_activity_type         = EXCLUDED.mother_activity_type,
                  guardian_vital_status        = EXCLUDED.guardian_vital_status,
                  guardian_education_level     = EXCLUDED.guardian_education_level,
                  guardian_activity_type       = EXCLUDED.guardian_activity_type,
                  guardian_hiv_status          = EXCLUDED.guardian_hiv_status,
                  admission_date               = EXCLUDED.admission_date,
                  schooling_status             = EXCLUDED.schooling_status,
                  screening_code               = EXCLUDED.screening_code,
                  updated_at                   = NOW()
                """,
                initiationId,
                p.birthWeightKg(), p.birthLengthCm(), p.headCircumferenceCm(),
                p.apgarScore(), p.deliveryMode(), p.deliveredAtFacility(),
                p.motherReceivedPtme(), p.motherHivStatus(), p.motherVitalStatus(),
                p.motherPtmeRegimen(), p.infantArvProphylaxisGiven(), p.infantArvProtocol(),
                p.feedingMode(),
                p.weaningDate() == null ? null : Date.valueOf(p.weaningDate()),
                p.vaccinations(),
                p.fatherVitalStatus(), p.fatherEducationLevel(), p.fatherActivityType(),
                p.motherEducationLevel(), p.motherActivityType(),
                p.guardianVitalStatus(), p.guardianEducationLevel(), p.guardianActivityType(),
                p.guardianHivStatus(),
                p.admissionDate() == null ? null : Date.valueOf(p.admissionDate()),
                p.schoolingStatus(), p.screeningCode());
    }

    /**
     * Propagate the five profile fields (marital_status, birth_place,
     * education_level, religion, profession) from the enrolment form to
     * core.patients. Only updates columns where the form provided a
     * non-null value, so we never overwrite an existing value with a
     * missing one. updated_at gets bumped only if at least one column
     * actually changes.
     */
    private void propagateProfile(long patientId, TreatmentInitiationDto t) {
        if (t.maritalStatus() == null && t.birthPlace() == null
                && t.educationLevel() == null && t.religion() == null
                && t.profession() == null) {
            return;
        }
        jdbc.update(
                """
                UPDATE core.patients SET
                  marital_status  = COALESCE(?, marital_status),
                  birth_place     = COALESCE(?, birth_place),
                  education_level = COALESCE(?, education_level),
                  religion        = COALESCE(?, religion),
                  profession      = COALESCE(?, profession),
                  updated_at      = NOW()
                WHERE id = ?
                """,
                t.maritalStatus(), t.birthPlace(), t.educationLevel(),
                t.religion(), t.profession(),
                patientId);
    }

    private String writeJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize extra_data", e);
        }
    }

    public record BatchResult(int accepted, int rejected, List<SyncBatchResponse.RecordError> errors) {}
}
