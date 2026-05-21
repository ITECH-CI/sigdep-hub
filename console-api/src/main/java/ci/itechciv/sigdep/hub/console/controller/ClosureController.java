package ci.itechciv.sigdep.hub.console.controller;

import ci.itechciv.sigdep.hub.console.security.AuthScope;
import ci.itechciv.sigdep.hub.console.security.AuthScope.Scope;
import ci.itechciv.sigdep.hub.domain.service.ClosureService;
import ci.itechciv.sigdep.hub.domain.service.ClosureService.ClosureRecord;
import ci.itechciv.sigdep.hub.domain.service.ClosureService.ClosureSummary;
import ci.itechciv.sigdep.hub.domain.service.ClosureService.RecordPage;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.format.DateTimeFormatter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/closures")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','IT_ADMIN','NATIONAL_VIEWER','REGIONAL_COORD','DISTRICT_COORD','SITE_USER','ANALYST','AUDITOR')")
public class ClosureController {

    private final ClosureService service;
    private final AuthScope authScope;

    public ClosureController(ClosureService service, AuthScope authScope) {
        this.service = service;
        this.authScope = authScope;
    }

    @GetMapping("/summary")
    public ClosureSummary summary(
            @RequestParam(defaultValue = "60") int months,
            @RequestParam(required = false) Long regionId,
            @RequestParam(required = false) Long districtId,
            @RequestParam(required = false) Long siteId) {
        Scope s = authScope.effective(regionId, districtId, siteId);
        int safe = Math.max(1, Math.min(120, months));
        return service.summary(safe, s.regionId(), s.districtId(), s.siteId());
    }

    @GetMapping("/records")
    public RecordPage records(
            @RequestParam(defaultValue = "60") int months,
            @RequestParam(required = false) Long regionId,
            @RequestParam(required = false) Long districtId,
            @RequestParam(required = false) Long siteId,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String dir,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Scope s = authScope.effective(regionId, districtId, siteId);
        return service.records(Math.max(1, Math.min(120, months)),
                s.regionId(), s.districtId(), s.siteId(), sort, dir, page, size);
    }

    @GetMapping(value = "/records.csv", produces = "text/csv;charset=UTF-8")
    public void recordsCsv(
            @RequestParam(defaultValue = "60") int months,
            @RequestParam(required = false) Long regionId,
            @RequestParam(required = false) Long districtId,
            @RequestParam(required = false) Long siteId,
            HttpServletResponse response) throws IOException {

        int safeMonths = Math.max(1, Math.min(120, months));
        Scope s = authScope.effective(regionId, districtId, siteId);
        RecordPage page = service.records(safeMonths,
                s.regionId(), s.districtId(), s.siteId(), null, null, 0, 5000);

        response.setContentType("text/csv;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"clotures-" + safeMonths + "m.csv\"");

        DateTimeFormatter df = DateTimeFormatter.ISO_LOCAL_DATE;
        try (PrintWriter w = response.getWriter()) {
            w.write('﻿');
            w.println("date_cloture;patient_code;motif;date_deces;cause_deces;"
                    + "date_transfert;destination_transfert;motif_transfert;"
                    + "date_arret_volontaire;date_negativation;site_code;site_nom");
            for (ClosureRecord r : page.content()) {
                w.print(r.closureDate() == null ? "" : r.closureDate().format(df));
                w.print(';');
                w.print(csv(r.patientCode()));
                w.print(';');
                w.print(csv(r.closureType()));
                w.print(';');
                w.print(r.deathDate() == null ? "" : r.deathDate().format(df));
                w.print(';');
                w.print(csv(r.deathCauseText() != null ? r.deathCauseText() : r.deathCauseCode()));
                w.print(';');
                w.print(r.transferDate() == null ? "" : r.transferDate().format(df));
                w.print(';');
                w.print(csv(r.transferDestination()));
                w.print(';');
                w.print(csv(r.transferReason()));
                w.print(';');
                w.print(r.voluntaryStopDate() == null ? "" : r.voluntaryStopDate().format(df));
                w.print(';');
                w.print(r.hivNegativeDate() == null ? "" : r.hivNegativeDate().format(df));
                w.print(';');
                w.print(csv(r.siteCode()));
                w.print(';');
                w.println(csv(r.siteName()));
            }
        }
    }

    private static String csv(String s) {
        if (s == null) return "";
        boolean q = s.contains(";") || s.contains("\"") || s.contains("\n");
        if (!q) return s;
        return '"' + s.replace("\"", "\"\"") + '"';
    }
}
