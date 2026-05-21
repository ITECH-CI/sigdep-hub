package ci.itechciv.sigdep.hub.ingestion.writer;

import ci.itechciv.sigdep.contracts.SyncBatchResponse.RecordError;
import ci.itechciv.sigdep.contracts.dto.PtmeChildDto;
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

/** Writes PTME child + child follow-up rows to core.ptme_children. */
@Repository
public class PtmeChildWriter {

    private static final Logger log = LoggerFactory.getLogger(PtmeChildWriter.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public PtmeChildWriter(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.json = json;
        this.tx = new TransactionTemplate(txManager);
    }

    public BatchResult upsertBatch(long siteId, List<PtmeChildDto> batch) {
        int accepted = 0;
        int rejected = 0;
        List<RecordError> errors = new ArrayList<>();

        for (PtmeChildDto c : batch) {
            try {
                tx.executeWithoutResult(s -> upsertOne(siteId, c));
                accepted++;
            } catch (RuntimeException e) {
                rejected++;
                errors.add(new RecordError(c.sourceUuid(), "UPSERT_FAILED",
                        e.getClass().getSimpleName() + ": " + e.getMessage()));
                log.warn("Failed to upsert ptme child {}: {}", c.sourceUuid(), e.getMessage());
            }
        }
        return new BatchResult(accepted, rejected, errors);
    }

    private void upsertOne(long siteId, PtmeChildDto c) {
        String extra = c.extraData() == null ? null : writeJson(c.extraData());
        jdbc.update(
                """
                INSERT INTO core.ptme_children (
                  site_id, source_uuid, mother_source_uuid,
                  child_followup_number, birth_date, gender,
                  arv_prophylaxis_given, arv_prophylaxis_given_date, followup_end_date,
                  pcr1_sampling_date, age_in_week_on_pcr1, age_in_month_on_pcr1, pcr1_result,
                  pcr2_sampling_date, age_in_week_on_pcr2, age_in_month_on_pcr2, pcr2_result,
                  pcr3_sampling_date, age_in_week_on_pcr3, age_in_month_on_pcr3, pcr3_result,
                  ctx_initiation_date, age_in_week_on_ctx_initiation, age_in_month_on_ctx_initiation,
                  inh_initiation_date, age_in_week_on_inh_initiation, age_in_month_on_inh_initiation,
                  hiv_serology1_date, age_in_week_on_serology1, age_in_month_on_serology1, hiv_serology1_result,
                  hiv_serology2_date, age_in_week_on_serology2, age_in_month_on_serology2, hiv_serology2_result,
                  followup_result, followup_result_date, reference_location,
                  extra_data, voided, created_at, updated_at
                ) VALUES (
                  ?, ?, ?,
                  ?, ?, ?,
                  ?, ?, ?,
                  ?, ?, ?, ?,
                  ?, ?, ?, ?,
                  ?, ?, ?, ?,
                  ?, ?, ?,
                  ?, ?, ?,
                  ?, ?, ?, ?,
                  ?, ?, ?, ?,
                  ?, ?, ?,
                  ?::jsonb, ?, NOW(), NOW()
                )
                ON CONFLICT (site_id, source_uuid) DO UPDATE SET
                  mother_source_uuid             = EXCLUDED.mother_source_uuid,
                  child_followup_number          = EXCLUDED.child_followup_number,
                  birth_date                     = EXCLUDED.birth_date,
                  gender                         = EXCLUDED.gender,
                  arv_prophylaxis_given          = EXCLUDED.arv_prophylaxis_given,
                  arv_prophylaxis_given_date     = EXCLUDED.arv_prophylaxis_given_date,
                  followup_end_date              = EXCLUDED.followup_end_date,
                  pcr1_sampling_date             = EXCLUDED.pcr1_sampling_date,
                  age_in_week_on_pcr1            = EXCLUDED.age_in_week_on_pcr1,
                  age_in_month_on_pcr1           = EXCLUDED.age_in_month_on_pcr1,
                  pcr1_result                    = EXCLUDED.pcr1_result,
                  pcr2_sampling_date             = EXCLUDED.pcr2_sampling_date,
                  age_in_week_on_pcr2            = EXCLUDED.age_in_week_on_pcr2,
                  age_in_month_on_pcr2           = EXCLUDED.age_in_month_on_pcr2,
                  pcr2_result                    = EXCLUDED.pcr2_result,
                  pcr3_sampling_date             = EXCLUDED.pcr3_sampling_date,
                  age_in_week_on_pcr3            = EXCLUDED.age_in_week_on_pcr3,
                  age_in_month_on_pcr3           = EXCLUDED.age_in_month_on_pcr3,
                  pcr3_result                    = EXCLUDED.pcr3_result,
                  ctx_initiation_date            = EXCLUDED.ctx_initiation_date,
                  age_in_week_on_ctx_initiation  = EXCLUDED.age_in_week_on_ctx_initiation,
                  age_in_month_on_ctx_initiation = EXCLUDED.age_in_month_on_ctx_initiation,
                  inh_initiation_date            = EXCLUDED.inh_initiation_date,
                  age_in_week_on_inh_initiation  = EXCLUDED.age_in_week_on_inh_initiation,
                  age_in_month_on_inh_initiation = EXCLUDED.age_in_month_on_inh_initiation,
                  hiv_serology1_date             = EXCLUDED.hiv_serology1_date,
                  age_in_week_on_serology1       = EXCLUDED.age_in_week_on_serology1,
                  age_in_month_on_serology1      = EXCLUDED.age_in_month_on_serology1,
                  hiv_serology1_result           = EXCLUDED.hiv_serology1_result,
                  hiv_serology2_date             = EXCLUDED.hiv_serology2_date,
                  age_in_week_on_serology2       = EXCLUDED.age_in_week_on_serology2,
                  age_in_month_on_serology2      = EXCLUDED.age_in_month_on_serology2,
                  hiv_serology2_result           = EXCLUDED.hiv_serology2_result,
                  followup_result                = EXCLUDED.followup_result,
                  followup_result_date           = EXCLUDED.followup_result_date,
                  reference_location             = EXCLUDED.reference_location,
                  extra_data                     = EXCLUDED.extra_data,
                  voided                         = EXCLUDED.voided,
                  updated_at                     = NOW()
                """,
                siteId, c.sourceUuid(),
                c.motherSourceUuid() == null ? null : c.motherSourceUuid().toString(),
                c.childFollowupNumber(),
                c.birthDate() == null ? null : Date.valueOf(c.birthDate()),
                c.gender(),
                c.arvProphylaxisGiven(),
                c.arvProphylaxisGivenDate() == null ? null : Date.valueOf(c.arvProphylaxisGivenDate()),
                c.followupEndDate() == null ? null : Date.valueOf(c.followupEndDate()),
                c.pcr1SamplingDate() == null ? null : Date.valueOf(c.pcr1SamplingDate()),
                c.ageInWeekOnPcr1(), c.ageInMonthOnPcr1(), c.pcr1Result(),
                c.pcr2SamplingDate() == null ? null : Date.valueOf(c.pcr2SamplingDate()),
                c.ageInWeekOnPcr2(), c.ageInMonthOnPcr2(), c.pcr2Result(),
                c.pcr3SamplingDate() == null ? null : Date.valueOf(c.pcr3SamplingDate()),
                c.ageInWeekOnPcr3(), c.ageInMonthOnPcr3(), c.pcr3Result(),
                c.ctxInitiationDate() == null ? null : Date.valueOf(c.ctxInitiationDate()),
                c.ageInWeekOnCtxInitiation(), c.ageInMonthOnCtxInitiation(),
                c.inhInitiationDate() == null ? null : Date.valueOf(c.inhInitiationDate()),
                c.ageInWeekOnInhInitiation(), c.ageInMonthOnInhInitiation(),
                c.hivSerology1Date() == null ? null : Date.valueOf(c.hivSerology1Date()),
                c.ageInWeekOnSerology1(), c.ageInMonthOnSerology1(), c.hivSerology1Result(),
                c.hivSerology2Date() == null ? null : Date.valueOf(c.hivSerology2Date()),
                c.ageInWeekOnSerology2(), c.ageInMonthOnSerology2(), c.hivSerology2Result(),
                c.followupResult(),
                c.followupResultDate() == null ? null : Date.valueOf(c.followupResultDate()),
                c.referenceLocation(),
                extra, Boolean.TRUE.equals(c.voided()));
    }

    private String writeJson(Object o) {
        try { return json.writeValueAsString(o); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot serialize extra_data", e); }
    }

    public record BatchResult(int accepted, int rejected, List<RecordError> errors) {}
}
