package ci.itechciv.sigdep.hub.console.controller;

import ci.itechciv.sigdep.hub.console.security.AuthScope;
import ci.itechciv.sigdep.hub.console.security.AuthScope.Scope;
import ci.itechciv.sigdep.hub.domain.service.PatientQueryService;
import ci.itechciv.sigdep.hub.domain.service.PatientQueryService.EncounterDay;
import ci.itechciv.sigdep.hub.domain.service.PatientQueryService.PatientDetail;
import ci.itechciv.sigdep.hub.domain.service.PatientQueryService.PatientPage;
import ci.itechciv.sigdep.hub.domain.service.PatientQueryService.PatientRow;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/patients")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','IT_ADMIN','NATIONAL_VIEWER','REGIONAL_COORD','DISTRICT_COORD','SITE_USER','ANALYST','AUDITOR')")
public class PatientController {

    private final PatientQueryService service;
    private final AuthScope authScope;

    public PatientController(PatientQueryService service, AuthScope authScope) {
        this.service = service;
        this.authScope = authScope;
    }

    @GetMapping
    public PatientPage list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long regionId,
            @RequestParam(required = false) Long districtId,
            @RequestParam(required = false) Long siteId,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String dir,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        Scope s = authScope.effective(regionId, districtId, siteId);
        return service.list(q, s.regionId(), s.districtId(), s.siteId(), sort, dir, page, size);
    }

    @GetMapping("/{id}")
    public ResponseEntity<PatientDetail> get(@PathVariable long id) {
        PatientDetail detail = service.get(id);
        if (detail == null) return ResponseEntity.notFound().build();
        Scope s = authScope.effective(null, null, null);
        if (!service.isVisibleTo(id, s.regionId(), s.districtId(), s.siteId())) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(detail);
    }

    @GetMapping("/{id}/encounters")
    public List<EncounterDay> encounters(@PathVariable long id) {
        Scope s = authScope.effective(null, null, null);
        if (!service.isVisibleTo(id, s.regionId(), s.districtId(), s.siteId())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN);
        }
        return service.encounters(id);
    }

    /** CSV export. Caps at 5,000 rows to keep memory bounded. */
    @GetMapping(value = "/list.csv", produces = "text/csv;charset=UTF-8")
    public void listCsv(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long regionId,
            @RequestParam(required = false) Long districtId,
            @RequestParam(required = false) Long siteId,
            HttpServletResponse response) throws IOException {

        Scope s = authScope.effective(regionId, districtId, siteId);
        PatientPage page = service.list(q, s.regionId(), s.districtId(), s.siteId(), null, null, 0, 100_000);

        String filename = "patients.csv";
        response.setContentType("text/csv;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");

        DateTimeFormatter df = DateTimeFormatter.ISO_LOCAL_DATE;
        try (PrintWriter w = response.getWriter()) {
            w.write('﻿'); // BOM
            w.println("code_arv;upid;sexe;date_naissance;date_init_arv;regime_arv_initial;derniere_visite;dernier_regime_arv;site_code;site_nom");
            for (PatientRow p : page.content()) {
                w.print(csv(p.codeArv())); w.print(';');
                w.print(csv(p.upid())); w.print(';');
                w.print(csv(p.sex())); w.print(';');
                w.print(p.birthDate() == null ? "" : p.birthDate().format(df)); w.print(';');
                w.print(p.arvInitDate() == null ? "" : p.arvInitDate().format(df)); w.print(';');
                w.print(csv(p.arvRegimenInitial())); w.print(';');
                w.print(p.lastVisitDate() == null ? "" : p.lastVisitDate().format(df)); w.print(';');
                w.print(csv(p.lastArvRegimen())); w.print(';');
                w.print(csv(p.siteCode())); w.print(';');
                w.println(csv(p.siteName()));
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
