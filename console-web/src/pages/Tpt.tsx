import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import {
  Bar, BarChart, LabelList, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts';
import { BookOpenCheck, Download } from 'lucide-react';
import {
  downloadTptCsv, fetchTptRecords, fetchTptSummary,
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
  { months: 120, label: '10 dernières années' },
];

function formatDate(iso: string | null): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleDateString('fr-FR',
    { day: '2-digit', month: 'short', year: 'numeric' });
}

export function Tpt() {
  const [months, setMonths] = useState(60);
  const [scope, setScope] = useState<GeoScope>({});
  const [sort, setSort] = useState<SortState>(null);
  const [page, setPage] = useState(0);
  const [exporting, setExporting] = useState(false);
  const size = 50;

  const summary = useQuery({
    queryKey: ['tpt-summary', months, scope],
    queryFn: () => fetchTptSummary(months, scope),
  });
  const records = useQuery({
    queryKey: ['tpt-records', months, scope, sort, page],
    queryFn: () => fetchTptRecords({ months, ...scope, sort, page, size }),
  });

  const onSort = (s: SortState) => { setSort(s); setPage(0); };

  const totalPages = records.data ? Math.max(1, Math.ceil(records.data.total / records.data.size)) : 1;

  async function handleExport() {
    setExporting(true);
    try { await downloadTptCsv(months, scope); }
    catch (err) { /* eslint-disable-next-line no-console */ console.error(err); }
    finally { setExporting(false); }
  }

  return (
    <div className="px-6 py-6">
      <PageHeader
        icon={BookOpenCheck}
        title="TPT"
        subtitle="Thérapie préventive de la tuberculose"
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
            disabled={exporting || !records.data || records.data.total === 0}
            className="inline-flex items-center gap-1.5 rounded-md border border-slate-300 px-3 py-1.5 text-xs
                       hover:bg-slate-50 disabled:opacity-50 transition">
            <Download className="h-3.5 w-3.5" />
            {exporting ? 'Export…' : 'Exporter CSV'}
          </button>
        </>} />

      {/* KPIs */}
      {summary.isLoading ? <KpiRowSkeleton /> : (
        <div className="grid gap-3 grid-cols-2 lg:grid-cols-4 mb-6">
          <Kpi label="TPT (cumul)"
               value={summary.isError ? 'Erreur' : formatInt(summary.data?.totalAllTime)}
               hint="Toutes périodes confondues"
               hintTone="neutral" />
          <Kpi label="TPT démarrés"
               value={summary.isError ? 'Erreur' : formatInt(summary.data?.startedInPeriod)}
               hint={`${PERIODS.find(p => p.months === months)?.label}`}
               hintTone="neutral" />
          <Kpi label="TPT terminés"
               value={summary.isError ? 'Erreur' : formatInt(summary.data?.completedInPeriod)}
               hint="Avec outcome documenté"
               hintTone="positive" />
          <Kpi label="Taux de complétion"
               value={summary.isError ? 'Erreur' : formatPercent(summary.data?.completionPct ?? null)}
               hint="Démarrés / terminés (période)"
               hintTone="positive" />
        </div>
      )}

      {/* Yearly chart + outcome distribution */}
      <div className="grid gap-3 lg:grid-cols-2 mb-6">
        <div className="card p-4 flex flex-col">
          <h3 className="text-sm font-medium mb-4">TPT démarrés par année</h3>
          <div className="flex-1 min-h-64">
            {summary.isLoading ? (
              <ChartSkeleton height="h-64" />
            ) : !summary.data || summary.data.yearly.length === 0 ? (
              <div className="h-full flex items-center justify-center text-ink-muted text-sm">—</div>
            ) : (
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={summary.data.yearly} margin={{ top: 24, right: 16, left: 0, bottom: 8 }}>
                  <XAxis dataKey="year" tick={{ fontSize: 11 }} stroke="#94a3b8" />
                  <YAxis tick={{ fontSize: 11 }} stroke="#94a3b8" />
                  <Tooltip contentStyle={{ borderRadius: 6, fontSize: 12 }}
                           formatter={(v) => [formatInt(v as number), 'TPT']} />
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
          <DistributionTable title="Statut TPT" rows={summary.data?.statuses ?? []} />
          <DistributionTable title="Protocole" rows={summary.data?.regimens ?? []} className="mt-5" />
          <DistributionTable title="Résultats (outcome)" rows={summary.data?.outcomes ?? []} className="mt-5" />
          <DistributionTable title="Observance" rows={summary.data?.adherence ?? []} className="mt-5" />
        </div>
      </div>

      {/* Records table */}
      <div className="card overflow-hidden">
        <div className="px-4 py-3 bg-sigdep-50 border-b border-sigdep-100 flex items-center justify-between">
          <h3 className="text-sm font-semibold text-sigdep-800">Enregistrements TPT</h3>
          <span className="text-xs text-ink-muted">
            {records.data ? `${formatInt(records.data.total)} enregistrements` : '—'}
          </span>
        </div>
        <table className="w-full text-sm">
          <thead className="thead-sigdep text-left">
            <tr className="text-left">
              <SortableTh k="date"       sort={sort} onSort={onSort}>Date</SortableTh>
              <SortableTh k="patient"    sort={sort} onSort={onSort}>Patient</SortableTh>
              <SortableTh k="tptStatus"  sort={sort} onSort={onSort}>Statut</SortableTh>
              <SortableTh k="tptRegimen" sort={sort} onSort={onSort}>Protocole</SortableTh>
              <th className="px-4 py-2 font-medium">Suivi</th>
              <th className="px-4 py-2 font-medium">Fin</th>
              <th className="px-4 py-2 font-medium">Résultat</th>
              <th className="px-4 py-2 font-medium">Observance</th>
              <SortableTh k="site"       sort={sort} onSort={onSort}>Site</SortableTh>
            </tr>
          </thead>
          {records.isLoading ? (
            <TableSkeleton rows={8} cols={9} />
          ) : (
          <tbody className="divide-y divide-slate-100">
            {(() => {
              if (records.isError) {
                return <tr><td colSpan={9} className="px-4 py-6 text-center text-rose-600">Erreur de chargement</td></tr>;
              }
              if (!records.data || records.data.content.length === 0) {
                return <tr><td colSpan={9} className="px-4 py-6 text-center text-ink-muted">Aucun enregistrement</td></tr>;
              }
              return records.data.content.map(r => (
                <tr key={r.id} className="hover:bg-slate-50">
                  <td className="px-4 py-2 whitespace-nowrap text-ink-muted">{formatDate(r.recordDate)}</td>
                  <td className="px-4 py-2">
                    <Link to={`/app/patients/${r.patientId}`}
                          className="text-sigdep-700 hover:underline font-mono text-xs">
                      {r.patientCode ?? `#${r.patientId}`}
                    </Link>
                  </td>
                  <td className="px-4 py-2">{r.tptStatus ?? '—'}</td>
                  <td className="px-4 py-2">{r.tptRegimen ?? '—'}</td>
                  <td className="px-4 py-2 whitespace-nowrap text-ink-muted">{formatDate(r.followupDate)}</td>
                  <td className="px-4 py-2 whitespace-nowrap text-ink-muted">{formatDate(r.endDate)}</td>
                  <td className="px-4 py-2">{r.outcome ?? '—'}</td>
                  <td className="px-4 py-2 text-ink-muted">{r.adherence ?? '—'}</td>
                  <td className="px-4 py-2 text-ink-muted">
                    <span className="font-mono text-xs">{r.siteCode}</span> {r.siteName}
                  </td>
                </tr>
              ));
            })()}
          </tbody>
          )}
        </table>

        {records.data && records.data.total > size && (
          <div className="px-4 py-3 flex items-center justify-between text-sm border-t border-slate-100">
            <p className="text-ink-muted">Page {records.data.page + 1} / {totalPages}</p>
            <div className="flex gap-2">
              <button
                onClick={() => setPage(p => Math.max(0, p - 1))}
                disabled={records.data.page === 0}
                className="px-3 py-1 rounded border border-slate-300 disabled:opacity-50 hover:bg-slate-50">
                Précédent
              </button>
              <button
                onClick={() => setPage(p => p + 1)}
                disabled={records.data.page + 1 >= totalPages}
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

function DistributionTable({
  title, rows, className,
}: {
  title: string;
  rows: { label: string; count: number }[];
  className?: string;
}) {
  return (
    <div className={className}>
      <h3 className="text-sm font-medium mb-3">{title}</h3>
      <table className="w-full text-sm">
        <thead className="text-ink-muted">
          <tr className="text-left">
            <th className="py-1 font-medium">Valeur</th>
            <th className="py-1 font-medium text-right">Nombre</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100">
          {rows.length === 0 ? (
            <tr><td colSpan={2} className="py-2 text-center text-ink-muted">—</td></tr>
          ) : rows.map(r => (
            <tr key={r.label}>
              <td className="py-1">{r.label}</td>
              <td className="py-1 text-right tabular-nums">{formatInt(r.count)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
