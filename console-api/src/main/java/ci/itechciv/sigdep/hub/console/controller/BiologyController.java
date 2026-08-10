package ci.itechciv.sigdep.hub.console.controller;

import ci.itechciv.sigdep.hub.console.security.AuthScope;
import ci.itechciv.sigdep.hub.console.security.AuthScope.Scope;
import ci.itechciv.sigdep.hub.domain.service.BiologyService;
import ci.itechciv.sigdep.hub.domain.service.BiologyService.BiologySummary;
import ci.itechciv.sigdep.hub.domain.service.BiologyService.ExamPage;
import ci.itechciv.sigdep.hub.domain.service.BiologyService.ExamRow;
import ci.itechciv.sigdep.hub.domain.service.PeriodRange;
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
@RequestMapping("/api/v1/biology")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','IT_ADMIN','NATIONAL_VIEWER','REGIONAL_COORD','DISTRICT_COORD','SITE_USER','ANALYST','AUDITOR')")
public class BiologyController {

    private final BiologyService service;
    private final AuthScope authScope;

    public BiologyController(BiologyService service, AuthScope authScope) {
        this.service = service;
        this.authScope = authScope;
    }

    @GetMapping("/summary")
    public BiologySummary summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long regionId,
            @RequestParam(required = false) Long districtId,
            @RequestParam(required = false) Long siteId) {
        Scope s = authScope.effective(regionId, districtId, siteId);
        PeriodRange period = PeriodRange.resolve(from, to);
        return service.summary(period, s.regionId(), s.districtId(), s.siteId());
    }

    @GetMapping("/exams")
    public ExamPage exams(
            @RequestParam(required = false) String test,
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
        return service.exams(test, period,
                s.regionId(), s.districtId(), s.siteId(), sort, dir, page, size);
    }

    /** CSV export. Caps at 5,000 rows to keep memory bounded. */
    @GetMapping(value = "/exams.csv", produces = "text/csv;charset=UTF-8")
    public void examsCsv(
            @RequestParam(required = false) String test,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long regionId,
            @RequestParam(required = false) Long districtId,
            @RequestParam(required = false) Long siteId,
            HttpServletResponse response) throws IOException {

        Scope s = authScope.effective(regionId, districtId, siteId);
        PeriodRange period = PeriodRange.resolve(from, to);
        ExamPage page = service.exams(test, period,
                s.regionId(), s.districtId(), s.siteId(), null, null, 0, 100_000);

        String filename = "biology-" + (test == null ? "all" : test)
                + "-" + period.from() + "_" + period.to() + ".csv";
        response.setContentType("text/csv;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");

        DateTimeFormatter df = DateTimeFormatter.ISO_LOCAL_DATE;
        boolean cd4 = "cd4".equals(test);
        try (PrintWriter w = response.getWriter()) {
            w.write('﻿'); // BOM so Excel opens UTF-8 cleanly
            if (cd4) {
                w.println("date;patient_code;examen;cd4_abs;cd4_pct;site_code;site_nom");
            } else {
                w.println("date;patient_code;examen;valeur;unite;site_code;site_nom");
            }
            for (ExamRow e : page.content()) {
                w.print(e.examDate() == null ? "" : e.examDate().format(df));
                w.print(';');
                w.print(csv(e.patientCode()));
                w.print(';');
                w.print(csv(e.testName()));
                w.print(';');
                if (cd4) {
                    w.print(e.valueNumeric() != null ? e.valueNumeric().toPlainString() : "");
                    w.print(';');
                    w.print(e.valuePct() != null ? e.valuePct().toPlainString() : "");
                } else {
                    w.print(e.valueNumeric() != null
                            ? e.valueNumeric().toPlainString()
                            : csv(e.valueText()));
                    w.print(';');
                    w.print(csv(e.unit()));
                }
                w.print(';');
                w.print(csv(e.siteCode()));
                w.print(';');
                w.println(csv(e.siteName()));
            }
        }
    }

    private static String csv(String s) {
        if (s == null) return "";
        boolean needsQuotes = s.contains(";") || s.contains("\"") || s.contains("\n");
        if (!needsQuotes) return s;
        return '"' + s.replace("\"", "\"\"") + '"';
    }
}
