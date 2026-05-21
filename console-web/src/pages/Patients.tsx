import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { ChevronLeft, ChevronRight, Download, Search, Users } from 'lucide-react';
import { downloadPatientsCsv, fetchPatients } from '../api/client';
import { formatInt } from '../components/Kpi';
import { PageHeader } from '../components/PageHeader';
import { GeoFilter, GeoScope } from '../components/GeoFilter';
import { SortableTh, SortState } from '../components/SortableTh';
import { TableSkeleton } from '../components/Skeleton';

function age(birthDate: string | null): string {
  if (!birthDate) return '—';
  const bd = new Date(birthDate);
  const now = new Date();
  let y = now.getFullYear() - bd.getFullYear();
  const m = now.getMonth() - bd.getMonth();
  if (m < 0 || (m === 0 && now.getDate() < bd.getDate())) y--;
  return `${y} ans`;
}

function formatDate(iso: string | null): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleDateString('fr-FR',
    { day: '2-digit', month: 'short', year: 'numeric' });
}

const SEX_LABEL: Record<string, string> = { M: 'Homme', F: 'Femme' };

export function Patients() {
  const [query, setQuery] = useState('');
  const [scope, setScope] = useState<GeoScope>({});
  const [sort, setSort] = useState<SortState>(null);
  const [page, setPage] = useState(0);
  const [exporting, setExporting] = useState(false);
  const size = 25;

  const { data, isLoading, isError } = useQuery({
    queryKey: ['patients', query, scope, sort, page],
    queryFn: () => fetchPatients({ q: query, ...scope, sort, page, size }),
  });

  const onSort = (s: SortState) => { setSort(s); setPage(0); };

  const totalPages = data ? Math.max(1, Math.ceil(data.total / data.size)) : 1;

  async function handleExport() {
    setExporting(true);
    try { await downloadPatientsCsv({ q: query, ...scope }); }
    catch (err) { /* eslint-disable-next-line no-console */ console.error(err); }
    finally { setExporting(false); }
  }

  return (
    <div className="px-6 py-6">
      <PageHeader
        icon={Users}
        title="Patients"
        subtitle={data ? `${formatInt(data.total)} patients` : 'Chargement…'}
        right={<>
          <GeoFilter value={scope} onChange={s => { setScope(s); setPage(0); }} />
          <div className="relative">
            <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 h-4 w-4 text-ink-subtle pointer-events-none" />
            <input
              type="search"
              value={query}
              onChange={e => { setQuery(e.target.value); setPage(0); }}
              placeholder="Rechercher (UUID, identifiant)…"
              className="w-72 rounded-md border border-slate-300 pl-8 pr-3 py-2 text-sm
                         focus:outline-none focus:border-sigdep-500 focus:ring-1 focus:ring-sigdep-500"
            />
          </div>
          <button
            onClick={handleExport}
            disabled={exporting || !data || data.total === 0}
            className="inline-flex items-center gap-1.5 rounded-md border border-slate-300 px-3 py-1.5 text-xs
                       hover:bg-slate-50 disabled:opacity-50 transition">
            <Download className="h-3.5 w-3.5" />
            {exporting ? 'Export…' : 'Exporter CSV'}
          </button>
        </>} />

      <div className="card overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="thead-sigdep text-left">
              <tr className="text-left">
                <SortableTh k="codeArv"     sort={sort} onSort={onSort}>Identifiant</SortableTh>
                <SortableTh k="upid"        sort={sort} onSort={onSort}>UPID</SortableTh>
                <SortableTh k="sex"         sort={sort} onSort={onSort}>Sexe</SortableTh>
                <SortableTh k="birthDate"   sort={sort} onSort={onSort}>Âge</SortableTh>
                <SortableTh k="arvInitDate" sort={sort} onSort={onSort}>Date init. ARV</SortableTh>
                <th className="px-4 py-2 font-medium">Régime initial</th>
                <SortableTh k="lastVisit"   sort={sort} onSort={onSort}>Dernière visite</SortableTh>
                <th className="px-4 py-2 font-medium">Dernier régime</th>
                <SortableTh k="site"        sort={sort} onSort={onSort}>Site</SortableTh>
              </tr>
            </thead>
            {isLoading ? (
              <TableSkeleton rows={8} cols={9} />
            ) : (
            <tbody className="divide-y divide-slate-100">
              {isError ? (
                <tr><td colSpan={9} className="px-4 py-6 text-center text-rose-600">Erreur de chargement</td></tr>
              ) : data?.content.length === 0 ? (
                <tr><td colSpan={9} className="px-4 py-6 text-center text-ink-muted">Aucun patient</td></tr>
              ) : data?.content.map(p => (
                <tr key={p.id} className="hover:bg-slate-50">
                  <td className="px-4 py-2">
                    <Link to={`/app/patients/${p.id}`} className="text-sigdep-700 hover:underline">
                      {p.codeArv ?? '—'}
                    </Link>
                  </td>
                  <td className="px-4 py-2 font-mono text-xs text-ink-muted">{p.upid ?? '—'}</td>
                  <td className="px-4 py-2">{p.sex ? SEX_LABEL[p.sex] ?? p.sex : '—'}</td>
                  <td className="px-4 py-2">{age(p.birthDate)}</td>
                  <td className="px-4 py-2 whitespace-nowrap text-ink-muted">{formatDate(p.arvInitDate)}</td>
                  <td className="px-4 py-2 text-ink-muted">{p.arvRegimenInitial ?? '—'}</td>
                  <td className="px-4 py-2 whitespace-nowrap text-ink-muted">{formatDate(p.lastVisitDate)}</td>
                  <td className="px-4 py-2 text-ink-muted">{p.lastArvRegimen ?? '—'}</td>
                  <td className="px-4 py-2 text-ink-muted">{p.siteName}</td>
                </tr>
              ))}
            </tbody>
            )}
          </table>
        </div>
      </div>

      {/* Pagination */}
      {data && data.total > 0 && (
        <div className="mt-4 flex items-center justify-between text-sm">
          <p className="text-ink-muted">
            Page {data.page + 1} / {totalPages}
          </p>
          <div className="flex gap-2">
            <button
              onClick={() => setPage(p => Math.max(0, p - 1))}
              disabled={data.page === 0}
              className="inline-flex items-center gap-1 px-3 py-1 rounded border border-slate-300
                         disabled:opacity-50 hover:bg-slate-50 transition">
              <ChevronLeft className="h-3.5 w-3.5" />
              Précédent
            </button>
            <button
              onClick={() => setPage(p => p + 1)}
              disabled={data.page + 1 >= totalPages}
              className="inline-flex items-center gap-1 px-3 py-1 rounded border border-slate-300
                         disabled:opacity-50 hover:bg-slate-50 transition">
              Suivant
              <ChevronRight className="h-3.5 w-3.5" />
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
