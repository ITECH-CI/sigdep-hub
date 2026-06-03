import { useQuery } from '@tanstack/react-query';
import { fetchDistricts, fetchRegions, fetchSitesOf } from '../api/client';
import { Combobox } from './Combobox';

export type GeoScope = {
  regionId?: number;
  districtId?: number;
  siteId?: number;
};

/**
 * Three cascading <select>s — region → district → site. Empty option means
 * "all" at that level. Choosing a parent resets the children.
 *
 * The component is uncontrolled-style: it lifts the scope through the
 * `value` + `onChange` props, so pages can plug it in their useState/
 * useQuery flow without copy/pasting the cascade logic.
 */
export function GeoFilter({
  value,
  onChange,
}: Readonly<{ value: GeoScope; onChange: (next: GeoScope) => void }>) {
  const regions = useQuery({ queryKey: ['regions'], queryFn: fetchRegions });

  const districts = useQuery({
    queryKey: ['districts', value.regionId],
    queryFn: () => fetchDistricts(value.regionId),
    enabled: value.regionId != null,
  });

  // Sites need either a region or a district to avoid loading the full 3,880.
  const sites = useQuery({
    queryKey: ['sitesOf', value.regionId, value.districtId],
    queryFn: () => fetchSitesOf(value.regionId, value.districtId),
    enabled: value.regionId != null || value.districtId != null,
  });

  return (
    <>
      <Combobox
        className="w-44"
        options={(regions.data ?? []).map((r) => ({ value: r.id, label: r.name }))}
        value={value.regionId ?? null}
        placeholder="Toutes les régions"
        onChange={(v) => onChange({ regionId: v ?? undefined })}
      />

      <Combobox
        className="w-52"
        options={(districts.data ?? []).map((d) => ({ value: d.id, label: d.name }))}
        value={value.districtId ?? null}
        disabled={value.regionId == null}
        placeholder={value.regionId == null ? 'District (région d’abord)' : 'Tous les districts'}
        onChange={(v) => onChange({ regionId: value.regionId, districtId: v ?? undefined })}
      />

      <Combobox
        className="w-64"
        options={(sites.data ?? []).map((s) => ({ value: s.id, label: `${s.code} — ${s.name}` }))}
        value={value.siteId ?? null}
        disabled={value.regionId == null && value.districtId == null}
        placeholder={
          value.regionId == null && value.districtId == null
            ? 'Site (région ou district d’abord)'
            : 'Tous les sites'
        }
        onChange={(v) =>
          onChange({ regionId: value.regionId, districtId: value.districtId, siteId: v ?? undefined })
        }
      />
    </>
  );
}
