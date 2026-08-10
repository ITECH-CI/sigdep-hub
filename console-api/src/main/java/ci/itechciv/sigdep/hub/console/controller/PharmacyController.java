package ci.itechciv.sigdep.hub.console.controller;

import ci.itechciv.sigdep.hub.console.security.AuthScope;
import ci.itechciv.sigdep.hub.console.security.AuthScope.Scope;
import ci.itechciv.sigdep.hub.domain.service.PeriodRange;
import ci.itechciv.sigdep.hub.domain.service.PharmacyService;
import ci.itechciv.sigdep.hub.domain.service.PharmacyService.DispensationPage;
import ci.itechciv.sigdep.hub.domain.service.PharmacyService.DispensationRow;
import ci.itechciv.sigdep.hub.domain.service.PharmacyService.PharmacySummary;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pharmacy")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','IT_ADMIN','NATIONAL_VIEWER','REGIONAL_COORD','DISTRICT_COORD','SITE_USER','ANALYST','AUDITOR')")
public class PharmacyController {

    private final PharmacyService service;
    private final AuthScope authScope;

    public PharmacyController(PharmacyService service, AuthScope authScope) {
        this.service = service;
        this.authScope = authScope;
    }

    @GetMapping("/summary")
    public PharmacySummary summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long regionId,
            @RequestParam(required = false) Long districtId,
            @RequestParam(required = false) Long siteId) {
        Scope s = authScope.effective(regionId, districtId, siteId);
        PeriodRange period = PeriodRange.resolve(from, to);
        return service.summary(period, s.regionId(), s.districtId(), s.siteId());
    }

    @GetMapping("/dispensations")
    public DispensationPage dispensations(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long regionId,
            @RequestParam(required = false) Long districtId,
            @RequestParam(required = false) Long siteId,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String dir,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Scope s = authScope.effective(regionId, districtId, siteId);
        PeriodRange period = PeriodRange.resolve(from, to);
        return service.dispensations(period,
                s.regionId(), s.districtId(), s.siteId(), sort, dir, page, size);
    }

    @GetMapping(value = "/dispensations.csv", produces = "text/csv;charset=UTF-8")
    public void dispensationsCsv(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long regionId,
            @RequestParam(required = false) Long districtId,
            @RequestParam(required = false) Long siteId,
            HttpServletResponse response) throws IOException {

        Scope s = authScope.effective(regionId, districtId, siteId);
        PeriodRange period = PeriodRange.resolve(from, to);
        DispensationPage page = service.dispensations(period,
                s.regionId(), s.districtId(), s.siteId(), null, null, 0, 100_000);

        String filename = "pharmacie-" + period.from() + "_" + period.to() + ".csv";
        response.setContentType("text/csv;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");

        DateTimeFormatter df = DateTimeFormatter.ISO_LOCAL_DATE;
        try (PrintWriter w = response.getWriter()) {
            w.write('﻿'); // BOM
            w.println("date_dispensation;patient_code;regime_arv;arv_jours;ctx_jours;prochaine_visite;site_code;site_nom");
            for (DispensationRow r : page.content()) {
                w.print(r.visitDate() == null ? "" : r.visitDate().format(df)); w.print(';');
                w.print(csv(r.patientCode())); w.print(';');
                w.print(csv(r.arvRegimen())); w.print(';');
                w.print(r.arvDays() == null ? "" : r.arvDays()); w.print(';');
                w.print(r.cotrimDays() == null ? "" : r.cotrimDays()); w.print(';');
                w.print(r.nextVisitDate() == null ? "" : r.nextVisitDate().format(df)); w.print(';');
                w.print(csv(r.siteCode())); w.print(';');
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
