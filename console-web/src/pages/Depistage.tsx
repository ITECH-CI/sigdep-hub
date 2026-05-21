import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  Bar, BarChart, LabelList, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts';
import { Activity, Download } from 'lucide-react';
import {
  downloadScreeningCsv, fetchScreeningRecords, fetchScreeningSummary,
  type ScreeningSiteTypeStat,
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

function resultBadge(r: string | null) {
  if (r === 'POS') return <span className="px-2 py-0.5 rounded text-xs font-medium bg-rose-50 text-rose-700">POS</span>;
  if (r === 'NEG') return <span className="px-2 py-0.5 rounded text-xs font-medium bg-emerald-50 text-emerald-700">NEG</span>;
  if (r === 'IND') return <span className="px-2 py-0.5 rounded text-xs font-medium bg-amber-50 text-amber-700">IND</span>;
  return <span className="text-ink-muted">—</span>;
}

export function Depistage() {
  const [months, setMonths] = useState(60);
  const [scope, setScope] = useState<GeoScope>({});
  const [sort, setSort] = useState<SortState>(null);
  const [page, setPage] = useState(0);
  const [exporting, setExporting] = useState(false);
  const size = 50;

  const summary = useQuery({
    queryKey: ['screening-summary', months, scope],
    queryFn: () => fetchScreeningSummary(months, scope),
  });
  const records = useQuery({
    queryKey: ['screening-records', months, scope, sort, page],
    queryFn: () => fetchScreeningRecords({ months, ...scope, sort, page, size }),
  });

  const onSort = (s: SortState) => { setSort(s); setPage(0); };

  const totalPages = records.data ? Math.max(1, Math.ceil(records.data.total / records.data.size)) : 1;

  async function handleExport() {
    setExporting(true);
    try { await downloadScreeningCsv(months, scope); }
    catch (err) { /* eslint-disable-next-line no-console */ console.error(err); }
    finally { setExporting(false); }
  }

  return (
    <div className="px-6 py-6">
      <PageHeader
        icon={Activity}
        title="Dépistage"
        subtitle="Dépistage VIH (module HIV Screening)"
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
          <Kpi label="Dépistages (cumul)"
               value={summary.isError ? 'Erreur' : formatInt(summary.data?.totalAllTime)}
               hint="Toutes périodes confondues"
               hintTone="neutral" />
          <Kpi label="Dépistés"
               value={summary.isError ? 'Erreur' : formatInt(summary.data?.screenedInPeriod)}
               hint={`${PERIODS.find(p => p.months === months)?.label}`}
               hintTone="neutral" />
          <Kpi label="Positifs"
               value={summary.isError ? 'Erreur' : formatInt(summary.data?.positiveInPeriod)}
               hint="Résultat final = POS"
               hintTone="warning" />
          <Kpi label="Positivité"
               value={summary.isError ? 'Erreur' : formatPercent(summary.data?.positivityPct ?? null)}
               hint="Positifs / dépistés (période)"
               hintTone="warning" />
        </div>
      )}

      {/* Yearly chart + distributions */}
      <div className="grid gap-3 lg:grid-cols-2 mb-6">
        <div className="card p-4 flex flex-col">
          <h3 className="text-sm font-medium mb-4">Dépistages par année</h3>
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
                           formatter={(v) => [formatInt(v as number), 'Dépistages']} />
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
          <DistributionTable title="Résultat final" rows={summary.data?.results ?? []} />
          <DistributionTable title="Population" rows={summary.data?.populations ?? []} className="mt-5" />
          <DistributionTable title="Motif de dépistage" rows={summary.data?.reasons ?? []} className="mt-5" />
          <DistributionTable title="Sexe" rows={summary.data?.genders ?? []} className="mt-5" />
        </div>
      </div>

      {/* Porte d'entrée — point d'accès au dépistage, donnée structurante pour le programme */}
      <div className="card p-4 mb-6">
        <div className="flex items-baseline justify-between mb-3">
          <h3 className="text-sm font-semibold text-sigdep-800">Porte d'entrée</h3>
          <span className="text-xs text-ink-muted">Volume et positivité par point d'accès</span>
        </div>
        <PorteEntreeTable rows={summary.data?.siteTypes ?? []} loading={summary.isLoading} />
      </div>

      {/* Records table */}
      <div className="card overflow-hidden">
        <div className="px-4 py-3 bg-sigdep-50 border-b border-sigdep-100 flex items-center justify-between">
          <h3 className="text-sm font-semibold text-sigdep-800">Enregistrements de dépistage</h3>
          <span className="text-xs text-ink-muted">
            {records.data ? `${formatInt(records.data.total)} enregistrements` : '—'}
          </span>
        </div>
        <table className="w-full text-sm">
          <thead className="thead-sigdep text-left">
            <tr className="text-left">
              <SortableTh k="date"       sort={sort} onSort={onSort}>Date</SortableTh>
              <SortableTh k="code"       sort={sort} onSort={onSort}>Code</SortableTh>
              <th className="px-4 py-2 font-medium">Sexe</th>
              <th className="px-4 py-2 font-medium">Âge</th>
              <SortableTh k="population" sort={sort} onSort={onSort}>Population</SortableTh>
              <SortableTh k="reason"     sort={sort} onSort={onSort}>Motif</SortableTh>
              <SortableTh k="result"     sort={sort} onSort={onSort}>Résultat</SortableTh>
              <th className="px-4 py-2 font-medium">Retest</th>
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
                  <td className="px-4 py-2 whitespace-nowrap text-ink-muted">{formatDate(r.screeningDate)}</td>
                  <td className="px-4 py-2 font-mono text-xs">{r.screeningCode ?? '—'}</td>
                  <td className="px-4 py-2">{r.gender ?? '—'}</td>
                  <td className="px-4 py-2 tabular-nums">{r.age ?? '—'}</td>
                  <td className="px-4 py-2">{r.populationType ?? '—'}</td>
                  <td className="px-4 py-2">{r.screeningReason ?? '—'}</td>
                  <td className="px-4 py-2">{resultBadge(r.finalResult)}</td>
                  <td className="px-4 py-2 text-ink-muted">
                    {r.retesting == null ? '—' : Boolean(r.retesting) ? 'oui' : 'non'}
                  </td>
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

function PorteEntreeTable({
  rows, loading,
}: {
  rows: ScreeningSiteTypeStat[];
  loading: boolean;
}) {
  if (loading) {
    return <div className="h-32 animate-pulse bg-slate-100 rounded" />;
  }
  if (rows.length === 0) {
    return <div className="py-6 text-center text-ink-muted text-sm">Aucune donnée</div>;
  }
  const maxScreened = Math.max(...rows.map(r => r.screened), 1);
  const totalScreened = rows.reduce((sum, r) => sum + r.screened, 0);
  return (
    <table className="w-full text-sm">
      <thead className="text-ink-muted">
        <tr className="text-left">
          <th className="py-1 font-medium">Point d'accès</th>
          <th className="py-1 font-medium text-right">Dépistés</th>
          <th className="py-1 font-medium text-right">% Contribution</th>
          <th className="py-1 font-medium text-right">Positifs</th>
          <th className="py-1 font-medium text-right">% Positivité</th>
          <th className="py-1 font-medium w-1/4">Volume</th>
        </tr>
      </thead>
      <tbody className="divide-y divide-slate-100">
        {rows.map(r => {
          const widthPct = (r.screened / maxScreened) * 100;
          const contributionPct = totalScreened === 0 ? null
              : Math.round((r.screened / totalScreened) * 1000) / 10;
          return (
            <tr key={r.label}>
              <td className="py-2">{r.label}</td>
              <td className="py-2 text-right tabular-nums">{formatInt(r.screened)}</td>
              <td className="py-2 text-right tabular-nums text-ink-muted">{formatPercent(contributionPct)}</td>
              <td className="py-2 text-right tabular-nums text-rose-700">{formatInt(r.positive)}</td>
              <td className="py-2 text-right tabular-nums">{formatPercent(r.positivityPct)}</td>
              <td className="py-2 pl-2">
                <div className="h-2 bg-slate-100 rounded overflow-hidden">
                  <div className="h-full bg-sigdep-500 rounded" style={{ width: `${widthPct}%` }} />
                </div>
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}
