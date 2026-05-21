package ci.itechciv.sigdep.hub.ingestion.writer;

import ci.itechciv.sigdep.contracts.SyncBatchResponse.RecordError;
import ci.itechciv.sigdep.contracts.dto.PtmeMotherVisitDto;
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

/** Writes PTME mother-follow-up visits to core.ptme_mother_visits. */
@Repository
public class PtmeMotherVisitWriter {

    private static final Logger log = LoggerFactory.getLogger(PtmeMotherVisitWriter.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public PtmeMotherVisitWriter(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.json = json;
        this.tx = new TransactionTemplate(txManager);
    }

    public BatchResult upsertBatch(long siteId, List<PtmeMotherVisitDto> batch) {
        int accepted = 0;
        int rejected = 0;
        List<RecordError> errors = new ArrayList<>();

        for (PtmeMotherVisitDto v : batch) {
            try {
                tx.executeWithoutResult(s -> upsertOne(siteId, v));
                accepted++;
            } catch (RuntimeException e) {
                rejected++;
                errors.add(new RecordError(v.sourceUuid(), "UPSERT_FAILED",
                        e.getClass().getSimpleName() + ": " + e.getMessage()));
                log.warn("Failed to upsert ptme mother visit {}: {}", v.sourceUuid(), e.getMessage());
            }
        }
        return new BatchResult(accepted, rejected, errors);
    }

    private void upsertOne(long siteId, PtmeMotherVisitDto v) {
        String extra = v.extraData() == null ? null : writeJson(v.extraData());
        jdbc.update(
                """
                INSERT INTO core.ptme_mother_visits (
                  site_id, source_uuid, mother_source_uuid,
                  visit_date, gestational_age,
                  continuing_arv, continuing_ctx,
                  extra_data, voided, created_at, updated_at
                ) VALUES (
                  ?, ?, ?,
                  ?, ?,
                  ?, ?,
                  ?::jsonb, ?, NOW(), NOW()
                )
                ON CONFLICT (site_id, source_uuid) DO UPDATE SET
                  mother_source_uuid = EXCLUDED.mother_source_uuid,
                  visit_date         = EXCLUDED.visit_date,
                  gestational_age    = EXCLUDED.gestational_age,
                  continuing_arv     = EXCLUDED.continuing_arv,
                  continuing_ctx     = EXCLUDED.continuing_ctx,
                  extra_data         = EXCLUDED.extra_data,
                  voided             = EXCLUDED.voided,
                  updated_at         = NOW()
                """,
                siteId, v.sourceUuid(),
                v.motherSourceUuid() == null ? null : v.motherSourceUuid().toString(),
                v.visitDate() == null ? null : Date.valueOf(v.visitDate()),
                v.gestationalAge(),
                v.continuingArv(), v.continuingCtx(),
                extra, Boolean.TRUE.equals(v.voided()));
    }

    private String writeJson(Object o) {
        try { return json.writeValueAsString(o); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot serialize extra_data", e); }
    }

    public record BatchResult(int accepted, int rejected, List<RecordError> errors) {}
}
