import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Building2, ChevronLeft, ChevronRight, KeyRound, Search } from 'lucide-react';
import {
  fetchSites, SiteStatus,
  fetchApiKeyStatus, generateApiKey, revokeApiKey,
} from '../api/client';
import { useAuth } from '../auth';
import { formatInt } from '../components/Kpi';
import { PageHeader } from '../components/PageHeader';
import { GeoFilter, GeoScope } from '../components/GeoFilter';
import { SortableTh, SortState } from '../components/SortableTh';
import { StatusBadge, type BadgeTone } from '../components/StatusBadge';
import { TableSkeleton } from '../components/Skeleton';

const ADMIN_ROLES = new Set(['SUPER_ADMIN', 'IT_ADMIN']);

const STATUS_TABS: { value: SiteStatus; label: string }[] = [
  { value: 'all',     label: 'Tous' },
  { value: 'online',  label: 'En ligne (< 24h)' },
  { value: 'late',    label: 'En retard (24h–7j)' },
  { value: 'offline', label: 'Hors ligne (> 7j)' },
];

function syncBadge(iso: string | null): { label: string; tone: BadgeTone } {
  if (!iso) return { label: 'Jamais', tone: 'neutral' };
  const ageHours = (Date.now() - new Date(iso).getTime()) / 36e5;
  if (ageHours < 24)     return { label: formatRelative(ageHours), tone: 'ok' };
  if (ageHours < 24 * 7) return { label: formatRelative(ageHours), tone: 'warning' };
  return { label: formatRelative(ageHours), tone: 'danger' };
}

function formatRelative(ageHours: number): string {
  if (ageHours < 1) return 'À l’instant';
  if (ageHours < 24) return `il y a ${Math.floor(ageHours)} h`;
  const days = Math.floor(ageHours / 24);
  return `il y a ${days} j`;
}

function sigdepBadge(flag: boolean | null): { label: string; tone: BadgeTone } {
  if (flag === true)  return { label: 'SIGDEP',      tone: 'info' };
  if (flag === false) return { label: 'Hors SIGDEP', tone: 'neutral' };
  return { label: '?', tone: 'neutral' };
}

