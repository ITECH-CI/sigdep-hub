package ci.itechciv.sigdep.hub.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Portée géographique d'un utilisateur zone-bound. Exactement un des trois IDs
 * (region/district/site) est non-null, selon le {@code user_level} du compte.
 * Mappe {@code auth.user_geo_scope}. Les IDs servent à peupler les claims
 * {@code regionId}/{@code districtId}/{@code siteId} du JWT (cf. AuthScope).
 */
@Entity
@Table(name = "user_geo_scope", schema = "auth")
public class UserGeoScope {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "region_id")
    private Long regionId;

    @Column(name = "district_id")
    private Long districtId;

    @Column(name = "site_id")
    private Long siteId;

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getRegionId() { return regionId; }
    public void setRegionId(Long regionId) { this.regionId = regionId; }
    public Long getDistrictId() { return districtId; }
    public void setDistrictId(Long districtId) { this.districtId = districtId; }
    public Long getSiteId() { return siteId; }
    public void setSiteId(Long siteId) { this.siteId = siteId; }
}
