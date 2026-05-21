package ci.itechciv.sigdep.hub.ingestion.controller;

import ci.itechciv.sigdep.contracts.EntityType;
import ci.itechciv.sigdep.contracts.SyncBatchRequest;
import ci.itechciv.sigdep.contracts.SyncBatchResponse;
import ci.itechciv.sigdep.contracts.SyncBatchResponse.RecordError;
import ci.itechciv.sigdep.contracts.dto.ClosureDto;
import ci.itechciv.sigdep.contracts.dto.DispensationDto;
import ci.itechciv.sigdep.contracts.dto.LabResultDto;
import ci.itechciv.sigdep.contracts.dto.PatientDto;
import ci.itechciv.sigdep.contracts.dto.PtmeChildDto;
import ci.itechciv.sigdep.contracts.dto.PtmeChildVisitDto;
import ci.itechciv.sigdep.contracts.dto.PtmeMotherDto;
import ci.itechciv.sigdep.contracts.dto.PtmeMotherVisitDto;
import ci.itechciv.sigdep.contracts.dto.ScreeningDto;
import ci.itechciv.sigdep.contracts.dto.TptRecordDto;
import ci.itechciv.sigdep.contracts.dto.TreatmentInitiationDto;
import ci.itechciv.sigdep.contracts.dto.VisitDto;
import ci.itechciv.sigdep.hub.domain.entity.Site;
import ci.itechciv.sigdep.hub.domain.repository.SiteRepository;
import ci.itechciv.sigdep.hub.domain.service.SiteResolver;
import ci.itechciv.sigdep.hub.ingestion.log.SyncBatchLogger;
import ci.itechciv.sigdep.hub.ingestion.writer.ClosureWriter;
import ci.itechciv.sigdep.hub.ingestion.writer.InitiationWriter;
import ci.itechciv.sigdep.hub.ingestion.writer.LabResultWriter;
import ci.itechciv.sigdep.hub.ingestion.writer.PatientWriter;
import ci.itechciv.sigdep.hub.ingestion.writer.PtmeChildVisitWriter;
import ci.itechciv.sigdep.hub.ingestion.writer.PtmeChildWriter;
import ci.itechciv.sigdep.hub.ingestion.writer.PtmeMotherVisitWriter;
import ci.itechciv.sigdep.hub.ingestion.writer.PtmeMotherWriter;
import ci.itechciv.sigdep.hub.ingestion.writer.ScreeningWriter;
import ci.itechciv.sigdep.hub.ingestion.writer.TptWriter;
import ci.itechciv.sigdep.hub.ingestion.writer.VisitWriter;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sync")
public class SyncController {

    private static final Logger log = LoggerFactory.getLogger(SyncController.class);

    private final SiteResolver siteResolver;
    private final SiteRepository sites;
    private final PatientWriter patientWriter;
    private final VisitWriter visitWriter;
    private final InitiationWriter initiationWriter;
    private final ClosureWriter closureWriter;
    private final LabResultWriter labResultWriter;
    private final TptWriter tptWriter;
    private final ScreeningWriter screeningWriter;
    private final PtmeMotherWriter ptmeMotherWriter;
    private final PtmeMotherVisitWriter ptmeMotherVisitWriter;
    private final PtmeChildWriter ptmeChildWriter;
    private final PtmeChildVisitWriter ptmeChildVisitWriter;
    private final SyncBatchLogger auditLog;

    public SyncController(SiteResolver siteResolver,
                          SiteRepository sites,
                          PatientWriter patientWriter,
                          VisitWriter visitWriter,
                          InitiationWriter initiationWriter,
                          ClosureWriter closureWriter,
                          LabResultWriter labResultWriter,
                          TptWriter tptWriter,
                          ScreeningWriter screeningWriter,
                          PtmeMotherWriter ptmeMotherWriter,
                          PtmeMotherVisitWriter ptmeMotherVisitWriter,
                          PtmeChildWriter ptmeChildWriter,
                          PtmeChildVisitWriter ptmeChildVisitWriter,
                          SyncBatchLogger auditLog) {
        this.siteResolver = siteResolver;
        this.sites = sites;
        this.patientWriter = patientWriter;
        this.visitWriter = visitWriter;
        this.initiationWriter = initiationWriter;
        this.closureWriter = closureWriter;
        this.labResultWriter = labResultWriter;
        this.tptWriter = tptWriter;
        this.screeningWriter = screeningWriter;
        this.ptmeMotherWriter = ptmeMotherWriter;
        this.ptmeMotherVisitWriter = ptmeMotherVisitWriter;
        this.ptmeChildWriter = ptmeChildWriter;
        this.ptmeChildVisitWriter = ptmeChildVisitWriter;
        this.auditLog = auditLog;
    }

