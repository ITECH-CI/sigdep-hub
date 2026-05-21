import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  Bar, BarChart, LabelList, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts';
import { Baby, Download, Users } from 'lucide-react';
import {
  downloadPtmeChildCsv, downloadPtmeMotherCsv,
  fetchPtmeChildRecords, fetchPtmeChildSummary,
  fetchPtmeMotherRecords, fetchPtmeMotherSummary,
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

type Tab = 'mother' | 'child';

function formatDate(iso: string | null): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleDateString('fr-FR',
    { day: '2-digit', month: 'short', year: 'numeric' });
}

function resultBadge(r: string | null) {
  if (r === 'POS') return <span className="px-1.5 py-0.5 rounded text-[10px] font-medium bg-rose-50 text-rose-700">POS</span>;
  if (r === 'NEG') return <span className="px-1.5 py-0.5 rounded text-[10px] font-medium bg-emerald-50 text-emerald-700">NEG</span>;
  if (!r) return <span className="text-ink-muted">—</span>;
  return <span className="text-ink-muted">{r}</span>;
}

export function Ptme() {
  const [tab, setTab] = useState<Tab>('mother');
  const [months, setMonths] = useState(60);
  const [scope, setScope] = useState<GeoScope>({});

  return (
    <div className="px-6 py-6">
      <PageHeader
        icon={tab === 'mother' ? Users : Baby}
        title="PTME"
        subtitle="Prévention de la Transmission Mère-Enfant"
        right={<>
          <GeoFilter value={scope} onChange={setScope} />
          <select
            value={months}
            onChange={e => setMonths(Number(e.target.value))}
            className="rounded-md border border-slate-300 px-3 py-2 text-sm bg-white">
            {PERIODS.map(p => (
              <option key={p.months} value={p.months}>{p.label}</option>
            ))}
          </select>
        </>} />

      {/* Tabs */}
      <div className="mb-4 border-b border-slate-200 flex gap-1">
        <button
          onClick={() => setTab('mother')}
          className={`px-4 py-2 text-sm font-medium border-b-2 transition -mb-px ${
            tab === 'mother'
              ? 'border-sigdep-600 text-sigdep-700'
              : 'border-transparent text-ink-muted hover:text-ink'
          }`}>
          <Users className="inline-block h-4 w-4 mr-1.5 -mt-0.5" />
          Mère
        </button>
        <button
          onClick={() => setTab('child')}
          className={`px-4 py-2 text-sm font-medium border-b-2 transition -mb-px ${
            tab === 'child'
              ? 'border-sigdep-600 text-sigdep-700'
              : 'border-transparent text-ink-muted hover:text-ink'
          }`}>
          <Baby className="inline-block h-4 w-4 mr-1.5 -mt-0.5" />
          Enfant
        </button>
      </div>

      {tab === 'mother'
        ? <MotherPanel months={months} scope={scope} />
        : <ChildPanel months={months} scope={scope} />}
    </div>
  );
}

