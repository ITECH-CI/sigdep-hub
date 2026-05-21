package ci.itechciv.sigdep.hub.ingestion.writer;

import ci.itechciv.sigdep.contracts.SyncBatchResponse;
import ci.itechciv.sigdep.contracts.SyncBatchResponse.RecordError;
import ci.itechciv.sigdep.contracts.dto.ScreeningDto;
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
 * Writes an HIV screening row to core.screenings. Unlike the other
 * writers, a screening carries no patient_id — it is anonymous from
 * SIGDEP's point of view (the upstream openmrs "hivscreening" module
 * stores demographics inline). Each record is upserted in its own short
 * transaction so a single bad row doesn't poison the batch.
 */
@Repository
public class ScreeningWriter {

    private static final Logger log = LoggerFactory.getLogger(ScreeningWriter.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public ScreeningWriter(JdbcTemplate jdbc,
                           ObjectMapper json,
                           PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.json = json;
        this.tx = new TransactionTemplate(txManager);
    }

    public BatchResult upsertBatch(long siteId, List<ScreeningDto> batch) {
        int accepted = 0;
        int rejected = 0;
        List<RecordError> errors = new ArrayList<>();

        for (ScreeningDto s : batch) {
            try {
                tx.executeWithoutResult(status -> upsertOne(siteId, s));
                accepted++;
            } catch (RuntimeException e) {
                rejected++;
                errors.add(new RecordError(s.sourceUuid(), "UPSERT_FAILED",
                        e.getClass().getSimpleName() + ": " + e.getMessage()));
                log.warn("Failed to upsert screening {}: {}", s.sourceUuid(), e.getMessage());
            }
        }
        return new BatchResult(accepted, rejected, errors);
    }

    private void upsertOne(long siteId, ScreeningDto s) {
        String extraJson = s.extraData() == null ? null : writeJson(s.extraData());

        jdbc.update(
                """
                INSERT INTO core.screenings (
                  site_id, source_uuid,
                  screening_code, screening_date, result_announcing_date,
                  gender, age, profession, residence,
                  marital_status, other_marital_status,
                  population_type, screening_reason, other_screening_reason,
                  test1_reaction, test2_reaction, test3_reaction,
                  test1_invalidated, test2_invalidated, test3_invalidated,
                  final_result, retesting, comment,
                  screening_site_type, screening_post,
                  extra_data, voided, created_at, updated_at
                ) VALUES (
                  ?, ?,
                  ?, ?, ?,
                  ?, ?, ?, ?,
                  ?, ?,
                  ?, ?, ?,
                  ?, ?, ?,
                  ?, ?, ?,
                  ?, ?, ?,
                  ?, ?,
                  ?::jsonb, ?, NOW(), NOW()
                )
                ON CONFLICT (site_id, source_uuid) DO UPDATE SET
                  screening_code         = EXCLUDED.screening_code,
                  screening_date         = EXCLUDED.screening_date,
                  result_announcing_date = EXCLUDED.result_announcing_date,
                  gender                 = EXCLUDED.gender,
                  age                    = EXCLUDED.age,
                  profession             = EXCLUDED.profession,
                  residence              = EXCLUDED.residence,
                  marital_status         = EXCLUDED.marital_status,
                  other_marital_status   = EXCLUDED.other_marital_status,
                  population_type        = EXCLUDED.population_type,
                  screening_reason       = EXCLUDED.screening_reason,
                  other_screening_reason = EXCLUDED.other_screening_reason,
                  test1_reaction         = EXCLUDED.test1_reaction,
                  test2_reaction         = EXCLUDED.test2_reaction,
                  test3_reaction         = EXCLUDED.test3_reaction,
                  test1_invalidated      = EXCLUDED.test1_invalidated,
                  test2_invalidated      = EXCLUDED.test2_invalidated,
                  test3_invalidated      = EXCLUDED.test3_invalidated,
                  final_result           = EXCLUDED.final_result,
                  retesting              = EXCLUDED.retesting,
                  comment                = EXCLUDED.comment,
                  screening_site_type    = EXCLUDED.screening_site_type,
                  screening_post         = EXCLUDED.screening_post,
                  extra_data             = EXCLUDED.extra_data,
                  voided                 = EXCLUDED.voided,
                  updated_at             = NOW()
                """,
                siteId, s.sourceUuid(),
                s.screeningCode(),
                s.screeningDate() == null ? null : Date.valueOf(s.screeningDate()),
                s.resultAnnouncingDate() == null ? null : Date.valueOf(s.resultAnnouncingDate()),
                s.gender(), s.age(), s.profession(), s.residence(),
                s.maritalStatus(), s.otherMaritalStatus(),
                s.populationType(), s.screeningReason(), s.otherScreeningReason(),
                s.test1Reaction(), s.test2Reaction(), s.test3Reaction(),
                Boolean.TRUE.equals(s.test1Invalidated()),
                Boolean.TRUE.equals(s.test2Invalidated()),
                Boolean.TRUE.equals(s.test3Invalidated()),
                s.finalResult(), Boolean.TRUE.equals(s.retesting()), s.comment(),
                s.screeningSiteType(), s.screeningPost(),
                extraJson,
                Boolean.TRUE.equals(s.voided()));
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
