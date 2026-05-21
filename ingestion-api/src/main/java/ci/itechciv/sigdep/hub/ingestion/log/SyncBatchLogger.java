package ci.itechciv.sigdep.hub.ingestion.log;

import ci.itechciv.sigdep.contracts.SyncBatchResponse.RecordError;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists one row in {@code audit.sync_batch} per call to the SyncController.
 * The logger never throws — failures to write the audit row are logged but do
 * not fail the ingest itself, so a transient audit DB problem can't lose data.
 *
 * Each call follows the {@link #start} → ingest → {@link #finish} pattern.
 * The audit insert runs in its own transaction (REQUIRES_NEW) so it is
 * persisted even if the surrounding ingest transaction rolls back.
 */
@Service
public class SyncBatchLogger {

    private static final Logger log = LoggerFactory.getLogger(SyncBatchLogger.class);
    private static final int MAX_ERROR_SAMPLE = 5;

    private final SimpleJdbcInsert insert;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public SyncBatchLogger(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.insert = new SimpleJdbcInsert(jdbc)
                .withSchemaName("audit")
                .withTableName("sync_batch")
                .usingGeneratedKeyColumns("id");
    }

    /**
     * Open a row for an incoming batch. Returns the audit row id (a long), or
     * {@code -1} if the audit insert failed — callers ignore that and don't
     * call {@link #finish}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long start(UUID batchId, Long siteId, String siteCode,
                      String entityType, int receivedCount) {
        try {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("batch_id", batchId == null ? null : batchId.toString());
            row.put("site_id", siteId);
            row.put("site_code", siteCode);
            row.put("entity_type", entityType);
            row.put("received_count", receivedCount);
            row.put("accepted", 0);
            row.put("rejected", 0);
            row.put("started_at", java.sql.Timestamp.from(Instant.now()));
            row.put("status", "ok");
            return insert.executeAndReturnKey(row).longValue();
        } catch (Exception ex) {
            log.warn("Failed to open audit.sync_batch row for {} batch {}: {}",
                    entityType, batchId, ex.toString());
            return -1L;
        }
    }

    /** Close out the row with the final counts. Silent on errors. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(long auditId, Instant startedAt,
                       int accepted, int rejected, List<RecordError> errors,
                       Long siteId, String entityType) {
        if (auditId <= 0) return;
        try {
            String status = rejected == 0 ? "ok" : "partial";
            Instant now = Instant.now();
            long durationMs = Duration.between(startedAt, now).toMillis();
            String sample = errors == null || errors.isEmpty()
                    ? null : json.writeValueAsString(buildErrorSample(errors));
            jdbc.update("UPDATE audit.sync_batch"
                            + "    SET accepted = ?, rejected = ?,"
                            + "        finished_at = ?, duration_ms = ?,"
                            + "        status = ?, error_sample = ?::jsonb"
                            + "  WHERE id = ?",
                    accepted, rejected,
                    java.sql.Timestamp.from(now), (int) durationMs,
                    status, sample, auditId);
            // Per-row rejects: every error becomes a row in audit.rejected_record
            // so operators can find which specific OpenMRS UUID failed without
            // grepping the aggregated error_sample.
            if (errors != null && !errors.isEmpty()) {
                insertRejects(auditId, siteId, entityType, errors);
            }
        } catch (Exception ex) {
            log.warn("Failed to close audit.sync_batch row {}: {}", auditId, ex.toString());
        }
    }

    /**
     * Bulk-insert one row per reject. Done in the same {@code REQUIRES_NEW}
     * transaction as the audit update so a partial failure here doesn't leave
     * the audit row half-closed.
     */
    private void insertRejects(long auditId, Long siteId, String entityType,
                               List<RecordError> errors) {
        if (errors.isEmpty()) return;
        List<Object[]> batch = new java.util.ArrayList<>(errors.size());
        for (RecordError e : errors) {
            batch.add(new Object[]{
                    auditId,
                    siteId,
                    entityType,
                    e.sourceUuid() == null ? "unknown" : e.sourceUuid().toString(),
                    e.code(),
                    e.message() == null ? null : truncate(e.message(), 4000),
            });
        }
        jdbc.batchUpdate(
                "INSERT INTO audit.rejected_record"
              + "  (batch_id, site_id, entity_type, source_uuid, error_code, error_message)"
              + "  VALUES (?, ?, ?, ?, ?, ?)",
                batch);
    }

    /** Mark the row failed (an exception escaped the writer). Silent on errors. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(long auditId, Instant startedAt, Throwable cause) {
        if (auditId <= 0) return;
        try {
            Instant now = Instant.now();
            long durationMs = Duration.between(startedAt, now).toMillis();
            String message = cause == null ? "unknown" : cause.getClass().getSimpleName()
                    + ": " + (cause.getMessage() == null ? "" : cause.getMessage());
            String sample = json.writeValueAsString(List.of(
                    Map.of("label", truncate(message, 240), "count", 1)));
            jdbc.update("UPDATE audit.sync_batch"
                            + "    SET finished_at = ?, duration_ms = ?,"
                            + "        status = 'failed', error_sample = ?::jsonb"
                            + "  WHERE id = ?",
                    java.sql.Timestamp.from(now), (int) durationMs, sample, auditId);
        } catch (Exception ex) {
            log.warn("Failed to mark audit.sync_batch row {} as failed: {}", auditId, ex.toString());
        }
    }

    /**
     * Group errors by a coarse "label" and keep the top {@value #MAX_ERROR_SAMPLE}
     * entries by count. The label is the code when it's meaningful on its own
     * (UNKNOWN_PATIENT, SITE_NOT_FOUND, ...), and "code: short-message" for
     * generic codes (UPSERT_FAILED) so we don't lose the actual SQL / runtime
     * cause when grouping. Avoids storing the full per-row error list, which
     * can be thousands long.
     */
    private static final java.util.Set<String> GENERIC_CODES =
            java.util.Set.of("UPSERT_FAILED");

    private static List<Map<String, Object>> buildErrorSample(List<RecordError> errors) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (RecordError e : errors) {
            String code = e.code() == null || e.code().isBlank() ? null : e.code();
            String message = e.message();
            String label;
            if (code != null && GENERIC_CODES.contains(code) && message != null && !message.isBlank()) {
                label = code + ": " + truncate(message, 200);
            } else if (code != null) {
                label = code;
            } else if (message != null && !message.isBlank()) {
                label = truncate(message, 200);
            } else {
                label = "unknown";
            }
            counts.merge(label, 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(MAX_ERROR_SAMPLE)
                .<Map<String, Object>>map(e -> Map.of("label", e.getKey(), "count", e.getValue()))
                .toList();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
