import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Link } from "react-router-dom";
import {
  Bar,
  BarChart,
  LabelList,
  Legend,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { Activity, Download, FilePlus, FileX, Stethoscope, type LucideIcon } from "lucide-react";
import {
  downloadClinicCsv, downloadClosureCsv, downloadInitiationCsv, downloadIvsaCsv,
  fetchClinicSummary, fetchClinicVisits,
  fetchClosureRecords, fetchClosureSummary,
  fetchInitiationRecords, fetchInitiationSummary,
  fetchIvsaSummary, fetchIvsaVisits,
} from "../api/client";
import { Kpi, formatInt, formatPercent } from "../components/Kpi";
import { PageHeader } from "../components/PageHeader";
import { GeoFilter, GeoScope } from "../components/GeoFilter";
import { SortableTh, SortState } from "../components/SortableTh";
import { ChartSkeleton, KpiRowSkeleton, TableSkeleton } from "../components/Skeleton";

const PERIODS = [
  { months: 12, label: "12 derniers mois" },
  { months: 24, label: "24 derniers mois" },
  { months: 60, label: "5 dernières années" },
];

type Tab = "visits" | "initiations" | "closures" | "ivsa";

export function Clinique() {
  const [tab, setTab] = useState<Tab>("initiations");
  const [months, setMonths] = useState(12);
  const [scope, setScope] = useState<GeoScope>({});

  // Order follows the patient journey: entrée (initiation) → suivi
  // (visites + IVSA pour les non stables) → sortie (clôture).
  const tabs: { id: Tab; label: string; icon: LucideIcon }[] = [
    { id: "initiations", label: "Initiations", icon: FilePlus },
    { id: "visits",      label: "Visites",     icon: Stethoscope },
    { id: "ivsa",        label: "IVSA",        icon: Activity },
    { id: "closures",    label: "Clôtures",    icon: FileX },
  ];

  const activeIcon = tabs.find(t => t.id === tab)?.icon ?? FilePlus;

  return (
    <div className="px-6 py-6">
      <PageHeader
        icon={activeIcon}
        title="Suivi clinique"
        subtitle="Visites · Initiations ARV · Clôtures · IVSA"
        right={<>
          <GeoFilter value={scope} onChange={setScope} />
          <select
            value={months}
            onChange={(e) => setMonths(Number(e.target.value))}
            className="rounded-md border border-slate-300 px-3 py-2 text-sm bg-white"
          >
            {PERIODS.map((p) => (
              <option key={p.months} value={p.months}>{p.label}</option>
            ))}
          </select>
        </>} />

      {/* Tabs */}
      <div className="mb-4 border-b border-slate-200 flex gap-1">
        {tabs.map(t => (
          <button
            key={t.id}
            onClick={() => setTab(t.id)}
            className={`px-4 py-2 text-sm font-medium border-b-2 transition -mb-px ${
              tab === t.id
                ? "border-sigdep-600 text-sigdep-700"
                : "border-transparent text-ink-muted hover:text-ink"
            }`}>
            <t.icon className="inline-block h-4 w-4 mr-1.5 -mt-0.5" />
            {t.label}
          </button>
        ))}
      </div>

      {tab === "visits"      && <VisitsPanel      months={months} scope={scope} />}
      {tab === "initiations" && <InitiationsPanel months={months} scope={scope} />}
      {tab === "closures"    && <ClosuresPanel    months={months} scope={scope} />}
      {tab === "ivsa"        && <IvsaPanel        months={months} scope={scope} />}
    </div>
  );
}

function formatDate(iso: string | null): string {
  if (!iso) return "—";
  return new Date(iso).toLocaleDateString("fr-FR", {
    day: "2-digit",
    month: "short",
    year: "numeric",
  });
}

function shortenWho(s: string | null): string {
  if (!s) return "—";
  // "WHO STAGE 2 ADULT" → "Stade 2"
  const m = s.match(/STAGE\s+(\d)/i);
  return m ? `Stade ${m[1]}` : s;
}

function VisitsPanel({ months, scope }: { months: number; scope: GeoScope }) {
  const [sort, setSort] = useState<SortState>(null);
  const [page, setPage] = useState(0);
  const [exporting, setExporting] = useState(false);
  const size = 50;

  const summary = useQuery({
    queryKey: ["clinic-summary", months, scope],
    queryFn: () => fetchClinicSummary(months, scope),
  });
  const visits = useQuery({
    queryKey: ["clinic-visits", months, scope, sort, page],
    queryFn: () => fetchClinicVisits({ months, ...scope, sort, page, size }),
  });

  const onSort = (s: SortState) => { setSort(s); setPage(0); };

  const totalPages = visits.data
    ? Math.max(1, Math.ceil(visits.data.total / visits.data.size))
    : 1;

  async function handleExport() {
    setExporting(true);
    try {
      await downloadClinicCsv(months, scope);
    } catch (err) {
      /* eslint-disable-next-line no-console */ console.error(err);
    } finally {
      setExporting(false);
    }
  }

  return (
    <>
      <div className="flex justify-end mb-3">
        <button
          onClick={handleExport}
          disabled={exporting || !visits.data || visits.data.total === 0}
          className="inline-flex items-center gap-1.5 rounded-md border border-slate-300 px-3 py-1.5 text-xs
                     hover:bg-slate-50 disabled:opacity-50 transition"
        >
          <Download className="h-3.5 w-3.5" />
          {exporting ? "Export…" : "Exporter CSV"}
        </button>
      </div>

      {/* KPIs */}
      {summary.isLoading ? <KpiRowSkeleton /> : (
        <div className="grid gap-3 grid-cols-2 lg:grid-cols-4 mb-6">
          <Kpi
            label="Visites (cumul)"
            value={summary.isError ? "Erreur" : formatInt(summary.data?.visitsAllTime)}
            hint="Toutes périodes"
            hintTone="neutral"
          />
          <Kpi
            label="Visites (période)"
            value={summary.isError ? "Erreur" : formatInt(summary.data?.visitsInPeriod)}
            hint={`${PERIODS.find((p) => p.months === months)?.label}`}
            hintTone="neutral"
          />
          <Kpi
            label="% dépistage TB"
            value={summary.isError
                ? "Erreur"
                : formatPercent(summary.data?.tbScreeningPct ?? null)}
            hint="Visites avec résultat documenté"
            hintTone="positive"
          />
          <Kpi
            label="% stade OMS"
            value={summary.isError
                ? "Erreur"
                : formatPercent(summary.data?.whoStagePct ?? null)}
            hint="Visites avec stade renseigné"
            hintTone="positive"
          />
        </div>
      )}

      {/* Visits vs Dispensations — détecte les gaps de couverture ARV
          (un mois avec beaucoup de visites mais peu de dispensations
          signale une rupture, ou inversement). */}
      <div className="card p-4 mb-6">
        <h3 className="text-sm font-medium mb-4">
          Visites vs dispensations ARV — par mois
        </h3>
        <p className="text-xs text-ink-muted mb-3">
          Dispensations = visites avec un nombre de jours de traitement ARV
          renseigné (la dispensation est captée sur la visite elle-même).
        </p>
        <div className="h-56">
          {summary.isLoading ? (
            <ChartSkeleton height="h-56" />
          ) : !summary.data || summary.data.monthly.length === 0 ? (
            <div className="h-full flex items-center justify-center text-ink-muted text-sm">
              —
            </div>
          ) : (
            <ResponsiveContainer width="100%" height="100%">
              <BarChart
                data={summary.data.monthly}
                margin={{ top: 24, right: 8, left: 0, bottom: 0 }}
              >
                <XAxis
                  dataKey="month"
                  tick={{ fontSize: 11 }}
                  stroke="#94a3b8"
                />
                <YAxis tick={{ fontSize: 11 }} stroke="#94a3b8" />
                <Tooltip
                  contentStyle={{ borderRadius: 6, fontSize: 12 }}
                  formatter={(v, name) => [formatInt(v as number), name]}
                />
                <Legend wrapperStyle={{ fontSize: 12 }} />
                <Bar dataKey="count" name="Visites" fill="#009d8e" radius={[3, 3, 0, 0]} />
                <Bar dataKey="dispensations" name="Dispensations" fill="#7c3aed" radius={[3, 3, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          )}
        </div>
      </div>

      {/* Évolution attendus vs venus — détecte les perdus de vue précoces :
          si le nombre attendu (= prochaine_visite tombant dans le mois)
          s'éloigne durablement du nombre venu, il y a fuite. */}
      <div className="card p-4 mb-6">
        <h3 className="text-sm font-medium mb-4">
          Patients attendus vs venus — par mois
        </h3>
        <div className="h-56">
          {summary.isLoading ? (
            <ChartSkeleton height="h-56" />
          ) : !summary.data || summary.data.monthly.length === 0 ? (
            <div className="h-full flex items-center justify-center text-ink-muted text-sm">—</div>
          ) : (
            <ResponsiveContainer width="100%" height="100%">
              <LineChart
                data={summary.data.monthly}
                margin={{ top: 24, right: 16, left: 0, bottom: 0 }}
              >
                <XAxis dataKey="month" tick={{ fontSize: 11 }} stroke="#94a3b8" />
                <YAxis tick={{ fontSize: 11 }} stroke="#94a3b8" />
                <Tooltip
                  contentStyle={{ borderRadius: 6, fontSize: 12 }}
                  formatter={(v, name) => [formatInt(v as number), name]}
                />
                <Legend wrapperStyle={{ fontSize: 12 }} />
                <Line type="monotone" dataKey="expected" name="Attendus"
                      stroke="#94a3b8" strokeWidth={2} strokeDasharray="4 4"
                      dot={{ r: 3 }} />
                <Line type="monotone" dataKey="count" name="Venus"
                      stroke="#009d8e" strokeWidth={2}
                      dot={{ r: 3 }} />
              </LineChart>
            </ResponsiveContainer>
          )}
        </div>
      </div>

      {/* Distributions row */}
      <div className="grid gap-3 lg:grid-cols-3 mb-6">
        <DistributionCard
          title="Stades OMS"
          rows={summary.data?.whoStageDistribution ?? []}
          formatter={shortenWho}
        />
        <DistributionCard
          title="Dépistage TB"
          rows={summary.data?.tbScreeningDistribution ?? []}
        />
        <DistributionCard
          title="Régimes ARV (top 10)"
          rows={summary.data?.arvRegimenDistribution ?? []}
        />
      </div>

      {/* Visits table */}
      <div className="card overflow-hidden">
        <div className="px-4 py-3 bg-sigdep-50 border-b border-sigdep-100 flex items-center justify-between">
          <h3 className="text-sm font-semibold text-sigdep-800">Visites</h3>
          <span className="text-xs text-ink-muted">
            {visits.data ? `${formatInt(visits.data.total)} visites` : "—"}
          </span>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="thead-sigdep text-left">
              <tr className="text-left">
                <SortableTh k="date"       sort={sort} onSort={onSort}>Date</SortableTh>
                <SortableTh k="patient"    sort={sort} onSort={onSort}>Patient</SortableTh>
                <th className="px-4 py-2 font-medium">Stade</th>
                <SortableTh k="arvRegimen" sort={sort} onSort={onSort}>Régime ARV</SortableTh>
                <SortableTh k="weight"     sort={sort} onSort={onSort} align="right">Poids</SortableTh>
                <SortableTh k="bmi"        sort={sort} onSort={onSort} align="right">IMC</SortableTh>
                <th className="px-4 py-2 font-medium">TPT</th>
                <SortableTh k="site"       sort={sort} onSort={onSort}>Site</SortableTh>
              </tr>
            </thead>
            {visits.isLoading ? (
              <TableSkeleton rows={8} cols={8} />
            ) : (
            <tbody className="divide-y divide-slate-100">
              {(() => {
                if (visits.isError) {
                  return (
                    <tr>
                      <td
                        colSpan={8}
                        className="px-4 py-6 text-center text-rose-600"
                      >
                        Erreur de chargement
                      </td>
                    </tr>
                  );
                }
                if (!visits.data || visits.data.content.length === 0) {
                  return (
                    <tr>
                      <td
                        colSpan={8}
                        className="px-4 py-6 text-center text-ink-muted"
                      >
                        Aucune visite
                      </td>
                    </tr>
                  );
                }
                return visits.data.content.map((v) => (
                  <tr key={v.id} className="hover:bg-slate-50">
                    <td className="px-4 py-2 whitespace-nowrap text-ink-muted">
                      {formatDate(v.visitDate)}
                    </td>
                    <td className="px-4 py-2">
                      <Link
                        to={`/app/patients/${v.patientId}`}
                        className="text-sigdep-700 hover:underline font-mono text-xs"
                      >
                        {v.patientCode ?? `#${v.patientId}`}
                      </Link>
                    </td>
                    <td className="px-4 py-2">{shortenWho(v.whoStage)}</td>
                    <td className="px-4 py-2">{v.arvRegimen ?? "—"}</td>
                    <td className="px-4 py-2 text-right tabular-nums">
                      {v.weightKg ?? "—"}
                    </td>
                    <td className="px-4 py-2 text-right tabular-nums">
                      {v.bmi ?? "—"}
                    </td>
                    <td className="px-4 py-2 text-ink-muted">
                      {v.tptStatus ? (
                        <span className="text-xs">
                          {v.tptStatus}
                          {v.tptRegimen ? ` (${v.tptRegimen})` : ""}
                        </span>
                      ) : (
                        "—"
                      )}
                    </td>
                    <td className="px-4 py-2 text-ink-muted">
                      <span className="font-mono text-xs">{v.siteCode}</span>{" "}
                      {v.siteName}
                    </td>
                  </tr>
                ));
              })()}
            </tbody>
            )}
          </table>
        </div>

        {visits.data && visits.data.total > size && (
          <div className="px-4 py-3 flex items-center justify-between text-sm border-t border-slate-100">
            <p className="text-ink-muted">
              Page {visits.data.page + 1} / {totalPages}
            </p>
            <div className="flex gap-2">
              <button
                onClick={() => setPage((p) => Math.max(0, p - 1))}
                disabled={visits.data.page === 0}
                className="px-3 py-1 rounded border border-slate-300 disabled:opacity-50 hover:bg-slate-50"
              >
                Précédent
              </button>
              <button
                onClick={() => setPage((p) => p + 1)}
                disabled={visits.data.page + 1 >= totalPages}
                className="px-3 py-1 rounded border border-slate-300 disabled:opacity-50 hover:bg-slate-50"
              >
                Suivant
              </button>
            </div>
          </div>
        )}
      </div>
    </>
  );
}

function DistributionCard({
  title,
  rows,
  formatter,
  className,
}: {
  title: string;
  rows: { label: string; count: number }[];
  formatter?: (s: string | null) => string;
  className?: string;
}) {
  // When embedded as a sub-section (Initiations panel grid), the card
  // wrapper is provided by the parent; in standalone mode we keep the
  // card affordance.
  const wrapperClass = className ?? "card p-4";
  return (
    <div className={wrapperClass}>
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
            <tr>
              <td colSpan={2} className="py-2 text-center text-ink-muted">
                —
              </td>
            </tr>
          ) : (
            rows.map((r) => (
              <tr key={r.label}>
                <td className="py-1">
                  {formatter ? formatter(r.label) : r.label}
                </td>
                <td className="py-1 text-right tabular-nums">
                  {formatInt(r.count)}
                </td>
              </tr>
            ))
          )}
        </tbody>
      </table>
    </div>
  );
}

// =============================================================================
// Initiations panel — fiche initiale ARV
// =============================================================================
function InitiationsPanel({ months, scope }: { months: number; scope: GeoScope }) {
  const [sort, setSort] = useState<SortState>(null);
  const [page, setPage] = useState(0);
  const [exporting, setExporting] = useState(false);
  const size = 50;

  const summary = useQuery({
    queryKey: ["init-summary", months, scope],
    queryFn: () => fetchInitiationSummary(months, scope),
  });
  const records = useQuery({
    queryKey: ["init-records", months, scope, sort, page],
    queryFn: () => fetchInitiationRecords({ months, ...scope, sort, page, size }),
  });

  const onSort = (s: SortState) => { setSort(s); setPage(0); };
  const totalPages = records.data ? Math.max(1, Math.ceil(records.data.total / records.data.size)) : 1;

  async function handleExport() {
    setExporting(true);
    try { await downloadInitiationCsv(months, scope); }
    catch (err) { /* eslint-disable-next-line no-console */ console.error(err); }
    finally { setExporting(false); }
  }

  return <>
    <div className="flex justify-end mb-3">
      <button onClick={handleExport}
        disabled={exporting || !records.data || records.data.total === 0}
        className="inline-flex items-center gap-1.5 rounded-md border border-slate-300 px-3 py-1.5 text-xs
                   hover:bg-slate-50 disabled:opacity-50 transition">
        <Download className="h-3.5 w-3.5" />
        {exporting ? "Export…" : "Exporter CSV"}
      </button>
    </div>

    {summary.isLoading ? <KpiRowSkeleton /> : (
      <div className="grid gap-3 grid-cols-2 lg:grid-cols-4 mb-6">
        <Kpi label="Initiations (cumul)"
             value={summary.isError ? "Erreur" : formatInt(summary.data?.totalAllTime)}
             hint="Toutes périodes" hintTone="neutral" />
        <Kpi label="Période"
             value={summary.isError ? "Erreur" : formatInt(summary.data?.inPeriod)}
             hint={PERIODS.find(p => p.months === months)?.label} hintTone="neutral" />
        <Kpi label="Pédiatriques"
             value={summary.isError ? "Erreur" : formatInt(summary.data?.pediatric)}
             hint="Avec fiche pédiatrique liée" hintTone="positive" />
        <Kpi label="Référés"
             value={summary.isError ? "Erreur" : formatInt(summary.data?.referred)}
             hint="Patients référés d'un autre site" hintTone="neutral" />
      </div>
    )}

    <div className="grid gap-3 lg:grid-cols-2 mb-6">
      <div className="card p-4 flex flex-col">
        <h3 className="text-sm font-medium mb-4">Initiations par année</h3>
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
                             formatter={(v) => [formatInt(v as number), "Init"]} />
                    <Bar dataKey="count" fill="#009d8e" radius={[3, 3, 0, 0]}>
                      <LabelList dataKey="count" position="top"
                                 style={{ fill: "#475569", fontSize: 11, fontWeight: 500 }} />
                    </Bar>
                  </BarChart>
                </ResponsiveContainer>
              )}
        </div>
      </div>

      <div className="card p-4">
        <DistributionCard title="Porte d'entrée" rows={summary.data?.entryPoints ?? []} className="" />
        <DistributionCard title="Régime initial" rows={summary.data?.regimens ?? []} className="mt-5" />
        <DistributionCard title="Stade OMS initial" rows={summary.data?.whoStages ?? []} className="mt-5" />
      </div>
    </div>

    <div className="card overflow-hidden">
      <div className="px-4 py-3 bg-sigdep-50 border-b border-sigdep-100 flex items-center justify-between">
        <h3 className="text-sm font-semibold text-sigdep-800">Initiations</h3>
        <span className="text-xs text-ink-muted">
          {records.data ? `${formatInt(records.data.total)} enregistrements` : "—"}
        </span>
      </div>
      <table className="w-full text-sm">
        <thead className="thead-sigdep text-left">
          <tr>
            <SortableTh k="date"    sort={sort} onSort={onSort}>Date init</SortableTh>
            <SortableTh k="patient" sort={sort} onSort={onSort}>Patient</SortableTh>
            <th className="px-4 py-2 font-medium">Porte d'entrée</th>
            <SortableTh k="regimen" sort={sort} onSort={onSort}>Régime initial</SortableTh>
            <SortableTh k="stage"   sort={sort} onSort={onSort}>Stade OMS</SortableTh>
            <th className="px-4 py-2 font-medium">Poids init</th>
            <th className="px-4 py-2 font-medium">Type VIH</th>
            <SortableTh k="site"    sort={sort} onSort={onSort}>Site</SortableTh>
          </tr>
        </thead>
        {records.isLoading ? <TableSkeleton rows={8} cols={8} /> : (
          <tbody className="divide-y divide-slate-100">
            {(() => {
              if (records.isError)
                return <tr><td colSpan={8} className="px-4 py-6 text-center text-rose-600">Erreur de chargement</td></tr>;
              if (!records.data || records.data.content.length === 0)
                return <tr><td colSpan={8} className="px-4 py-6 text-center text-ink-muted">Aucune initiation</td></tr>;
              return records.data.content.map(r => (
                <tr key={r.id} className="hover:bg-slate-50">
                  <td className="px-4 py-2 whitespace-nowrap text-ink-muted">{formatDate(r.arvInitDate)}</td>
                  <td className="px-4 py-2">
                    <Link to={`/app/patients/${r.patientId}`}
                          className="text-sigdep-700 hover:underline font-mono text-xs">
                      {r.patientCode ?? `#${r.patientId}`}
                    </Link>
                  </td>
                  <td className="px-4 py-2">{r.entryPoint ?? "—"}</td>
                  <td className="px-4 py-2">{r.arvRegimenInitial ?? "—"}</td>
                  <td className="px-4 py-2">{shortenWho(r.whoStageInitial)}</td>
                  <td className="px-4 py-2 text-right tabular-nums">{r.weightInitialKg ?? "—"}</td>
                  <td className="px-4 py-2">{r.hivType ?? "—"}</td>
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
              className="px-3 py-1 rounded border border-slate-300 disabled:opacity-50 hover:bg-slate-50">Précédent</button>
            <button onClick={() => setPage(p => p + 1)} disabled={records.data.page + 1 >= totalPages}
              className="px-3 py-1 rounded border border-slate-300 disabled:opacity-50 hover:bg-slate-50">Suivant</button>
          </div>
        </div>
      )}
    </div>
  </>;
}

// =============================================================================
// Closures panel — fiche de clôture (sortie de cohorte)
// =============================================================================
function ClosuresPanel({ months, scope }: { months: number; scope: GeoScope }) {
  const [sort, setSort] = useState<SortState>(null);
  const [page, setPage] = useState(0);
  const [exporting, setExporting] = useState(false);
  const size = 50;

  const summary = useQuery({
    queryKey: ["closure-summary", months, scope],
    queryFn: () => fetchClosureSummary(months, scope),
  });
  const records = useQuery({
    queryKey: ["closure-records", months, scope, sort, page],
    queryFn: () => fetchClosureRecords({ months, ...scope, sort, page, size }),
  });

  const onSort = (s: SortState) => { setSort(s); setPage(0); };
  const totalPages = records.data ? Math.max(1, Math.ceil(records.data.total / records.data.size)) : 1;

  async function handleExport() {
    setExporting(true);
    try { await downloadClosureCsv(months, scope); }
    catch (err) { /* eslint-disable-next-line no-console */ console.error(err); }
    finally { setExporting(false); }
  }

  return <>
    <div className="flex justify-end mb-3">
      <button onClick={handleExport}
        disabled={exporting || !records.data || records.data.total === 0}
        className="inline-flex items-center gap-1.5 rounded-md border border-slate-300 px-3 py-1.5 text-xs
                   hover:bg-slate-50 disabled:opacity-50 transition">
        <Download className="h-3.5 w-3.5" />
        {exporting ? "Export…" : "Exporter CSV"}
      </button>
    </div>

    {summary.isLoading ? <KpiRowSkeleton /> : (
      <div className="grid gap-3 grid-cols-2 lg:grid-cols-4 mb-6">
        <Kpi label="Clôtures (cumul)"
             value={summary.isError ? "Erreur" : formatInt(summary.data?.totalAllTime)}
             hint="Toutes périodes" hintTone="neutral" />
        <Kpi label="Période"
             value={summary.isError ? "Erreur" : formatInt(summary.data?.inPeriod)}
             hint={PERIODS.find(p => p.months === months)?.label} hintTone="neutral" />
        <Kpi label="Décès"
             value={summary.isError ? "Erreur" : formatInt(summary.data?.deaths)}
             hint="Clôtures pour décès" hintTone="warning" />
        <Kpi label="Mortalité (%)"
             value={summary.isError ? "Erreur" : formatPercent(summary.data?.mortalityPct ?? null)}
             hint="Décès / clôtures (période)" hintTone="warning" />
      </div>
    )}

    <div className="grid gap-3 lg:grid-cols-2 mb-6">
      <div className="card p-4 flex flex-col">
        <h3 className="text-sm font-medium mb-4">Clôtures par année</h3>
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
                             formatter={(v) => [formatInt(v as number), "Clôtures"]} />
                    <Bar dataKey="count" fill="#009d8e" radius={[3, 3, 0, 0]}>
                      <LabelList dataKey="count" position="top"
                                 style={{ fill: "#475569", fontSize: 11, fontWeight: 500 }} />
                    </Bar>
                  </BarChart>
                </ResponsiveContainer>
              )}
        </div>
      </div>

      <div className="card p-4">
        <DistributionCard title="Motif de clôture" rows={summary.data?.types ?? []} className="" />
        <DistributionCard title="Causes de décès (top 10)" rows={summary.data?.deathCauses ?? []} className="mt-5" />
      </div>
    </div>

    <div className="card overflow-hidden">
      <div className="px-4 py-3 bg-sigdep-50 border-b border-sigdep-100 flex items-center justify-between">
        <h3 className="text-sm font-semibold text-sigdep-800">Clôtures</h3>
        <span className="text-xs text-ink-muted">
          {records.data ? `${formatInt(records.data.total)} enregistrements` : "—"}
        </span>
      </div>
      <table className="w-full text-sm">
        <thead className="thead-sigdep text-left">
          <tr>
            <SortableTh k="date"    sort={sort} onSort={onSort}>Date</SortableTh>
            <SortableTh k="patient" sort={sort} onSort={onSort}>Patient</SortableTh>
            <SortableTh k="type"    sort={sort} onSort={onSort}>Motif</SortableTh>
            <th className="px-4 py-2 font-medium">Date décès</th>
            <th className="px-4 py-2 font-medium">Cause</th>
            <th className="px-4 py-2 font-medium">Destination transfert</th>
            <SortableTh k="site"    sort={sort} onSort={onSort}>Site</SortableTh>
          </tr>
        </thead>
        {records.isLoading ? <TableSkeleton rows={8} cols={7} /> : (
          <tbody className="divide-y divide-slate-100">
            {(() => {
              if (records.isError)
                return <tr><td colSpan={7} className="px-4 py-6 text-center text-rose-600">Erreur de chargement</td></tr>;
              if (!records.data || records.data.content.length === 0)
                return <tr><td colSpan={7} className="px-4 py-6 text-center text-ink-muted">Aucune clôture</td></tr>;
              return records.data.content.map(r => (
                <tr key={r.id} className="hover:bg-slate-50">
                  <td className="px-4 py-2 whitespace-nowrap text-ink-muted">{formatDate(r.closureDate)}</td>
                  <td className="px-4 py-2">
                    <Link to={`/app/patients/${r.patientId}`}
                          className="text-sigdep-700 hover:underline font-mono text-xs">
                      {r.patientCode ?? `#${r.patientId}`}
                    </Link>
                  </td>
                  <td className="px-4 py-2">{r.closureType ?? "—"}</td>
                  <td className="px-4 py-2 whitespace-nowrap text-ink-muted">{formatDate(r.deathDate)}</td>
                  <td className="px-4 py-2 text-ink-muted">{r.deathCauseText ?? r.deathCauseCode ?? "—"}</td>
                  <td className="px-4 py-2 text-ink-muted">{r.transferDestination ?? "—"}</td>
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
              className="px-3 py-1 rounded border border-slate-300 disabled:opacity-50 hover:bg-slate-50">Précédent</button>
            <button onClick={() => setPage(p => p + 1)} disabled={records.data.page + 1 >= totalPages}
              className="px-3 py-1 rounded border border-slate-300 disabled:opacity-50 hover:bg-slate-50">Suivant</button>
          </div>
        </div>
      )}
    </div>
  </>;
}

// =============================================================================
// IVSA panel — visites du sous-module "patient non stable"
// =============================================================================
function IvsaPanel({ months, scope }: { months: number; scope: GeoScope }) {
  const [sort, setSort] = useState<SortState>(null);
  const [page, setPage] = useState(0);
  const [exporting, setExporting] = useState(false);
  const size = 50;

  const summary = useQuery({
    queryKey: ["ivsa-summary", months, scope],
    queryFn: () => fetchIvsaSummary(months, scope),
  });
  const records = useQuery({
    queryKey: ["ivsa-visits", months, scope, sort, page],
    queryFn: () => fetchIvsaVisits({ months, ...scope, sort, page, size }),
  });

  const onSort = (s: SortState) => { setSort(s); setPage(0); };
  const totalPages = records.data ? Math.max(1, Math.ceil(records.data.total / records.data.size)) : 1;

  async function handleExport() {
    setExporting(true);
    try { await downloadIvsaCsv(months, scope); }
    catch (err) { /* eslint-disable-next-line no-console */ console.error(err); }
    finally { setExporting(false); }
  }

  return <>
    <div className="flex justify-end mb-3">
      <button onClick={handleExport}
        disabled={exporting || !records.data || records.data.total === 0}
        className="inline-flex items-center gap-1.5 rounded-md border border-slate-300 px-3 py-1.5 text-xs
                   hover:bg-slate-50 disabled:opacity-50 transition">
        <Download className="h-3.5 w-3.5" />
        {exporting ? "Export…" : "Exporter CSV"}
      </button>
    </div>

    {summary.isLoading ? <KpiRowSkeleton /> : (
      <div className="grid gap-3 grid-cols-2 lg:grid-cols-4 mb-6">
        <Kpi label="Visites IVSA (cumul)"
             value={summary.isError ? "Erreur" : formatInt(summary.data?.totalAllTime)}
             hint="Toutes périodes" hintTone="neutral" />
        <Kpi label="Période"
             value={summary.isError ? "Erreur" : formatInt(summary.data?.inPeriod)}
             hint={PERIODS.find(p => p.months === months)?.label} hintTone="neutral" />
        <Kpi label="Succès confirmés"
             value={summary.isError ? "Erreur" : formatInt(summary.data?.successConfirmed)}
             hint="Date de succès renseignée" hintTone="positive" />
        <Kpi label="Avec signes d'alerte"
             value={summary.isError ? "Erreur" : formatInt(summary.data?.withAlertSigns)}
             hint="≥ 1 signe coché à la visite" hintTone="warning" />
      </div>
    )}

    <div className="card p-4 mb-6">
      <h3 className="text-sm font-medium mb-3">Répartition modèle de soins (MSD)</h3>
      <table className="w-full text-sm">
        <thead className="text-ink-muted">
          <tr className="text-left">
            <th className="py-1 font-medium">Modèle</th>
            <th className="py-1 font-medium text-right">Nombre</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100">
          {(!summary.data || summary.data.msdDistribution.length === 0)
            ? <tr><td colSpan={2} className="py-2 text-center text-ink-muted">—</td></tr>
            : summary.data.msdDistribution.map(r => (
              <tr key={r.label}>
                <td className="py-1">{r.label}</td>
                <td className="py-1 text-right tabular-nums">{formatInt(r.count)}</td>
              </tr>
            ))}
        </tbody>
      </table>
    </div>

    <div className="card overflow-hidden">
      <div className="px-4 py-3 bg-sigdep-50 border-b border-sigdep-100 flex items-center justify-between">
        <h3 className="text-sm font-semibold text-sigdep-800">Visites IVSA</h3>
        <span className="text-xs text-ink-muted">
          {records.data ? `${formatInt(records.data.total)} visites` : "—"}
        </span>
      </div>
      <table className="w-full text-sm">
        <thead className="thead-sigdep text-left">
          <tr>
            <SortableTh k="date"    sort={sort} onSort={onSort}>Date</SortableTh>
            <SortableTh k="patient" sort={sort} onSort={onSort}>Patient</SortableTh>
            <th className="px-4 py-2 font-medium">Date succès</th>
            <SortableTh k="alerts"  sort={sort} onSort={onSort} align="right">Signes alerte</SortableTh>
            <SortableTh k="neuro"   sort={sort} onSort={onSort} align="right">Signes neuro</SortableTh>
            <th className="px-4 py-2 font-medium">Poids</th>
            <th className="px-4 py-2 font-medium">Temp.</th>
            <th className="px-4 py-2 font-medium">Prochain RDV</th>
            <SortableTh k="site"    sort={sort} onSort={onSort}>Site</SortableTh>
          </tr>
        </thead>
        {records.isLoading ? <TableSkeleton rows={8} cols={9} /> : (
          <tbody className="divide-y divide-slate-100">
            {(() => {
              if (records.isError)
                return <tr><td colSpan={9} className="px-4 py-6 text-center text-rose-600">Erreur de chargement</td></tr>;
              if (!records.data || records.data.content.length === 0)
                return <tr><td colSpan={9} className="px-4 py-6 text-center text-ink-muted">Aucune visite IVSA</td></tr>;
              return records.data.content.map(r => (
                <tr key={r.id} className="hover:bg-slate-50">
                  <td className="px-4 py-2 whitespace-nowrap text-ink-muted">{formatDate(r.visitDate)}</td>
                  <td className="px-4 py-2">
                    <Link to={`/app/patients/${r.patientId}`}
                          className="text-sigdep-700 hover:underline font-mono text-xs">
                      {r.patientCode ?? `#${r.patientId}`}
                    </Link>
                  </td>
                  <td className="px-4 py-2 whitespace-nowrap text-ink-muted">{formatDate(r.successConfirmationDate)}</td>
                  <td className="px-4 py-2 text-right tabular-nums">{r.alertSignsCount ?? "—"}</td>
                  <td className="px-4 py-2 text-right tabular-nums">{r.neuroSignsCount ?? "—"}</td>
                  <td className="px-4 py-2 text-right tabular-nums">{r.weightKg ?? "—"}</td>
                  <td className="px-4 py-2 text-right tabular-nums">{r.temperatureC ?? "—"}</td>
                  <td className="px-4 py-2 whitespace-nowrap text-ink-muted">{formatDate(r.nextVisitDate)}</td>
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
              className="px-3 py-1 rounded border border-slate-300 disabled:opacity-50 hover:bg-slate-50">Précédent</button>
            <button onClick={() => setPage(p => p + 1)} disabled={records.data.page + 1 >= totalPages}
              className="px-3 py-1 rounded border border-slate-300 disabled:opacity-50 hover:bg-slate-50">Suivant</button>
          </div>
        </div>
      )}
    </div>
  </>;
}
