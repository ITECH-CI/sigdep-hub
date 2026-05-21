import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import {
  Bar, BarChart, LabelList, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts';
import {
  BatchRow, LateBucket, LateSiteRow, RejectBucket, RejectRow,
  fetchSyncBatches, fetchSyncDaily, fetchSyncLateSites,
  fetchSyncRejects, fetchSyncRejectsOpenCounts, fetchSyncSummary,
  resolveSyncReject,
} from '../api/client';
import { CheckCircle2, Copy, RefreshCcw, X } from 'lucide-react';
import { Kpi, formatInt } from '../components/Kpi';
import { PageHeader } from '../components/PageHeader';
import { GeoFilter, GeoScope } from '../components/GeoFilter';
import { SortableTh, SortState } from '../components/SortableTh';
import { StatusBadge } from '../components/StatusBadge';
import { ChartSkeleton, KpiRowSkeleton, TableSkeleton } from '../components/Skeleton';

const ENTITY_OPTIONS: { value: string; label: string }[] = [
  { value: '',                       label: 'Toutes entités' },
  { value: 'patients',               label: 'Patients' },
  { value: 'visits',                 label: 'Visites' },
  { value: 'treatment_initiations',  label: 'Initiations ARV' },
  { value: 'closures',               label: 'Clôtures' },
  { value: 'lab_results',            label: 'Examens biologiques' },
  { value: 'tpt_records',            label: 'TPT' },
  { value: 'dispensations',          label: 'Dispensations' },
];

const STATUS_OPTIONS: { value: string; label: string }[] = [
  { value: '',         label: 'Tous statuts' },
  { value: 'ok',       label: 'OK' },
  { value: 'partial',  label: 'Partiel (rejets)' },
  { value: 'failed',   label: 'Échec' },
];

const BUCKETS: { value: LateBucket; label: string }[] = [
  { value: 'all',     label: 'Tous (en retard ou jamais)' },
  { value: 'late',    label: 'En retard (24h–7j)' },
  { value: 'offline', label: 'Hors ligne (> 7j)' },
  { value: 'never',   label: 'Jamais' },
];

function formatTime(iso: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return d.toLocaleString('fr-FR', {
    day: '2-digit', month: 'short', year: 'numeric',
    hour: '2-digit', minute: '2-digit',
  });
}

function formatDuration(ms: number | null): string {
  if (ms == null) return '—';
  if (ms < 1000) return `${ms} ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)} s`;
  return `${Math.floor(ms / 60_000)} min ${Math.floor((ms % 60_000) / 1000)} s`;
}

function statusBadge(s: string): { label: string; tone: string } {
  switch (s) {
    case 'ok':      return { label: 'OK',      tone: 'bg-emerald-50 text-emerald-700' };
    case 'partial': return { label: 'Partiel', tone: 'bg-amber-50 text-amber-700' };
    case 'failed':  return { label: 'Échec',   tone: 'bg-rose-50 text-rose-700' };
    default:        return { label: s,         tone: 'bg-slate-100 text-slate-600' };
  }
}

function relativeAge(iso: string | null): string {
  if (!iso) return 'Jamais';
  const ageMs = Date.now() - new Date(iso).getTime();
  const ageH = ageMs / 36e5;
  if (ageH < 1) return 'À l’instant';
  if (ageH < 24) return `il y a ${Math.floor(ageH)} h`;
  return `il y a ${Math.floor(ageH / 24)} j`;
}

type SyncTab = 'batches' | 'late' | 'rejects';

export function Synchronisation() {
  const [scope, setScope] = useState<GeoScope>({});
  const [tab, setTab] = useState<SyncTab>('batches');

  const summary = useQuery({
    queryKey: ['sync-summary', scope],
    queryFn: () => fetchSyncSummary(scope),
  });

  const daily = useQuery({
    queryKey: ['sync-daily', 30, scope],
    queryFn: () => fetchSyncDaily(30, scope),
  });

  // Open-rejects counter for the tab badge.
  const rejectsCounts = useQuery({
    queryKey: ['sync-rejects-counts', scope],
    queryFn: () => fetchSyncRejectsOpenCounts(scope),
  });
  const openRejectsTotal = (rejectsCounts.data ?? [])
      .reduce((s, e) => s + Number(e.count), 0);

  return (
    <div className="px-6 py-6">
      <PageHeader
        icon={RefreshCcw}
        tone="admin"
        title="Synchronisation"
        subtitle={summary.data
          ? `${formatInt(summary.data.sitesTotal)} sites · dernier batch ${relativeAge(summary.data.lastBatchAt)}`
          : 'Chargement…'}
        right={<GeoFilter value={scope} onChange={setScope} />} />

      {/* KPIs */}
      {summary.isLoading ? <KpiRowSkeleton /> : (
        <div className="grid gap-3 grid-cols-2 lg:grid-cols-4 mb-6">
          <Kpi label="Sites en ligne (< 24h)"
               value={summary.isError ? 'Erreur' : formatInt(summary.data?.sitesOnline)}
               hint={summary.data ? `sur ${formatInt(summary.data.sitesTotal)}` : ''}
               hintTone="positive" />
          <Kpi label="En retard (24h–7j)"
               value={summary.isError ? 'Erreur' : formatInt(summary.data?.sitesLate)}
               hint="à relancer rapidement"
               hintTone="warning" />
          <Kpi label="Hors ligne (> 7j)"
               value={summary.isError ? 'Erreur' : formatInt(summary.data?.sitesOffline)}
               hint="incluant 'jamais'"
               hintTone="warning" />
          <Kpi label="Batches reçus (24h)"
               value={summary.isError ? 'Erreur' : formatInt(summary.data?.batches24h)}
               hint={summary.data ? `${formatInt(summary.data.accepted24h)} acceptés · ${formatInt(summary.data.rejected24h)} rejetés` : ''}
               hintTone="neutral" />
        </div>
      )}

      {/* Daily volume chart */}
      <div className="card p-4 mb-6">
        <h3 className="text-sm font-medium mb-4">Batches reçus par jour (30j)</h3>
        <div className="h-48">
          {daily.isLoading ? (
            <ChartSkeleton height="h-48" />
          ) : !daily.data || daily.data.length === 0 ? (
            <div className="h-full flex items-center justify-center text-ink-muted text-sm">—</div>
          ) : (
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={daily.data} margin={{ top: 24, right: 8, left: 0, bottom: 0 }}>
                <XAxis dataKey="day" tick={{ fontSize: 10 }} stroke="#94a3b8"
                       tickFormatter={(d: string) => d.substring(8)} />
                <YAxis tick={{ fontSize: 11 }} stroke="#94a3b8" />
                <Tooltip
                  contentStyle={{ borderRadius: 6, fontSize: 12 }}
                  formatter={(v) => [formatInt(v as number), 'Batches']}
                />
                <Bar dataKey="batches" fill="#009d8e" radius={[3, 3, 0, 0]}>
                  <LabelList dataKey="batches" position="top"
                             style={{ fill: '#475569', fontSize: 10, fontWeight: 500 }} />
                </Bar>
              </BarChart>
            </ResponsiveContainer>
          )}
        </div>
      </div>

      {/* Tabs */}
      <div className="flex gap-1 mb-4 border-b border-slate-200">
        <TabButton current={tab} value="batches" onSelect={setTab}>Batches récents</TabButton>
        <TabButton current={tab} value="late"    onSelect={setTab}>Sites en retard</TabButton>
        <TabButton current={tab} value="rejects" onSelect={setTab}>
          Rejets
          {openRejectsTotal > 0 && (
            <span className="ml-1.5 inline-flex items-center rounded-full bg-rose-100 text-rose-700
                             text-[10px] font-semibold px-1.5 py-0.5">
              {formatInt(openRejectsTotal)}
            </span>
          )}
        </TabButton>
      </div>

      {tab === 'batches' && <BatchesTable scope={scope} />}
      {tab === 'late'    && <LateSitesTable scope={scope} />}
      {tab === 'rejects' && <RejectsTable scope={scope} />}
    </div>
  );
}

function TabButton({ current, value, onSelect, children }:
    Readonly<{ current: SyncTab; value: SyncTab; onSelect: (v: SyncTab) => void; children: React.ReactNode }>) {
  const active = current === value;
  return (
    <button
      onClick={() => onSelect(value)}
      className={`px-3 py-2 text-sm border-b-2 transition ${
        active
          ? 'border-accent-500 text-accent-700 font-medium'
          : 'border-transparent text-ink-muted hover:text-ink'
      }`}>
      {children}
    </button>
  );
}

function BatchesTable({ scope }: Readonly<{ scope: GeoScope }>) {
  const [entityType, setEntityType] = useState('');
  const [status, setStatus] = useState('');
  const [sort, setSort] = useState<SortState>(null);
  const [page, setPage] = useState(0);
  const size = 50;

  const batches = useQuery({
    queryKey: ['sync-batches', scope, entityType, status, sort, page],
    queryFn: () => fetchSyncBatches({
      ...scope, entityType: entityType || undefined,
      status: status || undefined, sort, page, size,
    }),
  });

  const onSort = (s: SortState) => { setSort(s); setPage(0); };
  const totalPages = batches.data
    ? Math.max(1, Math.ceil(batches.data.total / batches.data.size)) : 1;

  return (
    <>
      <div className="flex items-center gap-3 mb-3">
        <select value={entityType} onChange={e => { setEntityType(e.target.value); setPage(0); }}
                className="rounded-md border border-slate-300 px-3 py-2 text-sm bg-white">
          {ENTITY_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
        </select>
        <select value={status} onChange={e => { setStatus(e.target.value); setPage(0); }}
                className="rounded-md border border-slate-300 px-3 py-2 text-sm bg-white">
          {STATUS_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
        </select>
        <span className="text-xs text-ink-muted ml-auto">
          {batches.data ? `${formatInt(batches.data.total)} batches` : '—'}
        </span>
      </div>

      <div className="card overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="thead-sigdep text-left">
              <tr className="text-left">
                <SortableTh k="finishedAt" sort={sort} onSort={onSort}>Reçu</SortableTh>
                <SortableTh k="site"       sort={sort} onSort={onSort}>Site</SortableTh>
                <SortableTh k="entityType" sort={sort} onSort={onSort}>Entité</SortableTh>
                <SortableTh k="received"   sort={sort} onSort={onSort} align="right">Reçus</SortableTh>
                <SortableTh k="accepted"   sort={sort} onSort={onSort} align="right">Acceptés</SortableTh>
                <SortableTh k="rejected"   sort={sort} onSort={onSort} align="right">Rejetés</SortableTh>
                <SortableTh k="durationMs" sort={sort} onSort={onSort} align="right">Durée</SortableTh>
                <SortableTh k="status"     sort={sort} onSort={onSort}>Statut</SortableTh>
              </tr>
            </thead>
            {batches.isLoading ? (
              <TableSkeleton rows={8} cols={8} />
            ) : (
            <tbody className="divide-y divide-slate-100">
              {batches.isError ? (
                <tr><td colSpan={8} className="px-4 py-6 text-center text-rose-600">Erreur de chargement</td></tr>
              ) : batches.data?.content.length === 0 ? (
                <tr><td colSpan={8} className="px-4 py-6 text-center text-ink-muted">Aucun batch</td></tr>
              ) : batches.data?.content.map(b => <BatchRowItem key={b.id} b={b} />)}
            </tbody>
            )}
          </table>
        </div>

        {batches.data && batches.data.total > size && (
          <div className="px-4 py-3 flex items-center justify-between text-sm border-t border-slate-100">
            <p className="text-ink-muted">Page {batches.data.page + 1} / {totalPages}</p>
            <div className="flex gap-2">
              <button onClick={() => setPage(p => Math.max(0, p - 1))}
                      disabled={batches.data.page === 0}
                      className="px-3 py-1 rounded border border-slate-300 disabled:opacity-50 hover:bg-slate-50">
                Précédent
              </button>
              <button onClick={() => setPage(p => p + 1)}
                      disabled={batches.data.page + 1 >= totalPages}
                      className="px-3 py-1 rounded border border-slate-300 disabled:opacity-50 hover:bg-slate-50">
                Suivant
              </button>
            </div>
          </div>
        )}
      </div>
    </>
  );
}

function BatchRowItem({ b }: Readonly<{ b: BatchRow }>) {
  const [open, setOpen] = useState(false);
  const sb = statusBadge(b.status);
  const errors = parseErrorSample(b.errorSample);
  const code = b.siteResolvedCode ?? b.siteCode ?? '—';

  return (
    <>
      <tr className="hover:bg-slate-50">
        <td className="px-4 py-2 whitespace-nowrap text-ink-muted">
          {formatTime(b.finishedAt ?? b.startedAt)}
        </td>
        <td className="px-4 py-2 text-ink-muted">
          <span className="font-mono text-xs">{code}</span>
          {b.siteName && <span className="ml-1">{b.siteName}</span>}
        </td>
        <td className="px-4 py-2 text-ink-muted">{b.entityType}</td>
        <td className="px-4 py-2 text-right tabular-nums">{formatInt(b.receivedCount)}</td>
        <td className="px-4 py-2 text-right tabular-nums text-emerald-700">{formatInt(b.accepted)}</td>
        <td className="px-4 py-2 text-right tabular-nums text-rose-600">{formatInt(b.rejected)}</td>
        <td className="px-4 py-2 text-right tabular-nums text-ink-muted">{formatDuration(b.durationMs)}</td>
        <td className="px-4 py-2">
          <button
            onClick={() => errors.length > 0 && setOpen(o => !o)}
            disabled={errors.length === 0}
            className={`text-xs px-2 py-0.5 rounded ${sb.tone} ${errors.length > 0 ? 'cursor-pointer hover:underline' : 'cursor-default'}`}>
            {sb.label}{errors.length > 0 ? ` · ${errors.length}` : ''}
          </button>
        </td>
      </tr>
      {open && errors.length > 0 && (
        <tr className="bg-amber-50/40">
          <td colSpan={8} className="px-4 py-2 text-xs">
            <div className="text-ink-muted mb-1">Échantillon d'erreurs :</div>
            <ul className="list-disc list-inside space-y-0.5">
              {errors.map((e, i) => (
                <li key={i}><span className="font-medium">{e.label}</span> <span className="text-ink-muted">×{formatInt(e.count)}</span></li>
              ))}
            </ul>
          </td>
        </tr>
      )}
    </>
  );
}

function parseErrorSample(json: string | null): { label: string; count: number }[] {
  if (!json) return [];
  try {
    const v = JSON.parse(json);
    if (Array.isArray(v)) return v as { label: string; count: number }[];
    return [];
  } catch {
    return [];
  }
}

function LateSitesTable({ scope }: Readonly<{ scope: GeoScope }>) {
  const [bucket, setBucket] = useState<LateBucket>('all');
  const [sort, setSort] = useState<SortState>(null);
  const [page, setPage] = useState(0);
  const size = 50;

  const lateSites = useQuery({
    queryKey: ['sync-late-sites', bucket, scope, sort, page],
    queryFn: () => fetchSyncLateSites({ bucket, ...scope, sort, page, size }),
  });

  const onSort = (s: SortState) => { setSort(s); setPage(0); };
  const totalPages = lateSites.data
    ? Math.max(1, Math.ceil(lateSites.data.total / lateSites.data.size)) : 1;

  return (
    <>
      <div className="flex items-center gap-3 mb-3">
        <select value={bucket} onChange={e => { setBucket(e.target.value as LateBucket); setPage(0); }}
                className="rounded-md border border-slate-300 px-3 py-2 text-sm bg-white">
          {BUCKETS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
        </select>
        <span className="text-xs text-ink-muted ml-auto">
          {lateSites.data ? `${formatInt(lateSites.data.total)} sites` : '—'}
        </span>
      </div>

      <div className="card overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="thead-sigdep text-left">
              <tr className="text-left">
                <SortableTh k="code"       sort={sort} onSort={onSort}>Code</SortableTh>
                <SortableTh k="name"       sort={sort} onSort={onSort}>Nom</SortableTh>
                <SortableTh k="region"     sort={sort} onSort={onSort}>Région</SortableTh>
                <SortableTh k="district"   sort={sort} onSort={onSort}>District</SortableTh>
                <SortableTh k="lastSyncAt" sort={sort} onSort={onSort}>Dernier sync</SortableTh>
                <th className="px-4 py-2 font-medium text-right">Patients</th>
              </tr>
            </thead>
            {lateSites.isLoading ? (
              <TableSkeleton rows={8} cols={6} />
            ) : (
            <tbody className="divide-y divide-slate-100">
              {lateSites.isError ? (
                <tr><td colSpan={6} className="px-4 py-6 text-center text-rose-600">Erreur de chargement</td></tr>
              ) : lateSites.data?.content.length === 0 ? (
                <tr><td colSpan={6} className="px-4 py-6 text-center text-ink-muted">Aucun site dans ce bucket</td></tr>
              ) : lateSites.data?.content.map((s: LateSiteRow) => (
                <tr key={s.id} className="hover:bg-slate-50">
                  <td className="px-4 py-2 font-mono text-xs">
                    <Link to={`/app/sites?q=${s.code}`} className="text-sigdep-700 hover:underline">{s.code}</Link>
                  </td>
                  <td className="px-4 py-2">{s.name}</td>
                  <td className="px-4 py-2 text-ink-muted">{s.regionName}</td>
                  <td className="px-4 py-2 text-ink-muted">{s.districtName}</td>
                  <td className="px-4 py-2 text-ink-muted">
                    {s.lastSyncAt
                      ? <><span>{formatTime(s.lastSyncAt)}</span> <span className="text-xs">({relativeAge(s.lastSyncAt)})</span></>
                      : <span className="text-rose-600">Jamais</span>}
                  </td>
                  <td className="px-4 py-2 text-right tabular-nums">{formatInt(s.patientCount)}</td>
                </tr>
              ))}
            </tbody>
            )}
          </table>
        </div>

        {lateSites.data && lateSites.data.total > size && (
          <div className="px-4 py-3 flex items-center justify-between text-sm border-t border-slate-100">
            <p className="text-ink-muted">Page {lateSites.data.page + 1} / {totalPages}</p>
            <div className="flex gap-2">
              <button onClick={() => setPage(p => Math.max(0, p - 1))}
                      disabled={lateSites.data.page === 0}
                      className="px-3 py-1 rounded border border-slate-300 disabled:opacity-50 hover:bg-slate-50">
                Précédent
              </button>
              <button onClick={() => setPage(p => p + 1)}
                      disabled={lateSites.data.page + 1 >= totalPages}
                      className="px-3 py-1 rounded border border-slate-300 disabled:opacity-50 hover:bg-slate-50">
                Suivant
              </button>
            </div>
          </div>
        )}
      </div>
    </>
  );
}

// ---------- Rejets tab -----------------------------------------------------

const REJECT_BUCKETS: { value: RejectBucket; label: string }[] = [
  { value: 'open',     label: 'Ouverts' },
  { value: 'resolved', label: 'Résolus' },
  { value: 'all',      label: 'Tous' },
];

function RejectsTable({ scope }: Readonly<{ scope: GeoScope }>) {
  const [bucket, setBucket] = useState<RejectBucket>('open');
  const [entityType, setEntityType] = useState('');
  const [sort, setSort] = useState<SortState>(null);
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<RejectRow | null>(null);
  const [toResolve, setToResolve] = useState<RejectRow | null>(null);
  const size = 50;

  const rejects = useQuery({
    queryKey: ['sync-rejects', scope, bucket, entityType, sort, page],
    queryFn: () => fetchSyncRejects({
      ...scope, bucket, entityType: entityType || undefined,
      sort, page, size,
    }),
  });

  const onSort = (s: SortState) => { setSort(s); setPage(0); };
  const totalPages = rejects.data
    ? Math.max(1, Math.ceil(rejects.data.total / rejects.data.size)) : 1;

  return (
    <>
      <div className="flex items-center gap-3 mb-3">
        <select value={bucket} onChange={e => { setBucket(e.target.value as RejectBucket); setPage(0); }}
                className="rounded-md border border-slate-300 px-3 py-2 text-sm bg-white">
          {REJECT_BUCKETS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
        </select>
        <select value={entityType} onChange={e => { setEntityType(e.target.value); setPage(0); }}
                className="rounded-md border border-slate-300 px-3 py-2 text-sm bg-white">
          {ENTITY_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
        </select>
        <span className="text-xs text-ink-muted ml-auto">
          {rejects.data ? `${formatInt(rejects.data.total)} rejets` : '—'}
        </span>
      </div>

      <div className="card overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="thead-sigdep text-left">
              <tr className="text-left">
                <SortableTh k="rejectedAt" sort={sort} onSort={onSort}>Rejeté le</SortableTh>
                <SortableTh k="site"       sort={sort} onSort={onSort}>Site</SortableTh>
                <SortableTh k="entityType" sort={sort} onSort={onSort}>Entité</SortableTh>
                <th className="px-4 py-2 font-medium">Source UUID</th>
                <SortableTh k="code"       sort={sort} onSort={onSort}>Code</SortableTh>
                <th className="px-4 py-2 font-medium">Message</th>
                <th className="px-4 py-2 font-medium">Statut</th>
                <th className="px-4 py-2 font-medium text-right">Action</th>
              </tr>
            </thead>
            {rejects.isLoading ? (
              <TableSkeleton rows={8} cols={8} />
            ) : (
            <tbody className="divide-y divide-slate-100">
              {rejects.isError ? (
                <tr><td colSpan={8} className="px-4 py-6 text-center text-rose-600">Erreur de chargement</td></tr>
              ) : rejects.data?.content.length === 0 ? (
                <tr><td colSpan={8} className="px-4 py-6 text-center text-ink-muted">Aucun rejet</td></tr>
              ) : rejects.data?.content.map(r => (
                <tr key={r.id} className="hover:bg-slate-50">
                  <td className="px-4 py-2 whitespace-nowrap text-ink-muted">{formatTime(r.rejectedAt)}</td>
                  <td className="px-4 py-2 text-ink-muted">
                    <span className="font-mono text-xs">{r.siteCode ?? '—'}</span>
                    {r.siteName && <span className="ml-1">{r.siteName}</span>}
                  </td>
                  <td className="px-4 py-2 text-ink-muted">{r.entityType}</td>
                  <td className="px-4 py-2">
                    <button
                      onClick={() => setSelected(r)}
                      title="Voir le UUID complet et tout le détail"
                      className="font-mono text-[11px] text-sigdep-700 hover:underline">
                      {r.sourceUuid.substring(0, 18)}…
                    </button>
                  </td>
                  <td className="px-4 py-2">
                    <StatusBadge tone={r.errorCode === 'UNKNOWN_PATIENT' ? 'warning' : 'danger'}>
                      {r.errorCode ?? '—'}
                    </StatusBadge>
                  </td>
                  <td className="px-4 py-2 text-ink-muted text-xs max-w-md truncate"
                      title={r.errorMessage ?? ''}>
                    {r.errorMessage ?? '—'}
                  </td>
                  <td className="px-4 py-2">
                    {r.resolvedAt
                      ? <StatusBadge tone="ok">Résolu</StatusBadge>
                      : <StatusBadge tone="neutral">Ouvert</StatusBadge>}
                  </td>
                  <td className="px-4 py-2 text-right whitespace-nowrap">
                    <button
                      onClick={() => setSelected(r)}
                      className="text-sigdep-700 hover:underline text-xs mr-3">
                      Détails
                    </button>
                    {!r.resolvedAt && (
                      <button
                        onClick={() => setToResolve(r)}
                        className="text-emerald-700 hover:underline text-xs">
                        Résoudre
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
            )}
          </table>
        </div>

        {rejects.data && rejects.data.total > size && (
          <div className="px-4 py-3 flex items-center justify-between text-sm border-t border-slate-100">
            <p className="text-ink-muted">Page {rejects.data.page + 1} / {totalPages}</p>
            <div className="flex gap-2">
              <button onClick={() => setPage(p => Math.max(0, p - 1))}
                      disabled={rejects.data.page === 0}
                      className="px-3 py-1 rounded border border-slate-300 disabled:opacity-50 hover:bg-slate-50">
                Précédent
              </button>
              <button onClick={() => setPage(p => p + 1)}
                      disabled={rejects.data.page + 1 >= totalPages}
                      className="px-3 py-1 rounded border border-slate-300 disabled:opacity-50 hover:bg-slate-50">
                Suivant
              </button>
            </div>
          </div>
        )}
      </div>

      {selected && (
        <RejectDetailModal
          reject={selected}
          onClose={() => setSelected(null)}
          onResolve={() => { setToResolve(selected); setSelected(null); }} />
      )}

      {toResolve && (
        <ResolveModal
          reject={toResolve}
          onClose={() => setToResolve(null)} />
      )}
    </>
  );
}

function RejectDetailModal({ reject, onClose, onResolve }:
    Readonly<{ reject: RejectRow; onClose: () => void; onResolve: () => void }>) {
  const [copied, setCopied] = useState<string | null>(null);

  async function copy(value: string, label: string) {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(label);
      setTimeout(() => setCopied(null), 1500);
    } catch {
      /* ignore — older browsers without clipboard API */
    }
  }

  const code = reject.errorCode ?? '—';

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4"
         onClick={onClose}>
      <div className="bg-white rounded-lg shadow-xl w-full max-w-2xl max-h-[90vh] flex flex-col"
           onClick={e => e.stopPropagation()}>
        <div className="px-5 py-3 border-b border-slate-200 flex items-center justify-between">
          <h3 className="text-base font-semibold text-sigdep-800">
            Détails du rejet #{reject.id}
          </h3>
          <button onClick={onClose}
                  className="text-ink-muted hover:text-ink"><X className="h-4 w-4" /></button>
        </div>

        <div className="px-5 py-4 space-y-4 text-sm overflow-y-auto">
          <div className="grid grid-cols-2 gap-x-6 gap-y-3 text-xs">
            <DetailField label="Entité"      value={reject.entityType} />
            <DetailField label="Code"        value={code} />
            <DetailField label="Site"        value={reject.siteCode ?? '—'} mono />
            <DetailField label="Nom du site" value={reject.siteName ?? '—'} />
            <DetailField label="Rejeté le"   value={formatTime(reject.rejectedAt)} />
            <DetailField label="Batch id"    value={reject.batchId == null ? '—' : `#${reject.batchId}`} />
            {reject.resolvedAt && (
              <>
                <DetailField label="Résolu le"  value={formatTime(reject.resolvedAt)} />
                <DetailField label="Résolu par" value={reject.resolvedBy ?? '—'} />
              </>
            )}
          </div>

          <CopyableBlock
            label="Source UUID (OpenMRS)"
            value={reject.sourceUuid}
            copied={copied === 'uuid'}
            onCopy={() => copy(reject.sourceUuid, 'uuid')}
            mono />

          <CopyableBlock
            label="Message d'erreur"
            value={reject.errorMessage ?? '—'}
            copied={copied === 'message'}
            onCopy={() => copy(reject.errorMessage ?? '', 'message')} />

          {reject.resolutionNote && (
            <div>
              <span className="block text-xs font-medium text-ink-muted mb-1">
                Note de résolution
              </span>
              <div className="text-xs bg-emerald-50 border border-emerald-200 text-emerald-900 rounded p-2">
                {reject.resolutionNote}
              </div>
            </div>
          )}
        </div>

        <div className="px-5 py-3 border-t border-slate-200 flex justify-end gap-2">
          <button onClick={onClose}
                  className="px-3 py-1.5 text-sm border border-slate-300 rounded">Fermer</button>
          {!reject.resolvedAt && (
            <button
              onClick={onResolve}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 text-sm rounded
                         bg-emerald-600 text-white hover:bg-emerald-700">
              <CheckCircle2 className="h-4 w-4" />
              Marquer résolu
            </button>
          )}
        </div>
      </div>
    </div>
  );
}

function DetailField({ label, value, mono }: Readonly<{
  label: string; value: string; mono?: boolean;
}>) {
  return (
    <div>
      <span className="block text-ink-muted">{label}</span>
      <div className={mono ? 'font-mono break-all' : 'break-words'}>{value}</div>
    </div>
  );
}

function CopyableBlock({ label, value, copied, onCopy, mono }: Readonly<{
  label: string; value: string; copied: boolean; onCopy: () => void; mono?: boolean;
}>) {
  return (
    <div>
      <div className="flex items-center justify-between mb-1">
        <span className="text-xs font-medium text-ink-muted">{label}</span>
        <button onClick={onCopy}
                className="inline-flex items-center gap-1 text-xs text-ink-muted hover:text-sigdep-700">
          <Copy className="h-3 w-3" />
          {copied ? 'Copié !' : 'Copier'}
        </button>
      </div>
      <div className={`text-xs bg-slate-50 border border-slate-200 rounded p-2 break-all
                       max-h-40 overflow-auto ${mono ? 'font-mono' : ''}`}>
        {value}
      </div>
    </div>
  );
}

function ResolveModal({ reject, onClose }:
    Readonly<{ reject: RejectRow; onClose: () => void }>) {
  const [note, setNote] = useState('');
  const qc = useQueryClient();
  const m = useMutation({
    mutationFn: () => resolveSyncReject(reject.id, note || undefined),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['sync-rejects'] });
      qc.invalidateQueries({ queryKey: ['sync-rejects-counts'] });
      onClose();
    },
  });

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4"
         onClick={onClose}>
      <div className="bg-white rounded-lg shadow-xl w-full max-w-xl"
           onClick={e => e.stopPropagation()}>
        <div className="px-5 py-3 border-b border-slate-200 flex items-center justify-between">
          <h3 className="text-base font-semibold text-sigdep-800 flex items-center gap-2">
            <CheckCircle2 className="h-4 w-4 text-emerald-600" />
            Marquer comme résolu
          </h3>
          <button onClick={onClose}
                  className="text-ink-muted hover:text-ink"><X className="h-4 w-4" /></button>
        </div>
        <div className="px-5 py-4 space-y-3 text-sm">
          <div className="grid grid-cols-3 gap-2 text-xs">
            <div><span className="text-ink-muted">Entité</span><div>{reject.entityType}</div></div>
            <div><span className="text-ink-muted">Site</span>
              <div className="font-mono">{reject.siteCode ?? '—'}</div></div>
            <div><span className="text-ink-muted">Code</span>
              <div>{reject.errorCode ?? '—'}</div></div>
          </div>
          <div>
            <span className="text-xs text-ink-muted">Source UUID</span>
            <div className="font-mono text-[11px] break-all">{reject.sourceUuid}</div>
          </div>
          <div>
            <span className="text-xs text-ink-muted">Message</span>
            <div className="text-xs bg-slate-50 border border-slate-200 rounded p-2 mt-0.5 max-h-32 overflow-auto">
              {reject.errorMessage ?? '—'}
            </div>
          </div>
          <label className="block">
            <span className="block text-xs font-medium text-ink-muted mb-1">
              Commentaire (optionnel)
            </span>
            <textarea
              value={note}
              onChange={e => setNote(e.target.value)}
              rows={2}
              maxLength={500}
              placeholder="Ex : signalé au site, donnée corrigée dans OpenMRS."
              className="w-full rounded-md border border-slate-300 px-3 py-2 text-sm
                         focus:outline-none focus:border-accent-500 focus:ring-1 focus:ring-accent-500" />
          </label>
          {m.isError && <p className="text-rose-600 text-xs">{(m.error as Error).message}</p>}
        </div>
        <div className="px-5 py-3 border-t border-slate-200 flex justify-end gap-2">
          <button onClick={onClose}
                  className="px-3 py-1.5 text-sm border border-slate-300 rounded">Annuler</button>
          <button onClick={() => m.mutate()}
                  disabled={m.isPending}
                  className="px-3 py-1.5 text-sm rounded bg-accent-600 text-white hover:bg-accent-700 disabled:opacity-50">
            {m.isPending ? 'Résolution…' : 'Marquer résolu'}
          </button>
        </div>
      </div>
    </div>
  );
}