function MotherPanel({ months, scope }: { months: number; scope: GeoScope }) {
  const [sort, setSort] = useState<SortState>(null);
  const [page, setPage] = useState(0);
  const [exporting, setExporting] = useState(false);
  const size = 50;

  const summary = useQuery({
    queryKey: ['ptme-mother-summary', months, scope],
    queryFn: () => fetchPtmeMotherSummary(months, scope),
  });
  const records = useQuery({
    queryKey: ['ptme-mother-records', months, scope, sort, page],
    queryFn: () => fetchPtmeMotherRecords({ months, ...scope, sort, page, size }),
  });

  const onSort = (s: SortState) => { setSort(s); setPage(0); };
  const totalPages = records.data ? Math.max(1, Math.ceil(records.data.total / records.data.size)) : 1;

  async function handleExport() {
    setExporting(true);
    try { await downloadPtmeMotherCsv(months, scope); }
    catch (err) { /* eslint-disable-next-line no-console */ console.error(err); }
    finally { setExporting(false); }
  }

  return <>
    <div className="flex justify-end mb-3">
      <button
        onClick={handleExport}
        disabled={exporting || !records.data || records.data.total === 0}
        className="inline-flex items-center gap-1.5 rounded-md border border-slate-300 px-3 py-1.5 text-xs
                   hover:bg-slate-50 disabled:opacity-50 transition">
        <Download className="h-3.5 w-3.5" />
        {exporting ? 'Export…' : 'Exporter CSV (mères)'}
      </button>
    </div>

    {summary.isLoading ? <KpiRowSkeleton /> : (
      <div className="grid gap-3 grid-cols-2 lg:grid-cols-4 mb-6">
        <Kpi label="Femmes enceintes (cumul)"
             value={summary.isError ? 'Erreur' : formatInt(summary.data?.totalAllTime)}
             hint="Toutes périodes confondues"
             hintTone="neutral" />
        <Kpi label="Suivies (période)"
             value={summary.isError ? 'Erreur' : formatInt(summary.data?.inPeriod)}
             hint={PERIODS.find(p => p.months === months)?.label}
             hintTone="neutral" />
        <Kpi label="Conjoints positifs"
             value={summary.isError ? 'Erreur' : formatInt(summary.data?.spousalPositive)}
             hint="Dépistage conjoint POS"
             hintTone="warning" />
        <Kpi label="Couverture dépistage conjoint"
             value={summary.isError ? 'Erreur' : formatPercent(summary.data?.spousalCoveragePct ?? null)}
             hint="Conjoints dépistés / suivies"
             hintTone="positive" />
      </div>
    )}

    <div className="grid gap-3 lg:grid-cols-2 mb-6">
      <div className="card p-4 flex flex-col">
        <h3 className="text-sm font-medium mb-4">Femmes suivies par année</h3>
        <div className="flex-1 min-h-64">
          {summary.isLoading ? <ChartSkeleton height="h-64" />
            : !summary.data || summary.data.yearly.length === 0
              ? <div className="h-full flex items-center justify-center text-ink-muted text-sm">—</div>
              : (
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart data={summary.data.yearly} margin={{ top: 24, right: 16, left: 0, bottom: 8 }}>
                    <XAxis dataKey="year" tick={{ fontSize: 11 }} stroke="#94a3b8" />
                    <YAxis tick={{ fontSize: 11 }} stroke="#94a3b8" />
                    <Tooltip contentStyle={{ borderRadius: 6, fontSize: 12 }}
                             formatter={(v) => [formatInt(v as number), 'Mères']} />
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
        <DistributionTable title="Issue grossesse" rows={summary.data?.outcomes ?? []} />
        <DistributionTable title="Statut ARV à l'enregistrement" rows={summary.data?.arvAtRegistering ?? []} className="mt-5" />
      </div>
    </div>

    <div className="card overflow-hidden">
      <div className="px-4 py-3 bg-sigdep-50 border-b border-sigdep-100 flex items-center justify-between">
        <h3 className="text-sm font-semibold text-sigdep-800">Suivi des mères</h3>
        <span className="text-xs text-ink-muted">
          {records.data ? `${formatInt(records.data.total)} enregistrements` : '—'}
        </span>
      </div>
      <table className="w-full text-sm">
        <thead className="thead-sigdep text-left">
          <tr>
            <SortableTh k="date"    sort={sort} onSort={onSort}>Début</SortableTh>
            <SortableTh k="code"    sort={sort} onSort={onSort}>N° grossesse</SortableTh>
            <th className="px-4 py-2 font-medium">Âge</th>
            <SortableTh k="arv"     sort={sort} onSort={onSort}>Statut ARV</SortableTh>
            <th className="px-4 py-2 font-medium">DPA</th>
            <SortableTh k="outcome" sort={sort} onSort={onSort}>Issue</SortableTh>
            <th className="px-4 py-2 font-medium">Conjoint</th>
            <SortableTh k="site"    sort={sort} onSort={onSort}>Site</SortableTh>
          </tr>
        </thead>
        {records.isLoading ? <TableSkeleton rows={8} cols={8} /> : (
          <tbody className="divide-y divide-slate-100">
            {(() => {
              if (records.isError)
                return <tr><td colSpan={8} className="px-4 py-6 text-center text-rose-600">Erreur de chargement</td></tr>;
              if (!records.data || records.data.content.length === 0)
                return <tr><td colSpan={8} className="px-4 py-6 text-center text-ink-muted">Aucun enregistrement</td></tr>;
              return records.data.content.map(r => (
                <tr key={r.id} className="hover:bg-slate-50">
                  <td className="px-4 py-2 whitespace-nowrap text-ink-muted">{formatDate(r.startDate)}</td>
                  <td className="px-4 py-2 font-mono text-xs">{r.pregnantNumber ?? '—'}</td>
                  <td className="px-4 py-2 tabular-nums">{r.age ?? '—'}</td>
                  <td className="px-4 py-2">{r.arvStatusAtRegistering ?? '—'}</td>
                  <td className="px-4 py-2 whitespace-nowrap text-ink-muted">{formatDate(r.estimatedDeliveryDate)}</td>
                  <td className="px-4 py-2">{r.pregnancyOutcome ?? '—'}</td>
                  <td className="px-4 py-2">{resultBadge(r.spousalScreeningResult)}</td>
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
            <button onClick={() => setPage(p => Math.max(0, p - 1))} disabled={records.data.page === 0}
              className="px-3 py-1 rounded border border-slate-300 disabled:opacity-50 hover:bg-slate-50">
              Précédent
            </button>
            <button onClick={() => setPage(p => p + 1)} disabled={records.data.page + 1 >= totalPages}
              className="px-3 py-1 rounded border border-slate-300 disabled:opacity-50 hover:bg-slate-50">
              Suivant
            </button>
          </div>
        </div>
      )}
    </div>
  </>;
}

function ChildPanel({ months, scope }: { months: number; scope: GeoScope }) {
  const [sort, setSort] = useState<SortState>(null);
  const [page, setPage] = useState(0);
  const [exporting, setExporting] = useState(false);
  const size = 50;

  const summary = useQuery({
    queryKey: ['ptme-child-summary', months, scope],
    queryFn: () => fetchPtmeChildSummary(months, scope),
  });
  const records = useQuery({
    queryKey: ['ptme-child-records', months, scope, sort, page],
    queryFn: () => fetchPtmeChildRecords({ months, ...scope, sort, page, size }),
  });

  const onSort = (s: SortState) => { setSort(s); setPage(0); };
  const totalPages = records.data ? Math.max(1, Math.ceil(records.data.total / records.data.size)) : 1;

  async function handleExport() {
    setExporting(true);
    try { await downloadPtmeChildCsv(months, scope); }
    catch (err) { /* eslint-disable-next-line no-console */ console.error(err); }
    finally { setExporting(false); }
  }

  return <>
    <div className="flex justify-end mb-3">
      <button
        onClick={handleExport}
        disabled={exporting || !records.data || records.data.total === 0}
        className="inline-flex items-center gap-1.5 rounded-md border border-slate-300 px-3 py-1.5 text-xs
                   hover:bg-slate-50 disabled:opacity-50 transition">
        <Download className="h-3.5 w-3.5" />
        {exporting ? 'Export…' : 'Exporter CSV (enfants)'}
      </button>
    </div>

    {summary.isLoading ? <KpiRowSkeleton /> : (
      <div className="grid gap-3 grid-cols-2 lg:grid-cols-4 mb-6">
        <Kpi label="Enfants exposés (cumul)"
             value={summary.isError ? 'Erreur' : formatInt(summary.data?.totalAllTime)}
             hint="Toutes périodes confondues"
             hintTone="neutral" />
        <Kpi label="Nés (période)"
             value={summary.isError ? 'Erreur' : formatInt(summary.data?.inPeriod)}
             hint={PERIODS.find(p => p.months === months)?.label}
             hintTone="neutral" />
        <Kpi label="Positifs"
             value={summary.isError ? 'Erreur' : formatInt(summary.data?.anyPositive)}
             hint="PCR ou sérologie POS"
             hintTone="warning" />
        <Kpi label="Transmission"
             value={summary.isError ? 'Erreur' : formatPercent(summary.data?.positivityPct ?? null)}
             hint="Positifs / nés (période)"
             hintTone="warning" />
      </div>
    )}

    <div className="grid gap-3 lg:grid-cols-2 mb-6">
      <div className="card p-4 flex flex-col">
        <h3 className="text-sm font-medium mb-4">Enfants par année de naissance</h3>
        <div className="flex-1 min-h-64">
          {summary.isLoading ? <ChartSkeleton height="h-64" />
            : !summary.data || summary.data.yearly.length === 0
              ? <div className="h-full flex items-center justify-center text-ink-muted text-sm">—</div>
              : (
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart data={summary.data.yearly} margin={{ top: 24, right: 16, left: 0, bottom: 8 }}>
                    <XAxis dataKey="year" tick={{ fontSize: 11 }} stroke="#94a3b8" />
                    <YAxis tick={{ fontSize: 11 }} stroke="#94a3b8" />
                    <Tooltip contentStyle={{ borderRadius: 6, fontSize: 12 }}
                             formatter={(v) => [formatInt(v as number), 'Enfants']} />
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
        <DistributionTable title="Résultat du suivi" rows={summary.data?.followupResults ?? []} />
        <DistributionTable title="PCR1" rows={summary.data?.pcr1 ?? []} className="mt-5" />
      </div>
    </div>

    <div className="card overflow-hidden">
      <div className="px-4 py-3 bg-sigdep-50 border-b border-sigdep-100 flex items-center justify-between">
        <h3 className="text-sm font-semibold text-sigdep-800">Suivi des enfants</h3>
        <span className="text-xs text-ink-muted">
          {records.data ? `${formatInt(records.data.total)} enregistrements` : '—'}
        </span>
      </div>
      <table className="w-full text-sm">
        <thead className="thead-sigdep text-left">
          <tr>
            <SortableTh k="date"   sort={sort} onSort={onSort}>Naissance</SortableTh>
            <SortableTh k="code"   sort={sort} onSort={onSort}>Code suivi</SortableTh>
            <th className="px-4 py-2 font-medium">Sexe</th>
            <th className="px-4 py-2 font-medium">Prophylaxie</th>
            <th className="px-4 py-2 font-medium">PCR1</th>
            <th className="px-4 py-2 font-medium">PCR2</th>
            <th className="px-4 py-2 font-medium">PCR3</th>
            <th className="px-4 py-2 font-medium">Sérologie</th>
            <SortableTh k="result" sort={sort} onSort={onSort}>Résultat</SortableTh>
            <SortableTh k="site"   sort={sort} onSort={onSort}>Site</SortableTh>
          </tr>
        </thead>
        {records.isLoading ? <TableSkeleton rows={8} cols={10} /> : (
          <tbody className="divide-y divide-slate-100">
            {(() => {
              if (records.isError)
                return <tr><td colSpan={10} className="px-4 py-6 text-center text-rose-600">Erreur de chargement</td></tr>;
              if (!records.data || records.data.content.length === 0)
                return <tr><td colSpan={10} className="px-4 py-6 text-center text-ink-muted">Aucun enregistrement</td></tr>;
              return records.data.content.map(r => (
                <tr key={r.id} className="hover:bg-slate-50">
                  <td className="px-4 py-2 whitespace-nowrap text-ink-muted">{formatDate(r.birthDate)}</td>
                  <td className="px-4 py-2 font-mono text-xs">{r.childFollowupNumber ?? '—'}</td>
                  <td className="px-4 py-2">{r.gender ?? '—'}</td>
                  <td className="px-4 py-2 text-ink-muted">{r.arvProphylaxisGiven ?? '—'}</td>
                  <td className="px-4 py-2">{resultBadge(r.pcr1Result)}</td>
                  <td className="px-4 py-2">{resultBadge(r.pcr2Result)}</td>
                  <td className="px-4 py-2">{resultBadge(r.pcr3Result)}</td>
                  <td className="px-4 py-2">
                    {resultBadge(r.hivSerology1Result)}{' '}
                    {resultBadge(r.hivSerology2Result)}
                  </td>
                  <td className="px-4 py-2">{r.followupResult ?? '—'}</td>
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
            <button onClick={() => setPage(p => Math.max(0, p - 1))} disabled={records.data.page === 0}
              className="px-3 py-1 rounded border border-slate-300 disabled:opacity-50 hover:bg-slate-50">
              Précédent
            </button>
            <button onClick={() => setPage(p => p + 1)} disabled={records.data.page + 1 >= totalPages}
              className="px-3 py-1 rounded border border-slate-300 disabled:opacity-50 hover:bg-slate-50">
              Suivant
            </button>
          </div>
        </div>
      )}
    </div>
  </>;
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
          {rows.length === 0
            ? <tr><td colSpan={2} className="py-2 text-center text-ink-muted">—</td></tr>
            : rows.map(r => (
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
