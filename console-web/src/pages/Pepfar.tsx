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
import { BarChart3, Download, Loader2 } from "lucide-react";
import { Kpi, formatInt, formatPercent } from "../components/Kpi";
import { PageHeader } from "../components/PageHeader";
import { GeoFilter, GeoScope } from "../components/GeoFilter";
import { KpiRowSkeleton, ListSkeleton } from "../components/Skeleton";

// Tranches d'âge PEPFAR MER (désagrégation fine). L'ordre de ce tableau
// porte l'ordre d'affichage des colonnes. Doit rester aligné sur le CASE
// de PepfarService.ageBandExpr() côté backend. 'unknown' en dernier.
const AGE_BANDS = [
  "<1",
  "1-4",
  "5-9",
  "10-14",
  "15-19",
  "20-24",
  "25-29",
  "30-34",
  "35-39",
  "40-44",
  "45-49",
  "50+",
  "unknown",
] as const;
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
        title="Indicateurs MOU"
        subtitle={<>
          TX_NEW · TX_CURR · TX_PVLS · Trimestre fiscal
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
            {exporting ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Download className="h-3.5 w-3.5" />}
            {exporting ? "Export…" : "Exporter CSV"}
          </button>
        </>} />

      {/* Cascade TX — KPI */}
      {report.isLoading ? <KpiRowSkeleton /> : (
        <div className="grid gap-3 grid-cols-2 lg:grid-cols-4 mb-6">
          <Kpi
            label="Nouvelles initiations ARV"
            value={report.isError ? "Erreur" : formatInt(report.data?.txNew.total)}
            hint="TX_NEW · initiations du trimestre"
            hintTone="neutral"
          />
          <Kpi
            label="Patients sous traitement"
            value={report.isError ? "Erreur" : formatInt(report.data?.txCurr.total)}
            hint="TX_CURR · fin du trimestre"
            hintTone="neutral"
          />
          <Kpi
            label="Charge virale documentée"
            value={report.isError
                ? "Erreur"
                : formatInt(report.data?.txPvls.denominator.total)}
            hint="TX_PVLS (D) · éligibles CV, 12 mois"
            hintTone="neutral"
          />
          <Kpi
            label="Taux de suppression virale"
            value={report.isError
                ? "Erreur"
                : formatPercent(report.data?.txPvls.pct ?? null)}
            hint="TX_PVLS (%) · CV < 1000 copies/mL"
            hintTone="positive"
          />
        </div>
      )}

      {/* HTS + PMTCT + TB_PREV — KPI */}
      {!report.isLoading && report.data && (
        <div className="grid gap-3 grid-cols-2 lg:grid-cols-4 mb-6">
          <Kpi
            label="Clients dépistés"
            value={formatInt(report.data.hts.tst.total)}
            hint="HTS_TST · ayant reçu le résultat"
            hintTone="neutral"
          />
          <Kpi
            label="Dépistés positifs"
            value={formatInt(report.data.hts.pos.total)}
            hint={`HTS_POS · positivité : ${formatPercent(report.data.hts.positivityPct ?? null)}`}
            hintTone="warning"
          />
          <Kpi
            label="Femmes enceintes VIH+ sous ARV"
            value={formatPercent(report.data.pmtct.art.pct ?? null)}
            hint={`PMTCT_ART · ${formatInt(report.data.pmtct.art.numerator.total)} sous ARV / ${formatInt(report.data.pmtct.art.denominator.total)} VIH+`}
            hintTone="positive"
          />
          <Kpi
            label="TPT terminé"
            value={formatPercent(report.data.tbPrev.pct ?? null)}
            hint={`TB_PREV · ${formatInt(report.data.tbPrev.numerator.total)} terminés / ${formatInt(report.data.tbPrev.denominator.total)}`}
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
            title="Nouvelles initiations ARV (TX_NEW)"
            data={report.data.txNew}
          />
          <DisaggTable
            title="Patients sous traitement à la fin du trimestre (TX_CURR)"
            data={report.data.txCurr}
          />
          <PvlsTable pvls={report.data.txPvls} />
          <HtsTable hts={report.data.hts} />
          <PairTable
            title="Statut VIH connu chez la femme enceinte (PMTCT_STAT)"
            ratioLabel="connu"
            pair={report.data.pmtct.stat}
          />
          <PairTable
            title="Femmes enceintes VIH+ sous ARV (PMTCT_ART)"
            ratioLabel="sous ARV"
            pair={report.data.pmtct.art}
          />
          <PairTable
            title="Enfants exposés avec PCR1 ≤ 2 mois (PMTCT_EID)"
            ratioLabel="PCR1 précoce"
            pair={report.data.pmtct.eid}
          />
          <PairTable
            title="TPT terminé pendant le trimestre (TB_PREV)"
            ratioLabel="terminés"
            pair={report.data.tbPrev}
          />
        </div>
      )}
    </div>
  );
}