export function Sites() {
  const [query, setQuery] = useState('');
  const [status, setStatus] = useState<SiteStatus>('all');
  const [scope, setScope] = useState<GeoScope>({});
  const [sort, setSort] = useState<SortState>(null);
  const [page, setPage] = useState(0);
  const [keyModal, setKeyModal] = useState<{ id: number; code: string; name: string } | null>(null);
  const size = 50;

  const { user } = useAuth();
  const isAdmin = ADMIN_ROLES.has(user?.role ?? '');

  const sites = useQuery({
    queryKey: ['sites', query, status, scope, sort, page],
    queryFn: () => fetchSites({ q: query, status, ...scope, sort, page, size }),
  });

  const onSort = (s: SortState) => { setSort(s); setPage(0); };

  const totalPages = sites.data ? Math.max(1, Math.ceil(sites.data.total / sites.data.size)) : 1;

  return (
    <div className="px-6 py-6">
      <PageHeader
        icon={Building2}
        title="Sites"
        subtitle={sites.data ? `${formatInt(sites.data.total)} sites` : 'Chargement…'}
        right={<>
          <GeoFilter value={scope} onChange={s => { setScope(s); setPage(0); }} />
          <div className="relative">
            <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 h-4 w-4 text-ink-subtle pointer-events-none" />
            <input
              type="search"
              value={query}
              onChange={e => { setQuery(e.target.value); setPage(0); }}
              placeholder="Rechercher (code ou nom)…"
              className="w-72 rounded-md border border-slate-300 pl-8 pr-3 py-2 text-sm
                         focus:outline-none focus:border-sigdep-500 focus:ring-1 focus:ring-sigdep-500"
            />
          </div>
        </>} />

      {/* Status tabs */}
      <div className="flex gap-1 mb-4 border-b border-slate-200">
        {STATUS_TABS.map(t => (
          <button
            key={t.value}
            onClick={() => { setStatus(t.value); setPage(0); }}
            className={`px-3 py-2 text-sm border-b-2 transition ${
              status === t.value
                ? 'border-sigdep-500 text-sigdep-700 font-medium'
                : 'border-transparent text-ink-muted hover:text-ink'
            }`}>
            {t.label}
          </button>
        ))}
      </div>

      <div className="card overflow-hidden">
        <table className="w-full text-sm">
          <thead className="thead-sigdep text-left">
            <tr className="text-left">
              <SortableTh k="code"          sort={sort} onSort={onSort}>Code</SortableTh>
              <SortableTh k="name"          sort={sort} onSort={onSort}>Nom</SortableTh>
              <SortableTh k="region"        sort={sort} onSort={onSort}>Région / District</SortableTh>
              <SortableTh k="facilityType"  sort={sort} onSort={onSort}>Type</SortableTh>
              <SortableTh k="patientCount"  sort={sort} onSort={onSort} align="right">Patients</SortableTh>
              <SortableTh k="lastSyncAt"    sort={sort} onSort={onSort}>Dernier sync</SortableTh>
              <th className="px-4 py-2 font-medium">SIGDEP</th>
              {isAdmin && <th className="px-4 py-2 font-medium">Clé API</th>}
            </tr>
          </thead>
          {sites.isLoading ? (
            <TableSkeleton rows={8} cols={7} />
          ) : (
          <tbody className="divide-y divide-slate-100">
            {sites.isError ? (
              <tr><td colSpan={isAdmin ? 8 : 7} className="px-4 py-6 text-center text-rose-600">Erreur de chargement</td></tr>
            ) : sites.data?.content.length === 0 ? (
              <tr><td colSpan={isAdmin ? 8 : 7} className="px-4 py-6 text-center text-ink-muted">Aucun site</td></tr>
            ) : sites.data?.content.map(s => {
              const sb = syncBadge(s.lastSyncAt);
              const sg = sigdepBadge(s.runsSigdep);
              return (
                <tr key={s.id} className="hover:bg-slate-50">
                  <td className="px-4 py-2 font-mono text-xs">{s.code}</td>
                  <td className="px-4 py-2">{s.name}</td>
                  <td className="px-4 py-2 text-ink-muted">
                    {s.regionName} <span className="text-ink-subtle">/ {s.districtName}</span>
                  </td>
                  <td className="px-4 py-2 text-ink-muted">{s.facilityType ?? '—'}</td>
                  <td className="px-4 py-2 text-right tabular-nums">{formatInt(s.patientCount)}</td>
                  <td className="px-4 py-2">
                    <StatusBadge tone={sb.tone}>{sb.label}</StatusBadge>
                  </td>
                  <td className="px-4 py-2">
                    <StatusBadge tone={sg.tone}>{sg.label}</StatusBadge>
                  </td>
                  {isAdmin && (
                    <td className="px-4 py-2">
                      <button
                        onClick={() => setKeyModal({ id: s.id, code: s.code, name: s.name })}
                        className="inline-flex items-center gap-1 text-xs text-sigdep-700 hover:underline">
                        <KeyRound className="h-3.5 w-3.5" />
                        Gérer
                      </button>
                    </td>
                  )}
                </tr>
              );
            })}
          </tbody>
          )}
        </table>
      </div>

      {sites.data && sites.data.total > 0 && (
        <div className="mt-4 flex items-center justify-between text-sm">
          <p className="text-ink-muted">
            Page {sites.data.page + 1} / {totalPages}
          </p>
          <div className="flex gap-2">
            <button
              onClick={() => setPage(p => Math.max(0, p - 1))}
              disabled={sites.data.page === 0}
              className="inline-flex items-center gap-1 px-3 py-1 rounded border border-slate-300
                         disabled:opacity-50 hover:bg-slate-50 transition">
              <ChevronLeft className="h-3.5 w-3.5" />
              Précédent
            </button>
            <button
              onClick={() => setPage(p => p + 1)}
              disabled={sites.data.page + 1 >= totalPages}
              className="inline-flex items-center gap-1 px-3 py-1 rounded border border-slate-300
                         disabled:opacity-50 hover:bg-slate-50 transition">
              Suivant
              <ChevronRight className="h-3.5 w-3.5" />
            </button>
          </div>
        </div>
      )}

      {keyModal && (
        <ApiKeyModal site={keyModal} onClose={() => setKeyModal(null)} />
      )}
    </div>
  );
}

