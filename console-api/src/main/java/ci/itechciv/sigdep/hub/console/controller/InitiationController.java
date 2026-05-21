package ci.itechciv.sigdep.hub.console.controller;

import ci.itechciv.sigdep.hub.console.security.AuthScope;
import ci.itechciv.sigdep.hub.console.security.AuthScope.Scope;
import ci.itechciv.sigdep.hub.domain.service.InitiationService;
import ci.itechciv.sigdep.hub.domain.service.InitiationService.InitiationRecord;
import ci.itechciv.sigdep.hub.domain.service.InitiationService.InitiationSummary;
import ci.itechciv.sigdep.hub.domain.service.InitiationService.RecordPage;
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
@RequestMapping("/api/v1/initiations")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','IT_ADMIN','NATIONAL_VIEWER','REGIONAL_COORD','DISTRICT_COORD','SITE_USER','ANALYST','AUDITOR')")
public class InitiationController {

    private final InitiationService service;
    private final AuthScope authScope;

    public InitiationController(InitiationService service, AuthScope authScope) {
        this.service = service;
        this.authScope = authScope;
    }

    @GetMapping("/summary")
    public InitiationSummary summary(
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
                "attachment; filename=\"initiations-" + safeMonths + "m.csv\"");

        DateTimeFormatter df = DateTimeFormatter.ISO_LOCAL_DATE;
        try (PrintWriter w = response.getWriter()) {
            w.write('﻿');
            w.println("date_init;date_enrol;patient_code;porte_entree;type_vih;regime_initial;stade_oms;stade_cdc;"
                    + "poids_kg;karnofsky;refere;origine_referencement;site_code;site_nom");
            for (InitiationRecord r : page.content()) {
                w.print(r.arvInitDate() == null ? "" : r.arvInitDate().format(df));
                w.print(';');
                w.print(r.enrollmentDate() == null ? "" : r.enrollmentDate().format(df));
                w.print(';');
                w.print(csv(r.patientCode()));
                w.print(';');
                w.print(csv(r.entryPoint()));
                w.print(';');
                w.print(csv(r.hivType()));
                w.print(';');
                w.print(csv(r.arvRegimenInitial()));
                w.print(';');
                w.print(csv(r.whoStageInitial()));
                w.print(';');
                w.print(csv(r.cdcStageInitial()));
                w.print(';');
                w.print(r.weightInitialKg() == null ? "" : r.weightInitialKg().toPlainString());
                w.print(';');
                w.print(r.karnofskyScore() == null ? "" : r.karnofskyScore().toString());
                w.print(';');
                w.print(csv(r.referred()));
                w.print(';');
                w.print(csv(r.referredOrigin()));
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
