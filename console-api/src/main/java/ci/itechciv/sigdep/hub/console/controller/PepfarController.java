package ci.itechciv.sigdep.hub.console.controller;

import ci.itechciv.sigdep.hub.console.security.AuthScope;
import ci.itechciv.sigdep.hub.console.security.AuthScope.Scope;
import ci.itechciv.sigdep.hub.domain.service.PepfarService;
import ci.itechciv.sigdep.hub.domain.service.PepfarService.DisaggCell;
import ci.itechciv.sigdep.hub.domain.service.PepfarService.PepfarReport;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pepfar")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','IT_ADMIN','NATIONAL_VIEWER','REGIONAL_COORD','DISTRICT_COORD','SITE_USER','ANALYST','AUDITOR')")
public class PepfarController {

    private final PepfarService service;
    private final AuthScope authScope;

    public PepfarController(PepfarService service, AuthScope authScope) {
        this.service = service;
        this.authScope = authScope;
    }

    @GetMapping("/report")
    public PepfarReport report(
            @RequestParam int fy,
            @RequestParam int q,
            @RequestParam(required = false) Long regionId,
            @RequestParam(required = false) Long districtId,
            @RequestParam(required = false) Long siteId) {
        Scope s = authScope.effective(regionId, districtId, siteId);
        return service.report(fy, q, s.regionId(), s.districtId(), s.siteId());
    }

    @GetMapping(value = "/report.csv", produces = "text/csv;charset=UTF-8")
    public void reportCsv(
            @RequestParam int fy,
            @RequestParam int q,
            @RequestParam(required = false) Long regionId,
            @RequestParam(required = false) Long districtId,
            @RequestParam(required = false) Long siteId,
            HttpServletResponse response) throws IOException {

        Scope s = authScope.effective(regionId, districtId, siteId);
        PepfarReport r = service.report(fy, q, s.regionId(), s.districtId(), s.siteId());

        String scopeSuffix;
        if (s.siteId() != null)          scopeSuffix = "-site" + s.siteId();
        else if (s.districtId() != null) scopeSuffix = "-district" + s.districtId();
        else if (s.regionId() != null)   scopeSuffix = "-region" + s.regionId();
        else                             scopeSuffix = "";
        String filename = "pepfar-FY" + fy + "Q" + q + scopeSuffix + ".csv";
        response.setContentType("text/csv;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");

        try (PrintWriter w = response.getWriter()) {
            w.write('﻿'); // BOM for Excel
            w.println("indicateur;sexe;tranche_age;valeur");
            LocalDate end = r.period().end();
            String period = "FY" + r.period().fiscalYear() + "Q" + r.period().quarter()
                    + " (au " + end + ")";

            writeRows(w, "TX_NEW", r.txNew().cells());
            writeTotal(w, "TX_NEW", r.txNew().total());

            writeRows(w, "TX_CURR", r.txCurr().cells());
            writeTotal(w, "TX_CURR", r.txCurr().total());

            writeRows(w, "TX_PVLS_D", r.txPvls().denominator().cells());
            writeTotal(w, "TX_PVLS_D", r.txPvls().denominator().total());

            writeRows(w, "TX_PVLS_N", r.txPvls().numerator().cells());
            writeTotal(w, "TX_PVLS_N", r.txPvls().numerator().total());

            if (r.txPvls().pct() != null) {
                w.print("TX_PVLS_pct;Total;Total;");
                w.println(r.txPvls().pct().toPlainString());
            }

            // HTS
            writeRows(w, "HTS_TST", r.hts().tst().cells());
            writeTotal(w, "HTS_TST", r.hts().tst().total());
            writeRows(w, "HTS_POS", r.hts().pos().cells());
            writeTotal(w, "HTS_POS", r.hts().pos().total());
            if (r.hts().positivityPct() != null) {
                w.print("HTS_POS_pct;Total;Total;");
                w.println(r.hts().positivityPct().toPlainString());
            }

            // PMTCT
            writePair(w, "PMTCT_STAT", r.pmtct().stat());
            writePair(w, "PMTCT_ART",  r.pmtct().art());
            writePair(w, "PMTCT_EID",  r.pmtct().eid());

            // TB_PREV
            writePair(w, "TB_PREV", r.tbPrev());

            w.println("# Période;" + period);
        }
    }

    /**
     * Write the denom/numer/pct triplet for an indicator shaped as
     * {@link ci.itechciv.sigdep.hub.domain.service.PepfarService.Pair}.
     * Uses suffixes _D (denominator), _N (numerator), _pct.
     */
    private static void writePair(PrintWriter w,
                                  String indicator,
                                  ci.itechciv.sigdep.hub.domain.service.PepfarService.Pair p) {
        writeRows(w, indicator + "_D", p.denominator().cells());
        writeTotal(w, indicator + "_D", p.denominator().total());
        writeRows(w, indicator + "_N", p.numerator().cells());
        writeTotal(w, indicator + "_N", p.numerator().total());
        if (p.pct() != null) {
            w.print(indicator);
            w.print("_pct;Total;Total;");
            w.println(p.pct().toPlainString());
        }
    }

    private static void writeRows(PrintWriter w, String indicator, java.util.List<DisaggCell> cells) {
        for (DisaggCell c : cells) {
            w.print(indicator);
            w.print(';');
            w.print(c.sex() == null ? "" : c.sex());
            w.print(';');
            w.print(c.ageBand() == null ? "" : c.ageBand());
            w.print(';');
            w.println(c.count());
        }
    }

    private static void writeTotal(PrintWriter w, String indicator, long total) {
        w.print(indicator);
        w.print(";Total;Total;");
        w.println(total);
    }
}
