import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import {
  Bar, BarChart, LabelList, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts';
import { Download, Pill } from 'lucide-react';
import {
  downloadPharmacyCsv, fetchPharmacyDispensations, fetchPharmacySummary,
} from '../api/client';
import { Kpi, formatInt, formatPercent } from '../components/Kpi';
import { PageHeader } from '../components/PageHeader';
import { GeoFilter, GeoScope } from '../components/GeoFilter';
import { SortableTh, SortState } from '../components/SortableTh';
import { ChartSkeleton, KpiRowSkeleton, TableSkeleton } from '../components/Skeleton';

const PERIODS = [
  { months: 12, label: '12 derniers mois' },
  { months: 24, label: '24 derniers mois' },
  { months: 60, label: '5 dernières années' },
];

function formatDate(iso: string | null): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleDateString('fr-FR',
    { day: '2-digit', month: 'short', year: 'numeric' });
}

export function Pharmacie() {
  const [months, setMonths] = useState(12);
  const [scope, setScope] = useState<GeoScope>({});
  const [sort, setSort] = useState<SortState>(null);
  const [page, setPage] = useState(0);
  const [exporting, setExporting] = useState(false);
  const size = 50;

  const summary = useQuery({
    queryKey: ['pharmacy-summary', months, scope],
    queryFn: () => fetchPharmacySummary(months, scope),
  });
  const dispensations = useQuery({
    queryKey: ['pharmacy-dispensations', months, scope, sort, page],
    queryFn: () => fetchPharmacyDispensations({ months, ...scope, sort, page, size }),
  });

  const onSort = (s: SortState) => { setSort(s); setPage(0); };

  const totalPages = dispensations.data
    ? Math.max(1, Math.ceil(dispensations.data.total / dispensations.data.size))
    : 1;

  async function handleExport() {
    setExporting(true);
    try { await downloadPharmacyCsv(months, scope); }
    catch (err) { /* eslint-disable-next-line no-console */ console.error(err); }
    finally { setExporting(false); }
  }

  const dur = summary.data?.durations;
  const durBars = dur && dur.total > 0 ? [
    { bucket: '1–7 j',   count: dur.d1_7 },
    { bucket: '8–30 j',  count: dur.d8_30 },
    { bucket: '31–90 j', count: dur.d31_90 },
    { bucket: '> 90 j',  count: dur.d90p },
  ] : [];

  return (
    <div className="px-6 py-6">
      <PageHeader
        icon={Pill}
        title="Pharmacie / ARV"
        subtitle="Dispensations ARV · régimes, durées, file pharmacie"
        right={<>
          <GeoFilter value={scope} onChange={s => { setScope(s); setPage(0); }} />
          <select
            value={months}
            onChange={e => { setMonths(Number(e.target.value)); setPage(0); }}
            className="rounded-md border border-slate-300 px-3 py-2 text-sm bg-white">
            {PERIODS.map(p => (
              <option key={p.months} value={p.months}>{p.label}</option>
            ))}
          </select>
          <button
            onClick={handleExport}
            disabled={exporting || !dispensations.data || dispensations.data.total === 0}
            className="inline-flex items-center gap-1.5 rounded-md border border-slate-300 px-3 py-1.5 text-xs
                       hover:bg-slate-50 disabled:opacity-50 transition">
            <Download className="h-3.5 w-3.5" />
            {exporting ? 'Export…' : 'Exporter CSV'}
          </button>
        </>} />

      {/* KPIs */}
      {summary.isLoading ? <KpiRowSkeleton /> : (
        <div className="grid gap-3 grid-cols-2 lg:grid-cols-4 mb-6">
          <Kpi label="Dispensations (période)"
               value={summary.isError ? 'Erreur' : formatInt(summary.data?.dispensationsInPeriod)}
               hint={`${PERIODS.find(p => p.months === months)?.label}`}
               hintTone="neutral" />
          <Kpi label="Patients sous ARV"
               value={summary.isError ? 'Erreur' : formatInt(summary.data?.patientsOnArvInPeriod)}
               hint="Distincts (période)"
               hintTone="neutral" />
          <Kpi label="Régimes différents"
               value={summary.isError ? 'Erreur' : formatInt(summary.data?.distinctRegimensInPeriod)}
               hint="Sur la période"
               hintTone="neutral" />
          <Kpi label="% < 30 jours"
               value={summary.isError ? 'Erreur' : formatPercent(summary.data?.shortDispensationPct ?? null)}
               hint="Dispensations courtes"
               hintTone="warning" />
        </div>
      )}

      {/* Two-column charts */}
      <div className="grid gap-3 lg:grid-cols-2 mb-6">
        <div className="card p-4">
          <h3 className="text-sm font-medium mb-4">Dispensations par mois</h3>
          <div className="h-56">
            {summary.isLoading ? (
              <ChartSkeleton height="h-56" />
            ) : !summary.data || summary.data.monthly.length === 0 ? (
              <div className="h-full flex items-center justify-center text-ink-muted text-sm">—</div>
            ) : (
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={summary.data.monthly} margin={{ top: 24, right: 8, left: 0, bottom: 0 }}>
                  <XAxis dataKey="month" tick={{ fontSize: 11 }} stroke="#94a3b8" />
                  <YAxis tick={{ fontSize: 11 }} stroke="#94a3b8" />
                  <Tooltip contentStyle={{ borderRadius: 6, fontSize: 12 }}
                           formatter={(v) => [formatInt(v as number), 'Dispensations']} />
                  <Bar dataKey="count" fill="#009d8e" radius={[3, 3, 0, 0]}>
                    <LabelList dataKey="count" position="top"
                               style={{ fill: '#475569', fontSize: 11, fontWeight: 500 }} />
                  </Bar>
                </BarChart>
              </ResponsiveContainer>
            )}
          </div>
        </div>

        <div className="card p-4">
          <h3 className="text-sm font-medium mb-1">Durée de dispensation</h3>
          <p className="text-xs text-ink-muted mb-3">
            {dur && dur.total > 0
              ? `${formatInt(dur.total)} dispensations${dur.unknown > 0 ? ` · ${formatInt(dur.unknown)} sans durée` : ''}`
              : 'Aucune dispensation'}
          </p>
          <div className="h-48">
            {durBars.length === 0 ? (
              <div className="h-full flex items-center justify-center text-ink-muted text-sm">—</div>
            ) : (
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={durBars} margin={{ top: 24, right: 8, left: 0, bottom: 0 }}>
                  <XAxis dataKey="bucket" tick={{ fontSize: 11 }} stroke="#94a3b8" />
                  <YAxis tick={{ fontSize: 11 }} stroke="#94a3b8" />
                  <Tooltip contentStyle={{ borderRadius: 6, fontSize: 12 }}
                           formatter={(v) => [formatInt(v as number), 'Dispensations']} />
                  <Bar dataKey="count" fill="#7c3aed" radius={[3, 3, 0, 0]}>
                    <LabelList dataKey="count" position="top"
                               style={{ fill: '#475569', fontSize: 11, fontWeight: 500 }} />
                  </Bar>
                </BarChart>
              </ResponsiveContainer>
            )}
          </div>
        </div>
      </div>

      {/* Top regimens */}
      <div className="card p-4 mb-6">
        <h3 className="text-sm font-medium mb-3">Top régimes ARV (période)</h3>
        <table className="w-full text-sm">
          <thead className="text-ink-muted">
            <tr className="text-left">
              <th className="py-1 font-medium">Régime</th>
              <th className="py-1 font-medium text-right">Dispensations</th>
              <th className="py-1 font-medium text-right">Patients</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {summary.data?.regimens.map(r => (
              <tr key={r.label}>
                <td className="py-1">{r.label}</td>
                <td className="py-1 text-right tabular-nums">{formatInt(r.count)}</td>
                <td className="py-1 text-right tabular-nums">{formatInt(r.patients)}</td>
              </tr>
            ))}
            {summary.data && summary.data.regimens.length === 0 && (
              <tr><td colSpan={3} className="py-2 text-center text-ink-muted">Aucun</td></tr>
            )}
          </tbody>
        </table>
      </div>

      {/* Dispensations table */}
      <div className="card overflow-hidden">
        <div className="px-4 py-3 bg-sigdep-50 border-b border-sigdep-100 flex items-center justify-between">
          <h3 className="text-sm font-semibold text-sigdep-800">Dispensations</h3>
          <span className="text-xs text-ink-muted">
            {dispensations.data ? `${formatInt(dispensations.data.total)} dispensations` : '—'}
          </span>
        </div>
        <table className="w-full text-sm">
          <thead className="thead-sigdep text-left">
            <tr className="text-left">
              <SortableTh k="date"       sort={sort} onSort={onSort}>Date</SortableTh>
              <SortableTh k="patient"    sort={sort} onSort={onSort}>Patient</SortableTh>
              <SortableTh k="arvRegimen" sort={sort} onSort={onSort}>Régime ARV</SortableTh>
              <SortableTh k="arvDays"    sort={sort} onSort={onSort} align="right">ARV (j)</SortableTh>
              <th className="px-4 py-2 font-medium text-right">CTX (j)</th>
              <th className="px-4 py-2 font-medium">Prochaine visite</th>
              <SortableTh k="site"       sort={sort} onSort={onSort}>Site</SortableTh>
            </tr>
          </thead>
          {dispensations.isLoading ? (
            <TableSkeleton rows={8} cols={7} />
          ) : (
          <tbody className="divide-y divide-slate-100">
            {(() => {
              if (dispensations.isError) {
                return <tr><td colSpan={7} className="px-4 py-6 text-center text-rose-600">Erreur de chargement</td></tr>;
              }
              if (!dispensations.data || dispensations.data.content.length === 0) {
                return <tr><td colSpan={7} className="px-4 py-6 text-center text-ink-muted">Aucune dispensation</td></tr>;
              }
              return dispensations.data.content.map(d => (
                <tr key={d.id} className="hover:bg-slate-50">
                  <td className="px-4 py-2 whitespace-nowrap text-ink-muted">{formatDate(d.visitDate)}</td>
                  <td className="px-4 py-2">
                    <Link to={`/app/patients/${d.patientId}`}
                          className="text-sigdep-700 hover:underline font-mono text-xs">
                      {d.patientCode ?? `#${d.patientId}`}
                    </Link>
                  </td>
                  <td className="px-4 py-2">{d.arvRegimen ?? '—'}</td>
                  <td className="px-4 py-2 text-right tabular-nums">{d.arvDays ?? '—'}</td>
                  <td className="px-4 py-2 text-right tabular-nums">{d.cotrimDays ?? '—'}</td>
                  <td className="px-4 py-2 whitespace-nowrap text-ink-muted">{formatDate(d.nextVisitDate)}</td>
                  <td className="px-4 py-2 text-ink-muted">
                    <span className="font-mono text-xs">{d.siteCode}</span> {d.siteName}
                  </td>
                </tr>
              ));
            })()}
          </tbody>
          )}
        </table>

        {dispensations.data && dispensations.data.total > size && (
          <div className="px-4 py-3 flex items-center justify-between text-sm border-t border-slate-100">
            <p className="text-ink-muted">Page {dispensations.data.page + 1} / {totalPages}</p>
            <div className="flex gap-2">
              <button
                onClick={() => setPage(p => Math.max(0, p - 1))}
                disabled={dispensations.data.page === 0}
                className="px-3 py-1 rounded border border-slate-300 disabled:opacity-50 hover:bg-slate-50">
                Précédent
              </button>
              <button
                onClick={() => setPage(p => p + 1)}
                disabled={dispensations.data.page + 1 >= totalPages}
                className="px-3 py-1 rounded border border-slate-300 disabled:opacity-50 hover:bg-slate-50">
                Suivant
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
