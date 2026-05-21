package ci.itechciv.sigdep.hub.console.controller;

import ci.itechciv.sigdep.hub.console.security.AuthScope;
import ci.itechciv.sigdep.hub.console.security.AuthScope.Scope;
import ci.itechciv.sigdep.hub.domain.service.PtmeService;
import ci.itechciv.sigdep.hub.domain.service.PtmeService.ChildPage;
import ci.itechciv.sigdep.hub.domain.service.PtmeService.ChildRecord;
import ci.itechciv.sigdep.hub.domain.service.PtmeService.ChildSummary;
import ci.itechciv.sigdep.hub.domain.service.PtmeService.MotherPage;
import ci.itechciv.sigdep.hub.domain.service.PtmeService.MotherRecord;
import ci.itechciv.sigdep.hub.domain.service.PtmeService.MotherSummary;
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
@RequestMapping("/api/v1/ptme")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','IT_ADMIN','NATIONAL_VIEWER','REGIONAL_COORD','DISTRICT_COORD','SITE_USER','ANALYST','AUDITOR')")
public class PtmeController {

    private final PtmeService service;
    private final AuthScope authScope;

    public PtmeController(PtmeService service, AuthScope authScope) {
        this.service = service;
        this.authScope = authScope;
    }

    private static int safeMonths(int m) { return Math.max(1, Math.min(120, m)); }

    // --- Mother track ---

    @GetMapping("/mothers/summary")
    public MotherSummary motherSummary(
            @RequestParam(defaultValue = "60") int months,
            @RequestParam(required = false) Long regionId,
            @RequestParam(required = false) Long districtId,
            @RequestParam(required = false) Long siteId) {
        Scope s = authScope.effective(regionId, districtId, siteId);
        return service.motherSummary(safeMonths(months), s.regionId(), s.districtId(), s.siteId());
    }

    @GetMapping("/mothers/records")
    public MotherPage motherRecords(
            @RequestParam(defaultValue = "60") int months,
            @RequestParam(required = false) Long regionId,
            @RequestParam(required = false) Long districtId,
            @RequestParam(required = false) Long siteId,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String dir,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Scope s = authScope.effective(regionId, districtId, siteId);
        return service.motherRecords(safeMonths(months),
                s.regionId(), s.districtId(), s.siteId(), sort, dir, page, size);
    }

    @GetMapping(value = "/mothers/records.csv", produces = "text/csv;charset=UTF-8")
    public void motherRecordsCsv(
            @RequestParam(defaultValue = "60") int months,
            @RequestParam(required = false) Long regionId,
            @RequestParam(required = false) Long districtId,
            @RequestParam(required = false) Long siteId,
            HttpServletResponse response) throws IOException {

        int sm = safeMonths(months);
        Scope s = authScope.effective(regionId, districtId, siteId);
        MotherPage page = service.motherRecords(sm,
                s.regionId(), s.districtId(), s.siteId(), null, null, 0, 5000);

        response.setContentType("text/csv;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"ptme-meres-" + sm + "m.csv\"");

        DateTimeFormatter df = DateTimeFormatter.ISO_LOCAL_DATE;
        try (PrintWriter w = response.getWriter()) {
            w.write('﻿');
            w.println("date_debut;date_fin;code_grossesse;code_pec_vih;code_depistage;age;statut_matrimonial;"
                    + "statut_arv;dpa;issue;depistage_conjoint;resultat_conjoint;type_accouchement;"
                    + "site_code;site_nom");
            for (MotherRecord r : page.content()) {
                w.print(r.startDate() == null ? "" : r.startDate().format(df));
                w.print(';');
                w.print(r.endDate() == null ? "" : r.endDate().format(df));
                w.print(';');
                w.print(csv(r.pregnantNumber()));
                w.print(';');
                w.print(csv(r.hivCareNumber()));
                w.print(';');
                w.print(csv(r.screeningNumber()));
                w.print(';');
                w.print(r.age() == null ? "" : r.age().toString());
                w.print(';');
                w.print(csv(r.maritalStatus()));
                w.print(';');
                w.print(csv(r.arvStatusAtRegistering()));
                w.print(';');
                w.print(r.estimatedDeliveryDate() == null ? "" : r.estimatedDeliveryDate().format(df));
                w.print(';');
                w.print(csv(r.pregnancyOutcome()));
                w.print(';');
                w.print(csv(r.spousalScreening()));
                w.print(';');
                w.print(csv(r.spousalScreeningResult()));
                w.print(';');
                w.print(csv(r.deliveryType()));
                w.print(';');
                w.print(csv(r.siteCode()));
                w.print(';');
                w.println(csv(r.siteName()));
            }
        }
    }