    @PostMapping("/patients")
    public ResponseEntity<SyncBatchResponse> ingestPatients(@RequestBody SyncBatchRequest<PatientDto> batch) {
        Site site = siteResolver.resolve(batch.siteCode(), null);
        log.info("Ingesting {} patients for site {} (batch {})",
                batch.records().size(), site.getCode(), batch.batchId());
        Instant t0 = Instant.now();
        long auditId = auditLog.start(batch.batchId(), site.getId(), site.getCode(),
                "patients", batch.records().size());
        try {
            var r = patientWriter.upsertBatch(site.getId(), batch.records());
            sites.touchLastSyncAt(site.getId());
            auditLog.finish(auditId, t0, r.accepted(), r.rejected(), r.errors(), site.getId(), "patients");
            return ResponseEntity.ok(new SyncBatchResponse(
                    batch.batchId(), r.accepted(), r.rejected(), r.errors()));
        } catch (RuntimeException ex) {
            auditLog.fail(auditId, t0, ex);
            throw ex;
        }
    }

    @PostMapping("/visits")
    public ResponseEntity<SyncBatchResponse> ingestVisits(@RequestBody SyncBatchRequest<VisitDto> batch) {
        Site site = siteResolver.resolve(batch.siteCode(), null);
        log.info("Ingesting {} visits for site {} (batch {})",
                batch.records().size(), site.getCode(), batch.batchId());
        Instant t0 = Instant.now();
        long auditId = auditLog.start(batch.batchId(), site.getId(), site.getCode(),
                "visits", batch.records().size());
        try {
            var r = visitWriter.upsertBatch(site.getId(), batch.records());
            sites.touchLastSyncAt(site.getId());
            auditLog.finish(auditId, t0, r.accepted(), r.rejected(), r.errors(), site.getId(), "visits");
            return ResponseEntity.ok(new SyncBatchResponse(
                    batch.batchId(), r.accepted(), r.rejected(), r.errors()));
        } catch (RuntimeException ex) {
            auditLog.fail(auditId, t0, ex);
            throw ex;
        }
    }

    @PostMapping("/treatment_initiations")
    public ResponseEntity<SyncBatchResponse> ingestInitiations(
            @RequestBody SyncBatchRequest<TreatmentInitiationDto> batch) {
        Site site = siteResolver.resolve(batch.siteCode(), null);
        log.info("Ingesting {} treatment initiations for site {} (batch {})",
                batch.records().size(), site.getCode(), batch.batchId());
        Instant t0 = Instant.now();
        long auditId = auditLog.start(batch.batchId(), site.getId(), site.getCode(),
                "treatment_initiations", batch.records().size());
        try {
            var r = initiationWriter.upsertBatch(site.getId(), batch.records());
            sites.touchLastSyncAt(site.getId());
            auditLog.finish(auditId, t0, r.accepted(), r.rejected(), r.errors(),
                    site.getId(), "treatment_initiations");
            return ResponseEntity.ok(new SyncBatchResponse(
                    batch.batchId(), r.accepted(), r.rejected(), r.errors()));
        } catch (RuntimeException ex) {
            auditLog.fail(auditId, t0, ex);
            throw ex;
        }
    }

    @PostMapping("/closures")
    public ResponseEntity<SyncBatchResponse> ingestClosures(@RequestBody SyncBatchRequest<ClosureDto> batch) {
        Site site = siteResolver.resolve(batch.siteCode(), null);
        log.info("Ingesting {} closures for site {} (batch {})",
                batch.records().size(), site.getCode(), batch.batchId());
        Instant t0 = Instant.now();
        long auditId = auditLog.start(batch.batchId(), site.getId(), site.getCode(),
                "closures", batch.records().size());
        try {
            var r = closureWriter.upsertBatch(site.getId(), batch.records());
            sites.touchLastSyncAt(site.getId());
            auditLog.finish(auditId, t0, r.accepted(), r.rejected(), r.errors(), site.getId(), "closures");
            return ResponseEntity.ok(new SyncBatchResponse(
                    batch.batchId(), r.accepted(), r.rejected(), r.errors()));
        } catch (RuntimeException ex) {
            auditLog.fail(auditId, t0, ex);
            throw ex;
        }
    }