// ---------- API key modal --------------------------------------------------

function ApiKeyModal({ site, onClose }:
    Readonly<{ site: { id: number; code: string; name: string }; onClose: () => void }>) {
  const qc = useQueryClient();
  const [generated, setGenerated] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const statusQ = useQuery({
    queryKey: ['apiKey', site.id],
    queryFn: () => fetchApiKeyStatus(site.id),
  });

  const doGenerate = async () => {
    setBusy(true); setError(null);
    try {
      const res = await generateApiKey(site.id);
      setGenerated(res?.apiKey ?? null);
      qc.invalidateQueries({ queryKey: ['apiKey', site.id] });
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Génération impossible');
    } finally {
      setBusy(false);
    }
  };

  const doRevoke = async () => {
    setBusy(true); setError(null);
    try {
      await revokeApiKey(site.id);
      setGenerated(null);
      qc.invalidateQueries({ queryKey: ['apiKey', site.id] });
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Révocation impossible');
    } finally {
      setBusy(false);
    }
  };

  const present = statusQ.data?.present ?? false;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4" onClick={onClose}>
      <div className="bg-white rounded-lg shadow-xl w-full max-w-lg flex flex-col" onClick={e => e.stopPropagation()}>
        <div className="px-5 py-3 border-b border-slate-200 flex items-center justify-between">
          <h3 className="text-base font-semibold text-sigdep-800">Clé API — {site.code}</h3>
          <button onClick={onClose} className="text-ink-muted hover:text-ink text-lg leading-none">&times;</button>
        </div>

        <div className="px-5 py-4 space-y-3 text-sm">
          <p className="text-ink-muted">{site.name}</p>

          {generated ? (
            <div className="rounded-md border border-amber-300 bg-amber-50 p-3 space-y-2">
              <p className="text-xs font-medium text-amber-800">
                Copie cette clé maintenant — elle ne sera plus jamais affichée.
              </p>
              <code className="block break-all rounded bg-white border border-amber-200 px-2 py-1.5 text-xs font-mono">
                {generated}
              </code>
            </div>
          ) : statusQ.isLoading ? (
            <p className="text-ink-muted">Chargement…</p>
          ) : present ? (
            <p className="text-sm">
              Une clé active existe (préfixe <span className="font-mono">{statusQ.data?.prefix}…</span>).
              En générer une nouvelle <span className="font-medium">révoque</span> l'ancienne.
            </p>
          ) : (
            <p className="text-sm text-ink-muted">Aucune clé active pour ce site.</p>
          )}

          {error && <p className="text-rose-600 text-xs">{error}</p>}
        </div>

        <div className="px-5 py-3 border-t border-slate-200 flex justify-end gap-2">
          {present && !generated && (
            <button onClick={doRevoke} disabled={busy}
                    className="px-3 py-1.5 text-sm rounded border border-rose-300 text-rose-700 hover:bg-rose-50 disabled:opacity-50">
              Révoquer
            </button>
          )}
          <button onClick={onClose} className="px-3 py-1.5 text-sm border border-slate-300 rounded">Fermer</button>
          {!generated && (
            <button onClick={doGenerate} disabled={busy}
                    className="px-3 py-1.5 text-sm rounded bg-sigdep-600 text-white hover:bg-sigdep-700 disabled:opacity-50">
              {busy ? 'Génération…' : present ? 'Régénérer' : 'Générer une clé'}
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
