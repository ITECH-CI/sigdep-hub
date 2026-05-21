package ci.itechciv.sigdep.hub.console.controller;

import ci.itechciv.sigdep.hub.console.security.AuthScope;
import ci.itechciv.sigdep.hub.console.security.AuthScope.Scope;
import ci.itechciv.sigdep.hub.domain.service.KpiService;
import ci.itechciv.sigdep.hub.domain.service.KpiService.DashboardKpis;
import ci.itechciv.sigdep.hub.domain.service.KpiService.RegionBucket;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','IT_ADMIN','NATIONAL_VIEWER','REGIONAL_COORD','DISTRICT_COORD','SITE_USER','ANALYST','AUDITOR')")
public class DashboardKpiController {

    private final KpiService kpiService;
    private final AuthScope authScope;

    public DashboardKpiController(KpiService kpiService, AuthScope authScope) {
        this.kpiService = kpiService;
        this.authScope = authScope;
    }

    /**
     * Dashboard KPIs filtered by the effective geo scope (JWT ceiling
     * tightened by the optional UI cascade). National roles get the
     * country totals; a SITE_USER gets their site, a DISTRICT_COORD
     * gets their district, a REGIONAL_COORD gets their region.
     */
    @GetMapping("/kpis")
    public DashboardKpis dashboardKpis(
            @RequestParam(required = false) Long regionId,
            @RequestParam(required = false) Long districtId,
            @RequestParam(required = false) Long siteId) {
        Scope s = authScope.effective(regionId, districtId, siteId);
        return kpiService.dashboardKpis(s.regionId(), s.districtId(), s.siteId());
    }

    /**
     * Répartition géographique de la file active. Bornée par le scope
     * effectif : un SITE_USER ne verra que sa région, un REGIONAL_COORD
     * la sienne, un coordo national toutes les régions.
     */
    @GetMapping("/file-active-by-region")
    public List<RegionBucket> fileActiveByRegion(
            @RequestParam(required = false) Long regionId,
            @RequestParam(required = false) Long districtId,
            @RequestParam(required = false) Long siteId) {
        Scope s = authScope.effective(regionId, districtId, siteId);
        return kpiService.fileActiveByRegion(s.regionId(), s.districtId(), s.siteId());
    }
}