    @PostMapping("/lab_results")
    public ResponseEntity<SyncBatchResponse> ingestLabResults(@RequestBody SyncBatchRequest<LabResultDto> batch) {
        Site site = siteResolver.resolve(batch.siteCode(), null);
        log.info("Ingesting {} lab results for site {} (batch {})",
                batch.records().size(), site.getCode(), batch.batchId());
        Instant t0 = Instant.now();
        long auditId = auditLog.start(batch.batchId(), site.getId(), site.getCode(),
                "lab_results", batch.records().size());
        try {
            var r = labResultWriter.upsertBatch(site.getId(), batch.records());
            sites.touchLastSyncAt(site.getId());
            auditLog.finish(auditId, t0, r.accepted(), r.rejected(), r.errors(), site.getId(), "lab_results");
            return ResponseEntity.ok(new SyncBatchResponse(
                    batch.batchId(), r.accepted(), r.rejected(), r.errors()));
        } catch (RuntimeException ex) {
            auditLog.fail(auditId, t0, ex);
            throw ex;
        }
    }

    @PostMapping("/tpt_records")
    public ResponseEntity<SyncBatchResponse> ingestTptRecords(@RequestBody SyncBatchRequest<TptRecordDto> batch) {
        Site site = siteResolver.resolve(batch.siteCode(), null);
        log.info("Ingesting {} TPT records for site {} (batch {})",
                batch.records().size(), site.getCode(), batch.batchId());
        Instant t0 = Instant.now();
        long auditId = auditLog.start(batch.batchId(), site.getId(), site.getCode(),
                "tpt_records", batch.records().size());
        try {
            var r = tptWriter.upsertBatch(site.getId(), batch.records());
            sites.touchLastSyncAt(site.getId());
            auditLog.finish(auditId, t0, r.accepted(), r.rejected(), r.errors(), site.getId(), "tpt_records");
            return ResponseEntity.ok(new SyncBatchResponse(
                    batch.batchId(), r.accepted(), r.rejected(), r.errors()));
        } catch (RuntimeException ex) {
            auditLog.fail(auditId, t0, ex);
            throw ex;
        }
    }

    @PostMapping("/screenings")
    public ResponseEntity<SyncBatchResponse> ingestScreenings(@RequestBody SyncBatchRequest<ScreeningDto> batch) {
        Site site = siteResolver.resolve(batch.siteCode(), null);
        log.info("Ingesting {} screenings for site {} (batch {})",
                batch.records().size(), site.getCode(), batch.batchId());
        Instant t0 = Instant.now();
        long auditId = auditLog.start(batch.batchId(), site.getId(), site.getCode(),
                "screenings", batch.records().size());
        try {
            var r = screeningWriter.upsertBatch(site.getId(), batch.records());
            sites.touchLastSyncAt(site.getId());
            auditLog.finish(auditId, t0, r.accepted(), r.rejected(), r.errors(), site.getId(), "screenings");
            return ResponseEntity.ok(new SyncBatchResponse(
                    batch.batchId(), r.accepted(), r.rejected(), r.errors()));
        } catch (RuntimeException ex) {
            auditLog.fail(auditId, t0, ex);
            throw ex;
        }
    }

    @PostMapping("/ptme_mothers")
    public ResponseEntity<SyncBatchResponse> ingestPtmeMothers(@RequestBody SyncBatchRequest<PtmeMotherDto> batch) {
        Site site = siteResolver.resolve(batch.siteCode(), null);
        log.info("Ingesting {} PTME mothers for site {} (batch {})",
                batch.records().size(), site.getCode(), batch.batchId());
        Instant t0 = Instant.now();
        long auditId = auditLog.start(batch.batchId(), site.getId(), site.getCode(),
                "ptme_mothers", batch.records().size());
        try {
            var r = ptmeMotherWriter.upsertBatch(site.getId(), batch.records());
            sites.touchLastSyncAt(site.getId());
            auditLog.finish(auditId, t0, r.accepted(), r.rejected(), r.errors(), site.getId(), "ptme_mothers");
            return ResponseEntity.ok(new SyncBatchResponse(
                    batch.batchId(), r.accepted(), r.rejected(), r.errors()));
        } catch (RuntimeException ex) {
            auditLog.fail(auditId, t0, ex);
            throw ex;
        }
    }

    @PostMapping("/ptme_mother_visits")
    public ResponseEntity<SyncBatchResponse> ingestPtmeMotherVisits(@RequestBody SyncBatchRequest<PtmeMotherVisitDto> batch) {
        Site site = siteResolver.resolve(batch.siteCode(), null);
        log.info("Ingesting {} PTME mother visits for site {} (batch {})",
                batch.records().size(), site.getCode(), batch.batchId());
        Instant t0 = Instant.now();
        long auditId = auditLog.start(batch.batchId(), site.getId(), site.getCode(),
                "ptme_mother_visits", batch.records().size());
        try {
            var r = ptmeMotherVisitWriter.upsertBatch(site.getId(), batch.records());
            sites.touchLastSyncAt(site.getId());
            auditLog.finish(auditId, t0, r.accepted(), r.rejected(), r.errors(), site.getId(), "ptme_mother_visits");
            return ResponseEntity.ok(new SyncBatchResponse(
                    batch.batchId(), r.accepted(), r.rejected(), r.errors()));
        } catch (RuntimeException ex) {
            auditLog.fail(auditId, t0, ex);
            throw ex;
        }
    }

