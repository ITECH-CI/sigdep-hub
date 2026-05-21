package ci.itechciv.sigdep.hub.console.controller;

import ci.itechciv.sigdep.hub.console.security.AuthScope;
import ci.itechciv.sigdep.hub.console.security.AuthScope.Scope;
import ci.itechciv.sigdep.hub.domain.service.RejectedRecordService;
import ci.itechciv.sigdep.hub.domain.service.RejectedRecordService.EntityCount;
import ci.itechciv.sigdep.hub.domain.service.RejectedRecordService.RejectsPage;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lists records the ingestion-api rejected (one row per reject in
 * audit.rejected_record) and lets an admin mark them resolved once the
 * underlying issue is fixed.
 */
@RestController
@RequestMapping("/api/v1/sync/rejected")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','IT_ADMIN','NATIONAL_VIEWER','REGIONAL_COORD','DISTRICT_COORD','SITE_USER','AUDITOR')")
public class RejectedRecordController {

    private final RejectedRecordService service;
    private final AuthScope authScope;

    public RejectedRecordController(RejectedRecordService service, AuthScope authScope) {
        this.service = service;
        this.authScope = authScope;
    }

    @GetMapping
    public RejectsPage list(
            @RequestParam(required = false) Long regionId,
            @RequestParam(required = false) Long districtId,
            @RequestParam(required = false) Long siteId,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String errorCode,
            @RequestParam(defaultValue = "open") String bucket,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String dir,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Scope s = authScope.effective(regionId, districtId, siteId);
        return service.list(s.regionId(), s.districtId(), s.siteId(),
                entityType, errorCode, bucket, sort, dir, page, size);
    }

    @GetMapping("/counts")
    public List<EntityCount> openCounts(
            @RequestParam(required = false) Long regionId,
            @RequestParam(required = false) Long districtId,
            @RequestParam(required = false) Long siteId) {
        Scope s = authScope.effective(regionId, districtId, siteId);
        return service.openCountsByEntity(s.regionId(), s.districtId(), s.siteId());
    }

    @PostMapping("/{id}/resolve")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','IT_ADMIN','AUDITOR')")
    public ResponseEntity<Void> resolve(
            @PathVariable long id,
            @RequestBody(required = false) ResolveRequest body,
            Authentication auth) {
        String username = "unknown";
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            String preferred = jwt.getClaim("preferred_username");
            if (preferred != null && !preferred.isBlank()) username = preferred;
        }
        boolean ok = service.resolve(id, username, body == null ? null : body.note());
        return ok ? ResponseEntity.noContent().build()
                  : ResponseEntity.status(404).build();
    }

    public record ResolveRequest(String note) {}
}
