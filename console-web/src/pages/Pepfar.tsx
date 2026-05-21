import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import {
  Cell, Legend, Pie, PieChart, ResponsiveContainer, Tooltip,
} from "recharts";
import {
  Disaggregated,
  Hts,
  MsdBucket,
  Pair,
  TxPvls,
  downloadPepfarCsv,
  fetchPepfarReport,
} from "../api/client";
import { BarChart3, Download } from "lucide-react";
import { Kpi, formatInt, formatPercent } from "../components/Kpi";
import { PageHeader } from "../components/PageHeader";
import { GeoFilter, GeoScope } from "../components/GeoFilter";
import { KpiRowSkeleton, ListSkeleton } from "../components/Skeleton";

const AGE_BANDS = ["<15", "15-24", "25-49", "50+", "unknown"] as const;
const SEXES: ReadonlyArray<{ key: "M" | "F"; label: string }> = [
  { key: "M", label: "Hommes" },
  { key: "F", label: "Femmes" },
];

function currentDefaultQuarter(): { fy: number; q: number } {
  // PEPFAR fiscal year starts Oct 1. Pick the most recently completed quarter.
  const now = new Date();
  const m = now.getMonth() + 1; // 1..12
  const y = now.getFullYear();
  if (m >= 10) return { fy: y + 1, q: 1 }; // Oct-Dec → Q1 of FY (y+1)
  if (m >= 7) return { fy: y, q: 4 }; // Jul-Sep
  if (m >= 4) return { fy: y, q: 3 }; // Apr-Jun
  if (m >= 1) return { fy: y, q: 2 }; // Jan-Mar
  return { fy: y, q: 1 };
}

function fyOptions(): number[] {
  const cur = currentDefaultQuarter();
  // Show 5 years back from current fiscal year.
  const out: number[] = [];
  for (let i = 0; i < 6; i++) out.push(cur.fy - i);
  return out;
}

function formatDateFr(iso: string): string {
  return new Date(iso).toLocaleDateString("fr-FR", {
    day: "2-digit",
    month: "short",
    year: "numeric",
  });
}

function buildMatrix(d: Disaggregated): {
  byBand: Record<string, Record<string, number>>;
  sexTotals: Record<string, number>;
  bandTotals: Record<string, number>;
} {
  const byBand: Record<string, Record<string, number>> = {};
  const sexTotals: Record<string, number> = { M: 0, F: 0, unknown: 0 };
  const bandTotals: Record<string, number> = {};
  for (const band of AGE_BANDS) {
    byBand[band] = { M: 0, F: 0, unknown: 0 };
    bandTotals[band] = 0;
  }
  for (const c of d.cells) {
    const sexKey = c.sex === "M" || c.sex === "F" ? c.sex : "unknown";
    const band = (AGE_BANDS as readonly string[]).includes(c.ageBand)
      ? c.ageBand
      : "unknown";
    byBand[band][sexKey] = (byBand[band][sexKey] ?? 0) + c.count;
    sexTotals[sexKey] = (sexTotals[sexKey] ?? 0) + c.count;
    bandTotals[band] = (bandTotals[band] ?? 0) + c.count;
  }
  return { byBand, sexTotals, bandTotals };
}

