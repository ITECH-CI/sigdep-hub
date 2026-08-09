import { useQuery } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../auth";
import {
  Bar,
  BarChart,
  LabelList,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { fetchPublicKpis } from "../api/client";
import { Kpi, formatInt, formatPercent } from "../components/Kpi";
import { PartnerLogos } from "../components/PartnerLogos";
import { buildLabel } from "../components/Footer";

const NATIONAL_TARGET_SITES = 549; // see runs_sigdep flag — not yet backfilled

export function Landing() {
  const auth = useAuth();
  const navigate = useNavigate();
  const { data, isLoading, isError } = useQuery({
    queryKey: ["publicKpis"],
    queryFn: fetchPublicKpis,
  });

  const handleLogin = () => {
    navigate(auth.isAuthenticated ? "/app" : "/login");
  };

  return (
    <div className="min-h-screen flex flex-col">
      {/* Header */}
      <header className="border-b border-slate-200 bg-white">
        <div className="mx-auto max-w-6xl flex items-center justify-between px-6 py-3">
          <div className="flex items-center gap-3">
            <img src="/logos/sigdep3_crop.png" alt="" className="h-10 w-10" />
            <img
              src="/logos/sigdep_logo_text_small.png"
              alt="SIGDEP-3"
              className="h-9 w-auto"
            />
            <span className="text-sm text-ink-muted hidden sm:inline">
              PNLS · Côte d’Ivoire
            </span>
          </div>
          <button
            onClick={handleLogin}
            className="inline-flex items-center gap-1 rounded-md bg-sigdep-500 px-4 py-2 text-sm font-medium text-white hover:bg-sigdep-600 transition"
          >
            {auth.isAuthenticated ? "Tableau de bord" : "Se connecter"}
            <svg
              className="h-3 w-3"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
            >
              <path d="M7 17L17 7M7 7h10v10" />
            </svg>
          </button>
        </div>
      </header>

      {/* Hero */}
      <section className="bg-gradient-to-b from-sigdep-50 to-white">
        <div className="mx-auto max-w-6xl px-6 py-12">
          <h1 className="text-3xl sm:text-4xl font-semibold tracking-tight">
            Base de Données Centrale Consolidée des patients vivant avec le
            VIH en Côte d’Ivoire
          </h1>
          <p className="mt-2 text-ink-muted">Indicateurs clés</p>
          <p className="mt-4 text-xs text-ink-subtle">
            Données mises à jour quotidiennement · Source : SIGDEP-3 ·{" "}
            <span className="font-medium">
              Données partielles — {data ? formatInt(data.sitesWithData) : "…"}{" "}
              sites synchronisés sur {NATIONAL_TARGET_SITES} ciblés
            </span>
          </p>
        </div>
      </section>

      {/* KPIs */}
      <section className="mx-auto max-w-6xl w-full px-6 -mt-6">
        <div className="grid gap-3 grid-cols-2 lg:grid-cols-4">
          <Kpi
            label="Patients en suivi actif"
            value={isError ? "Erreur" : formatInt(data?.patientsActive)}
            hint={isLoading ? "Chargement…" : "Cohorte cumulée"}
            hintTone="neutral"
          />
          <Kpi
            label="Sites de prise en charge"
            value={isError ? "Erreur" : formatInt(data?.sitesWithData)}
            hint={`sur ${NATIONAL_TARGET_SITES} sites SIGDEP cibles`}
            hintTone="neutral"
          />
          <Kpi
            label="Suppression virale"
            value={
              isError ? "Erreur" : formatPercent(data?.viralSuppression ?? null)
            }
            hint="CV < 1000 copies/mL · 12 mois"
            hintTone="positive"
          />
          <Kpi
            label="Couverture ARV adultes"
            value={
              data?.arvCoverage == null
                ? "À venir"
                : formatPercent(data.arvCoverage)
            }
            hint="Dénominateur national en attente"
            hintTone="neutral"
          />
        </div>
      </section>

      {/* Active file chart */}
      <section className="mx-auto max-w-6xl w-full px-6 mt-8">
        <div className="card p-4">
          <h3 className="text-sm font-medium mb-4">
            Évolution de la file active · 12 derniers mois
          </h3>
          <div className="h-48">
            {isLoading ? (
              <div className="h-full flex items-center justify-center text-ink-muted text-sm">
                Chargement…
              </div>
            ) : (
              <ResponsiveContainer width="100%" height="100%">
                <BarChart
                  data={data?.activeFile ?? []}
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
                    formatter={(v: number) => [formatInt(v), "Patients"]}
                  />
                  <Bar dataKey="count" fill="#009d8e" radius={[3, 3, 0, 0]}>
                    <LabelList dataKey="count" position="top"
                               style={{ fill: '#475569', fontSize: 11, fontWeight: 500 }} />
                  </Bar>
                </BarChart>
              </ResponsiveContainer>
            )}
          </div>
        </div>
      </section>

      {/* Footer */}
      <footer className="mt-12 border-t border-slate-200 bg-white">
        <div className="mx-auto max-w-6xl px-6 py-6 flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
          <PartnerLogos size="sm" />
          <div className="text-xs text-ink-subtle sm:text-right">
            <p>
              Ministère de la Santé de l'Hygiène Publique et de la Couverture
              Maladie Universelle · PNLS · 2026
            </p>
            <p className="mt-1 text-slate-400">{buildLabel()}</p>
          </div>
        </div>
      </footer>
    </div>
  );
}
