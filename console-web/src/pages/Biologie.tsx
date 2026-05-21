import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Link } from "react-router-dom";
import {
  Bar,
  BarChart,
  LabelList,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import {
  ExamFilter,
  ExamRow,
  downloadBiologyCsv,
  fetchBiologyExams,
  fetchBiologySummary,
} from "../api/client";
import { Download, Microscope } from "lucide-react";
import { CardHeader } from "../components/CardHeader";
import { GeoFilter, GeoScope } from "../components/GeoFilter";
import { Kpi, formatInt, formatPercent } from "../components/Kpi";
import { PageHeader } from "../components/PageHeader";
import { SortableTh, SortState } from "../components/SortableTh";
import { ChartSkeleton, KpiRowSkeleton, TableSkeleton } from "../components/Skeleton";

const PERIODS = [
  { months: 12, label: "12 derniers mois" },
  { months: 24, label: "24 derniers mois" },
  { months: 60, label: "5 dernières années" },
];

const TABS: { value: ExamFilter; label: string }[] = [
  { value: "vl", label: "Charge virale" },
  { value: "cd4", label: "CD4" },
];

function formatDate(iso: string | null): string {
  if (!iso) return "—";
  return new Date(iso).toLocaleDateString("fr-FR", {
    day: "2-digit",
    month: "short",
    year: "numeric",
  });
}

function formatExamValue(e: ExamRow): string {
  if (e.valueNumeric != null) {
    return e.unit ? `${e.valueNumeric} ${e.unit}` : String(e.valueNumeric);
  }
  return e.valueText ?? "—";
}

export function Biologie() {
  const [months, setMonths] = useState(12);
  const [scope, setScope] = useState<GeoScope>({});
  const [tab, setTab] = useState<ExamFilter>("vl");
  const [sort, setSort] = useState<SortState>(null);
  const [page, setPage] = useState(0);
  const [exporting, setExporting] = useState(false);
  const size = 50;

  const summary = useQuery({
    queryKey: ["biology-summary", months, scope],
    queryFn: () => fetchBiologySummary(months, scope),
  });

  const exams = useQuery({
    queryKey: ["biology-exams", tab, months, scope, sort, page],
    queryFn: () =>
      fetchBiologyExams({ test: tab, months, ...scope, sort, page, size }),
  });

  const onSort = (s: SortState) => { setSort(s); setPage(0); };

  const cd4 = summary.data?.cd4Distribution;
  const cd4Bars =
    cd4 && cd4.total > 0
      ? [
          { bucket: "< 200", count: cd4.lt200 },
          { bucket: "200–350", count: cd4.b200_350 },
          { bucket: "350–500", count: cd4.b350_500 },
          { bucket: "≥ 500", count: cd4.ge500 },
        ]
      : [];

  const totalPages = exams.data
    ? Math.max(1, Math.ceil(exams.data.total / exams.data.size))
    : 1;

  async function handleExport() {
    setExporting(true);
    try {
      await downloadBiologyCsv({ test: tab, months, ...scope });
    } catch (err) {
      // eslint-disable-next-line no-console
      console.error(err);
    } finally {
      setExporting(false);
    }
  }

  return (
    <div className="px-6 py-6">
      <PageHeader
        icon={Microscope}
        title="Biologie"
        subtitle="Examens biologiques · charge virale & CD4"
        right={
          <>
            <GeoFilter
              value={scope}
              onChange={(s) => {
                setScope(s);
                setPage(0);
              }}
            />
            <select
              value={months}
              onChange={(e) => {
                setMonths(Number(e.target.value));
                setPage(0);
              }}
              className="rounded-md border border-slate-300 px-3 py-2 text-sm bg-white"
            >
              {PERIODS.map((p) => (
                <option key={p.months} value={p.months}>
                  {p.label}
                </option>
              ))}
            </select>
          </>
        }
      />

      {/* KPIs */}
      {summary.isLoading ? <KpiRowSkeleton /> : (
        <div className="grid gap-3 grid-cols-2 lg:grid-cols-4 mb-6">
          <Kpi
            label="Examens (cumul)"
            value={summary.isError ? "Erreur" : formatInt(summary.data?.examsAllTime)}
            hint="Toutes périodes"
            hintTone="neutral"
          />
          <Kpi
            label="Examens (période)"
            value={summary.isError ? "Erreur" : formatInt(summary.data?.examsInPeriod)}
            hint={`${PERIODS.find((p) => p.months === months)?.label}`}
            hintTone="neutral"
          />
          <Kpi
            label="Suppression virale"
            value={summary.isError
                ? "Erreur"
                : formatPercent(summary.data?.viralSuppressionPct ?? null)}
            hint="CV < 1000 copies/mL"
            hintTone="positive"
          />
          <Kpi
            label="Dernier examen"
            value={formatDate(summary.data?.lastExamDate ?? null)}
            hint="Date la plus récente"
            hintTone="neutral"
          />
        </div>
      )}

      {/* Two-column charts */}
      <div className="grid gap-3 lg:grid-cols-2 mb-6">
        <div className="card">
          <CardHeader title="Suppression virale par mois · % < 1000 copies/mL" />
          <div className="h-56 p-4">
            {summary.isLoading ? (
              <ChartSkeleton height="h-56" />
            ) : (
              <ResponsiveContainer width="100%" height="100%">
                <LineChart
                  data={summary.data?.monthlySuppression ?? []}
                  margin={{ top: 8, right: 8, left: 0, bottom: 0 }}
                >
                  <XAxis
                    dataKey="month"
                    tick={{ fontSize: 11 }}
                    stroke="#94a3b8"
                  />
                  <YAxis
                    domain={[0, 100]}
                    tick={{ fontSize: 11 }}
                    stroke="#94a3b8"
                  />
                  <Tooltip
                    contentStyle={{ borderRadius: 6, fontSize: 12 }}
                    formatter={(value, _name, item) => {
                      const v = value as number | null;
                      const p = (
                        item as
                          | {
                              payload?: { total?: number; suppressed?: number };
                            }
                          | undefined
                      )?.payload;
                      if (v == null) return ["—", "%"];
                      return [`${v} % (${p?.suppressed}/${p?.total})`, "%"];
                    }}
                  />
                  <Line
                    type="monotone"
                    dataKey="pct"
                    stroke="#009d8e"
                    strokeWidth={2}
                    dot={{ r: 3 }}
                    connectNulls
                  />
                </LineChart>
              </ResponsiveContainer>
            )}
          </div>
        </div>

        <div className="card">
          <CardHeader
            title="Distribution CD4 (cellules/µL)"
            subtitle={
              cd4 && cd4.total > 0
                ? `${formatInt(cd4.total)} examens sur la période`
                : "Aucun examen CD4 sur la période"
            }
          />
          <div className="h-48 p-4">
            {cd4Bars.length === 0 ? (
              <div className="h-full flex items-center justify-center text-ink-muted text-sm">
                —
              </div>
            ) : (
              <ResponsiveContainer width="100%" height="100%">
                <BarChart
                  data={cd4Bars}
                  margin={{ top: 24, right: 8, left: 0, bottom: 0 }}
                >
                  <XAxis
                    dataKey="bucket"
                    tick={{ fontSize: 11 }}
                    stroke="#94a3b8"
                  />
                  <YAxis tick={{ fontSize: 11 }} stroke="#94a3b8" />
                  <Tooltip
                    contentStyle={{ borderRadius: 6, fontSize: 12 }}
                    formatter={(v) => [formatInt(v as number), "Examens"]}
                  />
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

      {/* Exams table with tabs */}
      <div className="card overflow-hidden">
        <div className="px-4 pt-3 flex items-center justify-between gap-2 flex-wrap bg-sigdep-50 border-b border-sigdep-100">
          <div className="flex gap-1">
            {TABS.map((t) => (
              <button
                key={t.value}
                onClick={() => {
                  setTab(t.value);
                  setPage(0);
                }}
                className={`px-3 py-2 text-sm border-b-2 transition -mb-px ${
                  tab === t.value
                    ? "border-sigdep-500 text-sigdep-700 font-medium"
                    : "border-transparent text-ink-muted hover:text-ink"
                }`}
              >
                {t.label}
              </button>
            ))}
          </div>
          <div className="flex items-center gap-3 pb-2">
            <span className="text-xs text-ink-muted">
              {exams.data ? `${formatInt(exams.data.total)} examens` : "—"}
            </span>
            <button
              onClick={handleExport}
              disabled={exporting || !exams.data || exams.data.total === 0}
              className="inline-flex items-center gap-1.5 rounded-md border border-slate-300 px-3 py-1.5 text-xs
                         hover:bg-slate-50 disabled:opacity-50 transition"
            >
              <Download className="h-3.5 w-3.5" />
              {exporting ? "Export…" : "Exporter CSV"}
            </button>
          </div>
        </div>

        <table className="w-full text-sm">
          <thead className="thead-sigdep text-left">
            <tr className="text-left">
              <SortableTh k="date"    sort={sort} onSort={onSort}>Date</SortableTh>
              <SortableTh k="patient" sort={sort} onSort={onSort}>Patient</SortableTh>
              {tab === "cd4" ? (
                <>
                  <th className="px-4 py-2 font-medium text-right">
                    CD4 abs <span className="text-ink-subtle">(cell/µL)</span>
                  </th>
                  <th className="px-4 py-2 font-medium text-right">CD4 %</th>
                </>
              ) : (
                <>
                  <SortableTh k="testName" sort={sort} onSort={onSort}>Examen</SortableTh>
                  <SortableTh k="value"    sort={sort} onSort={onSort} align="right">Valeur</SortableTh>
                </>
              )}
              <SortableTh k="site" sort={sort} onSort={onSort}>Site</SortableTh>
            </tr>
          </thead>
          {exams.isLoading ? (
            <TableSkeleton rows={8} cols={5} />
          ) : (
          <tbody className="divide-y divide-slate-100">
            {(() => {
              const colSpan = tab === "cd4" ? 5 : 5;
              if (exams.isError) {
                return (
                  <tr>
                    <td
                      colSpan={colSpan}
                      className="px-4 py-6 text-center text-rose-600"
                    >
                      Erreur de chargement
                    </td>
                  </tr>
                );
              }
              if (!exams.data || exams.data.content.length === 0) {
                return (
                  <tr>
                    <td
                      colSpan={colSpan}
                      className="px-4 py-6 text-center text-ink-muted"
                    >
                      Aucun examen
                    </td>
                  </tr>
                );
              }
              return exams.data.content.map((e) => (
                <tr key={e.id} className="hover:bg-slate-50">
                  <td className="px-4 py-2 whitespace-nowrap text-ink-muted">
                    {formatDate(e.examDate)}
                  </td>
                  <td className="px-4 py-2">
                    <Link
                      to={`/app/patients/${e.patientId}`}
                      className="text-sigdep-700 hover:underline font-mono text-xs"
                    >
                      {e.patientCode ?? `#${e.patientId}`}
                    </Link>
                  </td>
                  {tab === "cd4" ? (
                    <>
                      <td className="px-4 py-2 text-right tabular-nums">
                        {e.valueNumeric != null ? e.valueNumeric : "—"}
                      </td>
                      <td className="px-4 py-2 text-right tabular-nums">
                        {e.valuePct != null ? `${e.valuePct} %` : "—"}
                      </td>
                    </>
                  ) : (
                    <>
                      <td className="px-4 py-2">{e.testName}</td>
                      <td className="px-4 py-2 text-right tabular-nums">
                        {formatExamValue(e)}
                      </td>
                    </>
                  )}
                  <td className="px-4 py-2 text-ink-muted">
                    <span className="font-mono text-xs">{e.siteCode}</span>{" "}
                    {e.siteName}
                  </td>
                </tr>
              ));
            })()}
          </tbody>
          )}
        </table>

        {exams.data && exams.data.total > size && (
          <div className="px-4 py-3 flex items-center justify-between text-sm border-t border-slate-100">
            <p className="text-ink-muted">
              Page {exams.data.page + 1} / {totalPages}
            </p>
            <div className="flex gap-2">
              <button
                onClick={() => setPage((p) => Math.max(0, p - 1))}
                disabled={exams.data.page === 0}
                className="px-3 py-1 rounded border border-slate-300 disabled:opacity-50 hover:bg-slate-50"
              >
                Précédent
              </button>
              <button
                onClick={() => setPage((p) => p + 1)}
                disabled={exams.data.page + 1 >= totalPages}
                className="px-3 py-1 rounded border border-slate-300 disabled:opacity-50 hover:bg-slate-50"
              >
                Suivant
              </button>
            </div>
          </div>
        )}
      </div>

      {/* Top tests (kept as a small reference at the bottom) */}
      {summary.data && summary.data.topTests.length > 0 && (
        <div className="card p-4 mt-6">
          <h3 className="text-sm font-medium mb-3">
            Top examens (toutes catégories) &middot; période
          </h3>
          <table className="w-full text-sm">
            <thead className="text-ink-muted">
              <tr className="text-left">
                <th className="py-1 font-medium">Examen</th>
                <th className="py-1 font-medium text-right">Nombre</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {summary.data.topTests.map((t) => (
                <tr key={t.testName}>
                  <td className="py-1">{t.testName}</td>
                  <td className="py-1 text-right tabular-nums">
                    {formatInt(t.count)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
