package ci.itechciv.sigdep.hub.ingestion.writer;

import ci.itechciv.sigdep.contracts.SyncBatchResponse.RecordError;
import ci.itechciv.sigdep.contracts.dto.PtmeChildVisitDto;
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

/** Writes PTME child-follow-up visits to core.ptme_child_visits. */
@Repository
public class PtmeChildVisitWriter {

    private static final Logger log = LoggerFactory.getLogger(PtmeChildVisitWriter.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public PtmeChildVisitWriter(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.json = json;
        this.tx = new TransactionTemplate(txManager);
    }

    public BatchResult upsertBatch(long siteId, List<PtmeChildVisitDto> batch) {
        int accepted = 0;
        int rejected = 0;
        List<RecordError> errors = new ArrayList<>();

        for (PtmeChildVisitDto v : batch) {
            try {
                tx.executeWithoutResult(s -> upsertOne(siteId, v));
                accepted++;
            } catch (RuntimeException e) {
                rejected++;
                errors.add(new RecordError(v.sourceUuid(), "UPSERT_FAILED",
                        e.getClass().getSimpleName() + ": " + e.getMessage()));
                log.warn("Failed to upsert ptme child visit {}: {}", v.sourceUuid(), e.getMessage());
            }
        }
        return new BatchResult(accepted, rejected, errors);
    }

    private void upsertOne(long siteId, PtmeChildVisitDto v) {
        String extra = v.extraData() == null ? null : writeJson(v.extraData());
        jdbc.update(
                """
                INSERT INTO core.ptme_child_visits (
                  site_id, source_uuid, child_source_uuid,
                  visit_date, age_in_day, age_in_week, age_in_month,
                  eating_type, modern_contraceptive_method,
                  continuing_ctx, continuing_inh,
                  extra_data, voided, created_at, updated_at
                ) VALUES (
                  ?, ?, ?,
                  ?, ?, ?, ?,
                  ?, ?,
                  ?, ?,
                  ?::jsonb, ?, NOW(), NOW()
                )
                ON CONFLICT (site_id, source_uuid) DO UPDATE SET
                  child_source_uuid           = EXCLUDED.child_source_uuid,
                  visit_date                  = EXCLUDED.visit_date,
                  age_in_day                  = EXCLUDED.age_in_day,
                  age_in_week                 = EXCLUDED.age_in_week,
                  age_in_month                = EXCLUDED.age_in_month,
                  eating_type                 = EXCLUDED.eating_type,
                  modern_contraceptive_method = EXCLUDED.modern_contraceptive_method,
                  continuing_ctx              = EXCLUDED.continuing_ctx,
                  continuing_inh              = EXCLUDED.continuing_inh,
                  extra_data                  = EXCLUDED.extra_data,
                  voided                      = EXCLUDED.voided,
                  updated_at                  = NOW()
                """,
                siteId, v.sourceUuid(),
                v.childSourceUuid() == null ? null : v.childSourceUuid().toString(),
                v.visitDate() == null ? null : Date.valueOf(v.visitDate()),
                v.ageInDay(), v.ageInWeek(), v.ageInMonth(),
                v.eatingType(), v.modernContraceptiveMethod(),
                v.continuingCtx(), v.continuingInh(),
                extra, Boolean.TRUE.equals(v.voided()));
    }

    private String writeJson(Object o) {
        try { return json.writeValueAsString(o); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot serialize extra_data", e); }
    }

    public record BatchResult(int accepted, int rejected, List<RecordError> errors) {}
}