    @PostMapping("/ptme_children")
    public ResponseEntity<SyncBatchResponse> ingestPtmeChildren(@RequestBody SyncBatchRequest<PtmeChildDto> batch) {
        Site site = siteResolver.resolve(batch.siteCode(), null);
        log.info("Ingesting {} PTME children for site {} (batch {})",
                batch.records().size(), site.getCode(), batch.batchId());
        Instant t0 = Instant.now();
        long auditId = auditLog.start(batch.batchId(), site.getId(), site.getCode(),
                "ptme_children", batch.records().size());
        try {
            var r = ptmeChildWriter.upsertBatch(site.getId(), batch.records());
            sites.touchLastSyncAt(site.getId());
            auditLog.finish(auditId, t0, r.accepted(), r.rejected(), r.errors(), site.getId(), "ptme_children");
            return ResponseEntity.ok(new SyncBatchResponse(
                    batch.batchId(), r.accepted(), r.rejected(), r.errors()));
        } catch (RuntimeException ex) {
            auditLog.fail(auditId, t0, ex);
            throw ex;
        }
    }

    @PostMapping("/ptme_child_visits")
    public ResponseEntity<SyncBatchResponse> ingestPtmeChildVisits(@RequestBody SyncBatchRequest<PtmeChildVisitDto> batch) {
        Site site = siteResolver.resolve(batch.siteCode(), null);
        log.info("Ingesting {} PTME child visits for site {} (batch {})",
                batch.records().size(), site.getCode(), batch.batchId());
        Instant t0 = Instant.now();
        long auditId = auditLog.start(batch.batchId(), site.getId(), site.getCode(),
                "ptme_child_visits", batch.records().size());
        try {
            var r = ptmeChildVisitWriter.upsertBatch(site.getId(), batch.records());
            sites.touchLastSyncAt(site.getId());
            auditLog.finish(auditId, t0, r.accepted(), r.rejected(), r.errors(), site.getId(), "ptme_child_visits");
            return ResponseEntity.ok(new SyncBatchResponse(
                    batch.batchId(), r.accepted(), r.rejected(), r.errors()));
        } catch (RuntimeException ex) {
            auditLog.fail(auditId, t0, ex);
            throw ex;
        }
    }

    /**
     * Dispensations are not yet wired to a writer; we still log the batch as
     * a pass-through (everything accepted) so volume / cadence shows up in
     * the audit page.
     */
    @PostMapping("/dispensations")
    public ResponseEntity<SyncBatchResponse> ingestDispensations(@RequestBody SyncBatchRequest<DispensationDto> batch) {
        Site site = resolveOrNull(batch.siteCode());
        Instant t0 = Instant.now();
        long auditId = auditLog.start(batch.batchId(),
                site == null ? null : site.getId(), batch.siteCode(),
                "dispensations", batch.records().size());
        auditLog.finish(auditId, t0, batch.records().size(), 0, List.<RecordError>of(),
                site == null ? null : site.getId(), "dispensations");
        return ResponseEntity.ok(new SyncBatchResponse(
                batch.batchId(), batch.records().size(), 0, List.of()));
    }

    @PostMapping("/{entityType}/backfill")
    public ResponseEntity<SyncBatchResponse> ingestBackfill(
            @PathVariable EntityType entityType,
            @RequestBody SyncBatchRequest<Object> batch) {
        Site site = resolveOrNull(batch.siteCode());
        Instant t0 = Instant.now();
        String entityName = "backfill_" + entityType.name().toLowerCase(java.util.Locale.ROOT);
        long auditId = auditLog.start(batch.batchId(),
                site == null ? null : site.getId(), batch.siteCode(),
                entityName, batch.records().size());
        auditLog.finish(auditId, t0, batch.records().size(), 0, List.<RecordError>of(),
                site == null ? null : site.getId(), entityName);
        return ResponseEntity.ok(new SyncBatchResponse(
                batch.batchId(), batch.records().size(), 0, List.of()));
    }

    /** Best-effort site lookup that swallows "not found" so audit still records. */
    private Site resolveOrNull(String code) {
        try {
            return siteResolver.resolve(code, null);
        } catch (RuntimeException ex) {
            log.warn("Unknown site code {} in audit-only batch: {}", code, ex.toString());
            return null;
        }
    }
}