// Lignes affichées = sexes présents (+ 'unknown' seulement s'il porte des
// données), dans l'ordre M, F, unknown.
const SEX_ROWS: ReadonlyArray<{ key: "M" | "F" | "unknown"; label: string }> = [
  { key: "M", label: "Hommes" },
  { key: "F", label: "Femmes" },
  { key: "unknown", label: "Non renseigné" },
];

/** Bandes à afficher en colonnes : on masque 'unknown' s'il est vide. */
function visibleBands(bandTotals: Record<string, number>): string[] {
  return AGE_BANDS.filter((b) => b !== "unknown" || (bandTotals[b] ?? 0) > 0);
}

/** Lignes de sexe à afficher : on masque 'unknown' s'il est vide. */
function visibleSexRows(sexTotals: Record<string, number>) {
  return SEX_ROWS.filter((s) => s.key !== "unknown" || (sexTotals[s.key] ?? 0) > 0);
}

function DisaggTable({ title, data }: { title: string; data: Disaggregated }) {
  const { byBand, sexTotals, bandTotals } = buildMatrix(data);
  const bands = visibleBands(bandTotals);
  const rows = visibleSexRows(sexTotals);
  return (
    <div className="card overflow-hidden">
      <div className="px-4 py-3 border-b border-slate-200">
        <h3 className="text-sm font-medium">{title}</h3>
      </div>
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead className="thead-sigdep text-left">
            <tr>
              <th className="px-4 py-2 text-left font-medium">Sexe</th>
              {bands.map((band) => (
                <th key={band} className="px-3 py-2 text-right font-medium tabular-nums">
                  {band}
                </th>
              ))}
              <th className="px-4 py-2 text-right font-medium">Total</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {rows.map((s) => (
              <tr key={s.key} className="hover:bg-slate-50">
                <td className="px-4 py-2 whitespace-nowrap">{s.label}</td>
                {bands.map((band) => (
                  <td key={band} className="px-3 py-2 text-right tabular-nums">
                    {formatInt(byBand[band][s.key] ?? 0)}
                  </td>
                ))}
                <td className="px-4 py-2 text-right tabular-nums font-medium">
                  {formatInt(sexTotals[s.key] ?? 0)}
                </td>
              </tr>
            ))}
            <tr className="bg-slate-50 font-medium">
              <td className="px-4 py-2">Total</td>
              {bands.map((band) => (
                <td key={band} className="px-3 py-2 text-right tabular-nums">
                  {formatInt(bandTotals[band] ?? 0)}
                </td>
              ))}
              <td className="px-4 py-2 text-right tabular-nums">
                {formatInt(data.total)}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  );
}

/** Cellule ratio « n / d (%) » réutilisée par les tableaux num/dénom. */
function ratioCell(n: number, d: number, pctClass: string) {
  return (
    <>
      {formatInt(n)} / {formatInt(d)}
      {d > 0 && (
        <span className={`${pctClass} text-xs ml-1`}>
          ({Math.round((n / d) * 1000) / 10}%)
        </span>
      )}
    </>
  );
}

