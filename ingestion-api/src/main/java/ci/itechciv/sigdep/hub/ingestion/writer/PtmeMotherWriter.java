package ci.itechciv.sigdep.hub.ingestion.writer;

import ci.itechciv.sigdep.contracts.SyncBatchResponse.RecordError;
import ci.itechciv.sigdep.contracts.dto.PtmeMotherDto;
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

/** Writes PTME mother + follow-up rows to core.ptme_mothers. */
@Repository
public class PtmeMotherWriter {

    private static final Logger log = LoggerFactory.getLogger(PtmeMotherWriter.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public PtmeMotherWriter(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.json = json;
        this.tx = new TransactionTemplate(txManager);
    }

    public BatchResult upsertBatch(long siteId, List<PtmeMotherDto> batch) {
        int accepted = 0;
        int rejected = 0;
        List<RecordError> errors = new ArrayList<>();

        for (PtmeMotherDto m : batch) {
            try {
                tx.executeWithoutResult(s -> upsertOne(siteId, m));
                accepted++;
            } catch (RuntimeException e) {
                rejected++;
                errors.add(new RecordError(m.sourceUuid(), "UPSERT_FAILED",
                        e.getClass().getSimpleName() + ": " + e.getMessage()));
                log.warn("Failed to upsert ptme mother {}: {}", m.sourceUuid(), e.getMessage());
            }
        }
        return new BatchResult(accepted, rejected, errors);
    }

    private void upsertOne(long siteId, PtmeMotherDto m) {
        String extra = m.extraData() == null ? null : writeJson(m.extraData());
        jdbc.update(
                """
                INSERT INTO core.ptme_mothers (
                  site_id, source_uuid,
                  pregnant_number, hiv_care_number, screening_number, age,
                  marital_status, spousal_screening, spousal_screening_result,
                  start_date, end_date, arv_status_at_registering,
                  estimated_delivery_date, pregnancy_outcome,
                  spousal_screening_date, delivery_type,
                  extra_data, voided, created_at, updated_at
                ) VALUES (
                  ?, ?,
                  ?, ?, ?, ?,
                  ?, ?, ?,
                  ?, ?, ?,
                  ?, ?,
                  ?, ?,
                  ?::jsonb, ?, NOW(), NOW()
                )
                ON CONFLICT (site_id, source_uuid) DO UPDATE SET
                  pregnant_number           = EXCLUDED.pregnant_number,
                  hiv_care_number           = EXCLUDED.hiv_care_number,
                  screening_number          = EXCLUDED.screening_number,
                  age                       = EXCLUDED.age,
                  marital_status            = EXCLUDED.marital_status,
                  spousal_screening         = EXCLUDED.spousal_screening,
                  spousal_screening_result  = EXCLUDED.spousal_screening_result,
                  start_date                = EXCLUDED.start_date,
                  end_date                  = EXCLUDED.end_date,
                  arv_status_at_registering = EXCLUDED.arv_status_at_registering,
                  estimated_delivery_date   = EXCLUDED.estimated_delivery_date,
                  pregnancy_outcome         = EXCLUDED.pregnancy_outcome,
                  spousal_screening_date    = EXCLUDED.spousal_screening_date,
                  delivery_type             = EXCLUDED.delivery_type,
                  extra_data                = EXCLUDED.extra_data,
                  voided                    = EXCLUDED.voided,
                  updated_at                = NOW()
                """,
                siteId, m.sourceUuid(),
                m.pregnantNumber(), m.hivCareNumber(), m.screeningNumber(), m.age(),
                m.maritalStatus(), m.spousalScreening(), m.spousalScreeningResult(),
                m.startDate() == null ? null : Date.valueOf(m.startDate()),
                m.endDate() == null ? null : Date.valueOf(m.endDate()),
                m.arvStatusAtRegistering(),
                m.estimatedDeliveryDate() == null ? null : Date.valueOf(m.estimatedDeliveryDate()),
                m.pregnancyOutcome(),
                m.spousalScreeningDate() == null ? null : Date.valueOf(m.spousalScreeningDate()),
                m.deliveryType(),
                extra, Boolean.TRUE.equals(m.voided()));
    }

    private String writeJson(Object o) {
        try { return json.writeValueAsString(o); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot serialize extra_data", e); }
    }

    public record BatchResult(int accepted, int rejected, List<RecordError> errors) {}
}
