package ci.itechciv.sigdep.hub.domain.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Read access + resolution of {@code audit.rejected_record}. Surfaces the
 * "Rejets" tab on the Synchronisation page: every record the ingestion-api
 * rejected with its sourceUuid, error code and message. Admins can mark
 * rejects as resolved once the underlying issue is addressed.
 *
 * Geo filter follows the same tightest-wins precedence as the rest of the
 * app.
 */
@Service
public class RejectedRecordService {

    private final JdbcTemplate jdbc;

    public RejectedRecordService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final Map<String, String> SORTABLE = Map.of(
            "rejectedAt",  "r.rejected_at",
            "site",        "s.code",
            "entityType",  "r.entity_type",
            "code",        "r.error_code"
    );

    public RejectsPage list(Long regionId, Long districtId, Long siteId,
                            String entityType, String errorCode, String bucket,
                            String sort, String dir,
                            int page, int size) {
        int safeSize = Math.max(1, Math.min(500, size));
        int safePage = Math.max(0, page);
        int offset = safePage * safeSize;

        StringBuilder where = new StringBuilder("WHERE 1=1");
        List<Object> args = new ArrayList<>();

        Long g = geoArg(regionId, districtId, siteId);
        String join = geoJoin(regionId, districtId, siteId);
        if (g != null) args.add(g);

        if (entityType != null && !entityType.isBlank()) {
            where.append(" AND r.entity_type = ?");
            args.add(entityType);
        }
        if (errorCode != null && !errorCode.isBlank()) {
            where.append(" AND r.error_code = ?");
            args.add(errorCode);
        }
        // bucket: "open" (default) = resolved_at IS NULL ; "resolved" ; "all"
        switch (bucket == null ? "open" : bucket) {
            case "resolved" -> where.append(" AND r.resolved_at IS NOT NULL");
            case "all"      -> { /* no filter */ }
            default         -> where.append(" AND r.resolved_at IS NULL");
        }

        // For the count: only the geo join (if any).
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM audit.rejected_record r" + join + " " + where,
                Long.class, args.toArray());

        // For the select: always read site code/name via a LEFT JOIN aliased
        // s2 so we have it even when no geo filter is active. The inner join
        // on alias s (if present) filters the row set.
        String sql = "SELECT r.id, r.batch_id, r.entity_type, r.source_uuid,"
                + "       r.error_code, r.error_message,"
                + "       r.rejected_at, r.resolved_at, r.resolved_by, r.resolution_note,"
                + "       s2.code AS site_code, s2.name AS site_name"
                + "  FROM audit.rejected_record r"
                + join
                + " LEFT JOIN core.sites s2 ON s2.id = r.site_id"
                + " " + where
                + SortSpec.orderBy(sort, dir, SORTABLE, "r.rejected_at DESC, r.id DESC")
                + " LIMIT ? OFFSET ?";

        List<Object> pagedArgs = new ArrayList<>(args);
        pagedArgs.add(safeSize);
        pagedArgs.add(offset);

        List<RejectRow> rows = jdbc.query(sql, (rs, i) -> {
            var rejectedAt = rs.getTimestamp("rejected_at");
            var resolvedAt = rs.getTimestamp("resolved_at");
            return new RejectRow(
                    rs.getLong("id"),
                    (Long) rs.getObject("batch_id"),
                    rs.getString("entity_type"),
                    rs.getString("source_uuid"),
                    rs.getString("error_code"),
                    rs.getString("error_message"),
                    rejectedAt == null ? null : rejectedAt.toInstant().atOffset(ZoneOffset.UTC),
                    resolvedAt == null ? null : resolvedAt.toInstant().atOffset(ZoneOffset.UTC),
                    rs.getString("resolved_by"),
                    rs.getString("resolution_note"),
                    rs.getString("site_code"),
                    rs.getString("site_name"));
        }, pagedArgs.toArray());

        return new RejectsPage(rows, total == null ? 0L : total, safePage, safeSize);
    }

    /**
     * Aggregate counters for the Rejets tab badge: how many open rejects,
     * split by entity type.
     */
    public List<EntityCount> openCountsByEntity(Long regionId, Long districtId, Long siteId) {
        String join = geoJoin(regionId, districtId, siteId);
        Long g = geoArg(regionId, districtId, siteId);
        Object[] args = g == null ? new Object[0] : new Object[]{g};
        return jdbc.query(
                "SELECT r.entity_type, count(*) AS n"
              + "  FROM audit.rejected_record r" + join
              + " WHERE r.resolved_at IS NULL"
              + " GROUP BY r.entity_type"
              + " ORDER BY n DESC",
                (rs, i) -> new EntityCount(rs.getString("entity_type"), rs.getLong("n")),
                args);
    }

    /**
     * Mark a reject as resolved. Idempotent: re-resolving a row keeps the
     * original resolver/note unchanged (use a separate update path if you
     * need to revise the note).
     */
    public boolean resolve(long id, String resolvedBy, String note) {
        int n = jdbc.update(
                "UPDATE audit.rejected_record"
              + "   SET resolved_at = NOW(), resolved_by = ?, resolution_note = ?"
              + " WHERE id = ? AND resolved_at IS NULL",
                resolvedBy, note == null ? null : note.substring(0, Math.min(500, note.length())),
                id);
        return n > 0;
    }

    // ---------- geo helpers (same precedence as the rest of the app) -------

    private static String geoJoin(Long regionId, Long districtId, Long siteId) {
        if (siteId != null) {
            return " JOIN core.sites s ON s.id = r.site_id AND s.id = ?";
        }
        if (districtId != null) {
            return " JOIN core.sites s ON s.id = r.site_id AND s.district_id = ?";
        }
        if (regionId != null) {
            return " JOIN core.sites s ON s.id = r.site_id"
                 + " JOIN core.districts d ON d.id = s.district_id AND d.region_id = ?";
        }
        return "";
    }

    private static Long geoArg(Long regionId, Long districtId, Long siteId) {
        if (siteId != null)     return siteId;
        if (districtId != null) return districtId;
        return regionId;
    }

    // ---------- records ----------------------------------------------------

    public record RejectRow(
            long id,
            Long batchId,
            String entityType,
            String sourceUuid,
            String errorCode,
            String errorMessage,
            OffsetDateTime rejectedAt,
            OffsetDateTime resolvedAt,
            String resolvedBy,
            String resolutionNote,
            String siteCode,
            String siteName
    ) {}

    public record RejectsPage(List<RejectRow> content, long total, int page, int size) {}

    public record EntityCount(String entityType, long count) {}
}