    // --- Child track ---

    @GetMapping("/children/summary")
    public ChildSummary childSummary(
            @RequestParam(defaultValue = "60") int months,
            @RequestParam(required = false) Long regionId,
            @RequestParam(required = false) Long districtId,
            @RequestParam(required = false) Long siteId) {
        Scope s = authScope.effective(regionId, districtId, siteId);
        return service.childSummary(safeMonths(months), s.regionId(), s.districtId(), s.siteId());
    }

    @GetMapping("/children/records")
    public ChildPage childRecords(
            @RequestParam(defaultValue = "60") int months,
            @RequestParam(required = false) Long regionId,
            @RequestParam(required = false) Long districtId,
            @RequestParam(required = false) Long siteId,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String dir,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Scope s = authScope.effective(regionId, districtId, siteId);
        return service.childRecords(safeMonths(months),
                s.regionId(), s.districtId(), s.siteId(), sort, dir, page, size);
    }

    @GetMapping(value = "/children/records.csv", produces = "text/csv;charset=UTF-8")
    public void childRecordsCsv(
            @RequestParam(defaultValue = "60") int months,
            @RequestParam(required = false) Long regionId,
            @RequestParam(required = false) Long districtId,
            @RequestParam(required = false) Long siteId,
            HttpServletResponse response) throws IOException {

        int sm = safeMonths(months);
        Scope s = authScope.effective(regionId, districtId, siteId);
        ChildPage page = service.childRecords(sm,
                s.regionId(), s.districtId(), s.siteId(), null, null, 0, 5000);

        response.setContentType("text/csv;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"ptme-enfants-" + sm + "m.csv\"");

        DateTimeFormatter df = DateTimeFormatter.ISO_LOCAL_DATE;
        try (PrintWriter w = response.getWriter()) {
            w.write('﻿');
            w.println("date_naissance;code_suivi;sexe;prophylaxie;date_prophylaxie;"
                    + "pcr1;pcr2;pcr3;serologie1;serologie2;"
                    + "resultat_suivi;date_resultat;code_mere;site_code;site_nom");
            for (ChildRecord r : page.content()) {
                w.print(r.birthDate() == null ? "" : r.birthDate().format(df));
                w.print(';');
                w.print(csv(r.childFollowupNumber()));
                w.print(';');
                w.print(csv(r.gender()));
                w.print(';');
                w.print(csv(r.arvProphylaxisGiven()));
                w.print(';');
                w.print(r.arvProphylaxisGivenDate() == null ? "" : r.arvProphylaxisGivenDate().format(df));
                w.print(';');
                w.print(csv(r.pcr1Result()));
                w.print(';');
                w.print(csv(r.pcr2Result()));
                w.print(';');
                w.print(csv(r.pcr3Result()));
                w.print(';');
                w.print(csv(r.hivSerology1Result()));
                w.print(';');
                w.print(csv(r.hivSerology2Result()));
                w.print(';');
                w.print(csv(r.followupResult()));
                w.print(';');
                w.print(r.followupResultDate() == null ? "" : r.followupResultDate().format(df));
                w.print(';');
                w.print(csv(r.motherSourceUuid()));
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