/**
 * Tableau numérateur/dénominateur pivoté : lignes = sexe, colonnes = tranche
 * d'âge. Sert TX_PVLS, HTS_POS, PMTCT_* et TB_PREV. `totalPct` (optionnel)
 * force le % de la cellule grand-total (déjà calculé côté backend).
 */
function RatioTable({
  title,
  denom,
  numer,
  denomTotal,
  numerTotal,
  totalPct,
}: {
  title: string;
  denom: ReturnType<typeof buildMatrix>;
  numer: ReturnType<typeof buildMatrix>;
  denomTotal: number;
  numerTotal: number;
  totalPct: number | null;
}) {
  const bands = visibleBands(denom.bandTotals);
  const rows = visibleSexRows(denom.sexTotals);
  return (
    <div className="card overflow-hidden">
      <div className="px-4 py-3 border-b border-slate-200">
        <h3 className="text-sm font-medium">{title}</h3>
      </div>
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead className="thead-sigdep text-left">
            <tr>
              <th className="px-4 py-2 text-left font-medium">Sexe</th>
              {bands.map((band) => (
                <th key={band} className="px-3 py-2 text-right font-medium tabular-nums">
                  {band}
                </th>
              ))}
              <th className="px-4 py-2 text-right font-medium">Total</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {rows.map((s) => (
              <tr key={s.key} className="hover:bg-slate-50">
                <td className="px-4 py-2 whitespace-nowrap">{s.label}</td>
                {bands.map((band) => (
                  <td key={band} className="px-3 py-2 text-right tabular-nums whitespace-nowrap">
                    {ratioCell(
                      numer.byBand[band][s.key] ?? 0,
                      denom.byBand[band][s.key] ?? 0,
                      "text-ink-muted",
                    )}
                  </td>
                ))}
                <td className="px-4 py-2 text-right tabular-nums whitespace-nowrap font-medium">
                  {ratioCell(
                    numer.sexTotals[s.key] ?? 0,
                    denom.sexTotals[s.key] ?? 0,
                    "text-emerald-700",
                  )}
                </td>
              </tr>
            ))}
            <tr className="bg-slate-50 font-medium">
              <td className="px-4 py-2">Total</td>
              {bands.map((band) => (
                <td key={band} className="px-3 py-2 text-right tabular-nums whitespace-nowrap">
                  {ratioCell(
                    numer.bandTotals[band] ?? 0,
                    denom.bandTotals[band] ?? 0,
                    "text-emerald-700",
                  )}
                </td>
              ))}
              <td className="px-4 py-2 text-right tabular-nums whitespace-nowrap">
                {formatInt(numerTotal)} / {formatInt(denomTotal)}
                {totalPct !== null && (
                  <span className="text-emerald-700 text-xs ml-1">({totalPct}%)</span>
                )}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  );
}

function PvlsTable({ pvls }: { pvls: TxPvls }) {
  return (
    <RatioTable
      title="Suppression virale · numérateur / dénominateur (%) (TX_PVLS)"
      denom={buildMatrix(pvls.denominator)}
      numer={buildMatrix(pvls.numerator)}
      denomTotal={pvls.denominator.total}
      numerTotal={pvls.numerator.total}
      totalPct={pvls.pct}
    />
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
  return (
    <RatioTable
      title={`${title} · ${ratioLabel} / total (%)`}
      denom={buildMatrix(pair.denominator)}
      numer={buildMatrix(pair.numerator)}
      denomTotal={pair.denominator.total}
      numerTotal={pair.numerator.total}
      totalPct={pair.pct}
    />
  );
}

/**
 * HTS — HTS_TST (dépistés) au dénominateur, HTS_POS (positifs) au
 * numérateur, taux de positivité par cellule. Réutilise RatioTable.
 */
function HtsTable({ hts }: { hts: Hts }) {
  return (
    <RatioTable
      title="Dépistage VIH · positifs / dépistés (%) (HTS_TST / HTS_POS)"
      denom={buildMatrix(hts.tst)}
      numer={buildMatrix(hts.pos)}
      denomTotal={hts.tst.total}
      numerTotal={hts.pos.total}
      totalPct={hts.positivityPct}
    />
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