export function Pepfar() {
  const def = currentDefaultQuarter();
  const [fy, setFy] = useState(def.fy);
  const [q, setQ] = useState(def.q);
  const [scope, setScope] = useState<GeoScope>({});
  const [exporting, setExporting] = useState(false);

  const report = useQuery({
    queryKey: ["pepfar", fy, q, scope],
    queryFn: () => fetchPepfarReport(fy, q, scope),
  });

  async function handleExport() {
    setExporting(true);
    try {
      await downloadPepfarCsv(fy, q, scope);
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
        icon={BarChart3}
        title="Indicateurs PEPFAR"
        subtitle={<>
          TX_NEW · TX_CURR · TX_PVLS · Trimestre Fiscal PEPFAR
          {report.data && <> · au {formatDateFr(report.data.period.end)}</>}
        </>}
        right={<>
          <GeoFilter value={scope} onChange={setScope} />
          <select
            value={fy}
            onChange={(e) => setFy(Number(e.target.value))}
            className="rounded-md border border-slate-300 px-3 py-2 text-sm bg-white"
          >
            {fyOptions().map((y) => (
              <option key={y} value={y}>FY{y}</option>
            ))}
          </select>
          <select
            value={q}
            onChange={(e) => setQ(Number(e.target.value))}
            className="rounded-md border border-slate-300 px-3 py-2 text-sm bg-white"
          >
            <option value={1}>Q1 (oct-déc)</option>
            <option value={2}>Q2 (jan-mar)</option>
            <option value={3}>Q3 (avr-juin)</option>
            <option value={4}>Q4 (juil-sep)</option>
          </select>
          <button
            onClick={handleExport}
            disabled={exporting || !report.data}
            className="inline-flex items-center gap-1.5 rounded-md border border-slate-300 px-3 py-1.5 text-xs
                       hover:bg-slate-50 disabled:opacity-50 transition"
          >
            <Download className="h-3.5 w-3.5" />
            {exporting ? "Export…" : "Exporter CSV"}
          </button>
        </>} />

      {/* Cascade TX — KPI */}
      {report.isLoading ? <KpiRowSkeleton /> : (
        <div className="grid gap-3 grid-cols-2 lg:grid-cols-4 mb-6">
          <Kpi
            label="TX_NEW"
            value={report.isError ? "Erreur" : formatInt(report.data?.txNew.total)}
            hint="Nouvelles initiations ARV"
            hintTone="neutral"
          />
          <Kpi
            label="TX_CURR"
            value={report.isError ? "Erreur" : formatInt(report.data?.txCurr.total)}
            hint="Sous traitement (fin du trimestre)"
            hintTone="neutral"
          />
          <Kpi
            label="TX_PVLS (D)"
            value={report.isError
                ? "Erreur"
                : formatInt(report.data?.txPvls.denominator.total)}
            hint="Éligibles à un test CV (12 mois)"
            hintTone="neutral"
          />
          <Kpi
            label="TX_PVLS (%)"
            value={report.isError
                ? "Erreur"
                : formatPercent(report.data?.txPvls.pct ?? null)}
            hint="CV < 1000 copies/mL"
            hintTone="positive"
          />
        </div>
      )}

      {/* HTS + PMTCT + TB_PREV — KPI */}
      {!report.isLoading && report.data && (
        <div className="grid gap-3 grid-cols-2 lg:grid-cols-4 mb-6">
          <Kpi
            label="HTS_TST"
            value={formatInt(report.data.hts.tst.total)}
            hint="Personnes dépistées"
            hintTone="neutral"
          />
          <Kpi
            label="HTS_POS"
            value={formatInt(report.data.hts.pos.total)}
            hint={`Positivité : ${formatPercent(report.data.hts.positivityPct ?? null)}`}
            hintTone="warning"
          />
          <Kpi
            label="PMTCT_ART (%)"
            value={formatPercent(report.data.pmtct.art.pct ?? null)}
            hint={`${formatInt(report.data.pmtct.art.numerator.total)} sous ARV / ${formatInt(report.data.pmtct.art.denominator.total)} VIH+`}
            hintTone="positive"
          />
          <Kpi
            label="TB_PREV (%)"
            value={formatPercent(report.data.tbPrev.pct ?? null)}
            hint={`${formatInt(report.data.tbPrev.numerator.total)} terminés / ${formatInt(report.data.tbPrev.denominator.total)}`}
            hintTone="positive"
          />
        </div>
      )}

      {/* File active par modèle de soins différenciés (donut) */}
      {report.data && report.data.txCurrByMsd.length > 0 && (
        <div className="card p-4 mb-6">
          <h3 className="text-sm font-medium mb-3">
            File active par modèle de soin
          </h3>
          <MsdDonut buckets={report.data.txCurrByMsd} />
        </div>
      )}

      {/* Disaggregation tables */}
      {report.isLoading && <ListSkeleton rows={6} />}
      {report.data && (
        <div className="space-y-6">
          <DisaggTable
            title="TX_NEW — Nouvelles initiations ARV"
            data={report.data.txNew}
          />
          <DisaggTable
            title="TX_CURR — Sous traitement à la fin du trimestre"
            data={report.data.txCurr}
          />
          <PvlsTable pvls={report.data.txPvls} />
          <HtsTable hts={report.data.hts} />
          <PairTable
            title="PMTCT_STAT — Statut VIH connu chez la femme enceinte"
            ratioLabel="connu"
            pair={report.data.pmtct.stat}
          />
          <PairTable
            title="PMTCT_ART — Femmes enceintes VIH+ sous ARV"
            ratioLabel="sous ARV"
            pair={report.data.pmtct.art}
          />
          <PairTable
            title="PMTCT_EID — Enfants exposés avec PCR1 ≤ 2 mois"
            ratioLabel="PCR1 précoce"
            pair={report.data.pmtct.eid}
          />
          <PairTable
            title="TB_PREV — TPT terminé pendant le trimestre"
            ratioLabel="terminés"
            pair={report.data.tbPrev}
          />
        </div>
      )}
    </div>
  );
}

function DisaggTable({ title, data }: { title: string; data: Disaggregated }) {
  const { byBand, sexTotals, bandTotals } = buildMatrix(data);
  return (
    <div className="card overflow-hidden">
      <div className="px-4 py-3 border-b border-slate-200">
        <h3 className="text-sm font-medium">{title}</h3>
      </div>
      <table className="w-full text-sm">
        <thead className="thead-sigdep text-left">
          <tr>
            <th className="px-4 py-2 text-left font-medium">Tranche d’âge</th>
            {SEXES.map((s) => (
              <th key={s.key} className="px-4 py-2 text-right font-medium">
                {s.label}
              </th>
            ))}
            <th className="px-4 py-2 text-right font-medium">Total</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100">
          {AGE_BANDS.filter((b) => bandTotals[b] > 0 || b !== "unknown").map(
            (band) => (
              <tr key={band} className="hover:bg-slate-50">
                <td className="px-4 py-2">{band}</td>
                {SEXES.map((s) => (
                  <td key={s.key} className="px-4 py-2 text-right tabular-nums">
                    {formatInt(byBand[band][s.key] ?? 0)}
                  </td>
                ))}
                <td className="px-4 py-2 text-right tabular-nums font-medium">
                  {formatInt(bandTotals[band] ?? 0)}
                </td>
              </tr>
            ),
          )}
          <tr className="bg-slate-50 font-medium">
            <td className="px-4 py-2">Total</td>
            {SEXES.map((s) => (
              <td key={s.key} className="px-4 py-2 text-right tabular-nums">
                {formatInt(sexTotals[s.key] ?? 0)}
              </td>
            ))}
            <td className="px-4 py-2 text-right tabular-nums">
              {formatInt(data.total)}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  );
}

function PvlsTable({ pvls }: { pvls: TxPvls }) {
  const denom = buildMatrix(pvls.denominator);
  const numer = buildMatrix(pvls.numerator);
  return (
    <div className="card overflow-hidden">
      <div className="px-4 py-3 border-b border-slate-200">
        <h3 className="text-sm font-medium">
          TX_PVLS — Suppression virale &middot; numérateur / dénominateur (%)
        </h3>
      </div>
      <table className="w-full text-sm">
        <thead className="thead-sigdep text-left">
          <tr>
            <th className="px-4 py-2 text-left font-medium">Tranche d’âge</th>
            {SEXES.map((s) => (
              <th key={s.key} className="px-4 py-2 text-right font-medium">
                {s.label}
              </th>
            ))}
            <th className="px-4 py-2 text-right font-medium">Total</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100">
          {AGE_BANDS.filter(
            (b) => denom.bandTotals[b] > 0 || b !== "unknown",
          ).map((band) => (
            <tr key={band} className="hover:bg-slate-50">
              <td className="px-4 py-2">{band}</td>
              {SEXES.map((s) => {
                const d = denom.byBand[band][s.key] ?? 0;
                const n = numer.byBand[band][s.key] ?? 0;
                return (
                  <td key={s.key} className="px-4 py-2 text-right tabular-nums">
                    {formatInt(n)} / {formatInt(d)}
                    {d > 0 && (
                      <span className="text-ink-muted text-xs ml-1">
                        ({Math.round((n / d) * 100)}%)
                      </span>
                    )}
                  </td>
                );
              })}
              <td className="px-4 py-2 text-right tabular-nums">
                {formatInt(numer.bandTotals[band] ?? 0)} /{" "}
                {formatInt(denom.bandTotals[band] ?? 0)}
              </td>
            </tr>
          ))}
          <tr className="bg-slate-50 font-medium">
            <td className="px-4 py-2">Total</td>
            {SEXES.map((s) => {
              const d = denom.sexTotals[s.key] ?? 0;
              const n = numer.sexTotals[s.key] ?? 0;
              return (
                <td key={s.key} className="px-4 py-2 text-right tabular-nums">
                  {formatInt(n)} / {formatInt(d)}
                  {d > 0 && (
                    <span className="text-emerald-700 text-xs ml-1">
                      ({Math.round((n / d) * 1000) / 10}%)
                    </span>
                  )}
                </td>
              );
            })}
            <td className="px-4 py-2 text-right tabular-nums">
              {formatInt(pvls.numerator.total)} /{" "}
              {formatInt(pvls.denominator.total)}
              {pvls.pct !== null && (
                <span className="text-emerald-700 text-xs ml-1">
                  ({pvls.pct}%)
                </span>
              )}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  );
}

/**
 * Generic numerator/denominator table — used for HTS_POS over HTS_TST,
 * PMTCT_STAT/ART/EID and TB_PREV. Mirrors the PvlsTable layout but
 * with a configurable ratioLabel so the header reads naturally
 * ("connu" / "sous ARV" / "PCR1 précoce" / "terminés").
 */
function PairTable({
  title,
  ratioLabel,
  pair,
}: {
  title: string;
  ratioLabel: string;
  pair: Pair;
}) {
  const denom = buildMatrix(pair.denominator);
  const numer = buildMatrix(pair.numerator);
  return (
    <div className="card overflow-hidden">
      <div className="px-4 py-3 border-b border-slate-200">
        <h3 className="text-sm font-medium">
          {title} &middot; {ratioLabel} / total (%)
        </h3>
      </div>
      <table className="w-full text-sm">
        <thead className="thead-sigdep text-left">
          <tr>
            <th className="px-4 py-2 text-left font-medium">Tranche d’âge</th>
            {SEXES.map((s) => (
              <th key={s.key} className="px-4 py-2 text-right font-medium">
                {s.label}
              </th>
            ))}
            <th className="px-4 py-2 text-right font-medium">Total</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100">
          {AGE_BANDS.filter(
            (b) => denom.bandTotals[b] > 0 || b !== "unknown",
          ).map((band) => (
            <tr key={band} className="hover:bg-slate-50">
              <td className="px-4 py-2">{band}</td>
              {SEXES.map((s) => {
                const d = denom.byBand[band][s.key] ?? 0;
                const n = numer.byBand[band][s.key] ?? 0;
                return (
                  <td key={s.key} className="px-4 py-2 text-right tabular-nums">
                    {formatInt(n)} / {formatInt(d)}
                    {d > 0 && (
                      <span className="text-ink-muted text-xs ml-1">
                        ({Math.round((n / d) * 100)}%)
                      </span>
                    )}
                  </td>
                );
              })}
              <td className="px-4 py-2 text-right tabular-nums">
                {formatInt(numer.bandTotals[band] ?? 0)} /{" "}
                {formatInt(denom.bandTotals[band] ?? 0)}
              </td>
            </tr>
          ))}
          <tr className="bg-slate-50 font-medium">
            <td className="px-4 py-2">Total</td>
            {SEXES.map((s) => {
              const d = denom.sexTotals[s.key] ?? 0;
              const n = numer.sexTotals[s.key] ?? 0;
              return (
                <td key={s.key} className="px-4 py-2 text-right tabular-nums">
                  {formatInt(n)} / {formatInt(d)}
                  {d > 0 && (
                    <span className="text-emerald-700 text-xs ml-1">
                      ({Math.round((n / d) * 1000) / 10}%)
                    </span>
                  )}
                </td>
              );
            })}
            <td className="px-4 py-2 text-right tabular-nums">
              {formatInt(pair.numerator.total)} /{" "}
              {formatInt(pair.denominator.total)}
              {pair.pct !== null && (
                <span className="text-emerald-700 text-xs ml-1">
                  ({pair.pct}%)
                </span>
              )}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  );
}

/**
 * HTS — two side-by-side disaggregated counts: HTS_TST (total tested)
 * and HTS_POS (positives), with positivity rate at the row/column level.
 */
function HtsTable({ hts }: { hts: Hts }) {
  const tst = buildMatrix(hts.tst);
  const pos = buildMatrix(hts.pos);
  return (
    <div className="card overflow-hidden">
      <div className="px-4 py-3 border-b border-slate-200">
        <h3 className="text-sm font-medium">
          HTS_TST / HTS_POS — Dépistage VIH &middot; positifs / dépistés (%)
        </h3>
      </div>
      <table className="w-full text-sm">
        <thead className="thead-sigdep text-left">
          <tr>
            <th className="px-4 py-2 text-left font-medium">Tranche d’âge</th>
            {SEXES.map((s) => (
              <th key={s.key} className="px-4 py-2 text-right font-medium">
                {s.label}
              </th>
            ))}
            <th className="px-4 py-2 text-right font-medium">Total</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100">
          {AGE_BANDS.filter(
            (b) => tst.bandTotals[b] > 0 || b !== "unknown",
          ).map((band) => (
            <tr key={band} className="hover:bg-slate-50">
              <td className="px-4 py-2">{band}</td>
              {SEXES.map((s) => {
                const t = tst.byBand[band][s.key] ?? 0;
                const p = pos.byBand[band][s.key] ?? 0;
                return (
                  <td key={s.key} className="px-4 py-2 text-right tabular-nums">
                    {formatInt(p)} / {formatInt(t)}
                    {t > 0 && (
                      <span className="text-rose-700 text-xs ml-1">
                        ({Math.round((p / t) * 100)}%)
                      </span>
                    )}
                  </td>
                );
              })}
              <td className="px-4 py-2 text-right tabular-nums">
                {formatInt(pos.bandTotals[band] ?? 0)} /{" "}
                {formatInt(tst.bandTotals[band] ?? 0)}
              </td>
            </tr>
          ))}
          <tr className="bg-slate-50 font-medium">
            <td className="px-4 py-2">Total</td>
            {SEXES.map((s) => {
              const t = tst.sexTotals[s.key] ?? 0;
              const p = pos.sexTotals[s.key] ?? 0;
              return (
                <td key={s.key} className="px-4 py-2 text-right tabular-nums">
                  {formatInt(p)} / {formatInt(t)}
                  {t > 0 && (
                    <span className="text-rose-700 text-xs ml-1">
                      ({Math.round((p / t) * 1000) / 10}%)
                    </span>
                  )}
                </td>
              );
            })}
            <td className="px-4 py-2 text-right tabular-nums">
              {formatInt(hts.pos.total)} / {formatInt(hts.tst.total)}
              {hts.positivityPct !== null && (
                <span className="text-rose-700 text-xs ml-1">
                  ({hts.positivityPct}%)
                </span>
              )}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  );
}

/**
 * Donut showing the active cohort split by MSD (Modèle de soins
 * différenciés) : Standard / IVSA / Échec thérapeutique. Empty buckets
 * are filtered out so the legend stays compact.
 */
const MSD_COLOURS: Record<string, string> = {
  "Standard":             "#009d8e",
  "IVSA":                 "#f59e0b",
  "Échec thérapeutique":  "#dc2626",
  "(non renseigné)":      "#94a3b8",
};

function MsdDonut({ buckets }: { buckets: MsdBucket[] }) {
  const total = buckets.reduce((s, b) => s + b.count, 0);
  const data = buckets.map((b) => ({
    name: b.msd,
    value: b.count,
    color: MSD_COLOURS[b.msd] ?? "#64748b",
  }));
  return (
    <div className="flex items-center gap-6">
      <div className="h-56 flex-1 min-w-0">
        <ResponsiveContainer width="100%" height="100%">
          <PieChart>
            <Pie
              data={data}
              dataKey="value"
              nameKey="name"
              innerRadius={45}
              outerRadius={80}
              paddingAngle={2}
            >
              {data.map((entry) => (
                <Cell key={entry.name} fill={entry.color} />
              ))}
            </Pie>
            <Tooltip
              contentStyle={{ borderRadius: 6, fontSize: 12 }}
              formatter={(v: number, _name, p) => {
                const pct = total > 0 ? Math.round((v / total) * 100) : 0;
                return [`${formatInt(v)} (${pct}%)`, p.payload.name];
              }}
            />
            <Legend
              verticalAlign="middle"
              align="right"
              layout="vertical"
              wrapperStyle={{ fontSize: 12 }}
            />
          </PieChart>
        </ResponsiveContainer>
      </div>
      <div className="text-sm">
        <div className="text-ink-muted text-xs uppercase mb-1">Total cohorte</div>
        <div className="text-2xl font-semibold tabular-nums">
          {formatInt(total)}
        </div>
      </div>
    </div>
  );
}
