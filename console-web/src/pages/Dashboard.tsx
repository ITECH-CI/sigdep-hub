import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  Bar, BarChart, LabelList, ResponsiveContainer, XAxis, YAxis, Tooltip,
} from 'recharts';
import {
  Activity, AlertTriangle, Building2, Clock, Hospital, LayoutDashboard, Map,
  TrendingUp, UserPlus, Users, type LucideIcon,
} from 'lucide-react';
import { fetchDashboardKpis, fetchFileActiveByRegion } from '../api/client';
import { GeoFilter, GeoScope } from '../components/GeoFilter';
import { Kpi, formatInt, formatPercent } from '../components/Kpi';
import { PageHeader } from '../components/PageHeader';
import {
  ChartSkeleton, KpiRowSkeleton, ListSkeleton,
} from '../components/Skeleton';
import { StatusBadge, type BadgeTone } from '../components/StatusBadge';

function formatTime(iso: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return d.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' });
}

export function Dashboard() {
  const [scope, setScope] = useState<GeoScope>({});

  const { data, isLoading, isError } = useQuery({
    queryKey: ['dashboardKpis', scope],
    queryFn: () => fetchDashboardKpis(scope),
  });

  const regions = useQuery({
    queryKey: ['fileActiveByRegion', scope],
    queryFn: () => fetchFileActiveByRegion(scope),
  });

  const periodLabel = new Date().toLocaleDateString('fr-FR',
    { month: 'long', year: 'numeric' });

  // Subtitle reflects the active scope so a SITE_USER sees "Périmètre : Site"
  // instead of the stale "National" label.
  const scopeLabel =
    scope.siteId     ? 'Site'
    : scope.districtId ? 'District'
    : scope.regionId   ? 'Région'
    : 'National';

  return (
    <div className="px-6 py-6">
      <PageHeader
        icon={LayoutDashboard}
        title="Vue d’ensemble"
        subtitle={`Périmètre : ${scopeLabel} · ${periodLabel.charAt(0).toUpperCase() + periodLabel.slice(1)}`}
        right={<GeoFilter value={scope} onChange={setScope} />} />

      {/* KPI row */}
      {isLoading ? <KpiRowSkeleton /> : (
        <div className="grid gap-3 grid-cols-2 lg:grid-cols-4 mb-6">
          <Kpi label="File active"
               icon={Users}
               value={isError ? 'Erreur' : formatInt(data?.fileActive)}
               hint="12 mois glissants"
               hintTone="neutral" />
          <Kpi label="TX_NEW (mois)"
               icon={UserPlus}
               value={isError ? 'Erreur' : formatInt(data?.txNewMonth)}
               hint="Nouvelles initiations ARV"
               hintTone="neutral" />
          <Kpi label="CV supprimée"
               icon={TrendingUp}
               value={isError ? 'Erreur' : formatPercent(data?.viralSuppression ?? null)}
               hint="< 1000 copies/mL · 12 mois"
               hintTone="positive" />
          <Kpi label="Sites en ligne"
               icon={Building2}
               value={isError ? 'Erreur'
                  : `${formatInt(data?.sitesOnline)} / ${formatInt(data?.sitesTotalScope)}`}
               hint="Synchronisés < 24h"
               hintTone="neutral" />
        </div>
      )}

      {/* Two-column row */}
      <div className="grid gap-3 lg:grid-cols-2">
        <section className="card p-5">
          <header className="flex items-center justify-between mb-4">
            <h3 className="text-sm font-semibold text-ink flex items-center gap-2">
              <span className="h-7 w-7 rounded-md bg-sigdep-50 text-sigdep-700
                               flex items-center justify-center">
                <Users className="h-4 w-4" />
              </span>
              File active &middot; 12 mois glissants
            </h3>
          </header>
          <div className="h-56">
            {isLoading ? (
              <ChartSkeleton height="h-56" />
            ) : (
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={data?.activeFile ?? []}
                          margin={{ top: 24, right: 8, left: 0, bottom: 0 }}>
                  <XAxis dataKey="month" tick={{ fontSize: 11 }} stroke="#94a3b8" />
                  <YAxis tick={{ fontSize: 11 }} stroke="#94a3b8" />
                  <Tooltip
                    contentStyle={{ borderRadius: 6, fontSize: 12, border: '1px solid #e2e8f0' }}
                    formatter={(v: number) => [formatInt(v), 'Patients']}
                  />
                  <Bar dataKey="count" fill="#009d8e" radius={[4, 4, 0, 0]}>
                    <LabelList dataKey="count" position="top"
                               style={{ fill: '#475569', fontSize: 11, fontWeight: 500 }} />
                  </Bar>
                </BarChart>
              </ResponsiveContainer>
            )}
          </div>
        </section>

        <section className="card p-5">
          <header className="flex items-center justify-between mb-3">
            <h3 className="text-sm font-semibold text-ink flex items-center gap-2">
              <span className="h-7 w-7 rounded-md bg-amber-50 text-amber-700
                               flex items-center justify-center">
                <AlertTriangle className="h-4 w-4" />
              </span>
              Alertes synchronisation
            </h3>
          </header>
          {isLoading ? (
            <ListSkeleton rows={4} />
          ) : data ? (
            <ul className="divide-y divide-slate-100">
              <AlertRow
                icon={AlertTriangle}
                iconTone="danger"
                label={`${formatInt(data.syncAlerts.sitesNoSync7d)} sites > 7j sans sync`}
                badge="Critique"
                tone="danger" />
              <AlertRow
                icon={Clock}
                iconTone="warning"
                label={`${formatInt(data.syncAlerts.sitesNoSync24h)} sites > 24h sans sync`}
                badge="Attention"
                tone="warning" />
              <AlertRow
                icon={Hospital}
                iconTone="info"
                label={`${formatInt(data.fileActive)} lignes en file`}
                badge="Globale"
                tone="info" />
              <AlertRow
                icon={Activity}
                iconTone="ok"
                label="Dernier batch reçu"
                badge={formatTime(data.syncAlerts.lastBatchAt)}
                tone="ok" />
            </ul>
          ) : null}
        </section>
      </div>

      {/* Répartition géographique de la file active. Bar chart horizontal
          plutôt qu'une carte SVG pour la v1 : aussi parlant pour le décideur
          et zéro asset à maintenir. La vraie carte CI viendra plus tard. */}
      {regions.data && regions.data.length > 1 && (
        <section className="card p-5 mt-3">
          <header className="flex items-center justify-between mb-4">
            <h3 className="text-sm font-semibold text-ink flex items-center gap-2">
              <span className="h-7 w-7 rounded-md bg-sigdep-50 text-sigdep-700
                               flex items-center justify-center">
                <Map className="h-4 w-4" />
              </span>
              File active par région &middot; 12 mois glissants
            </h3>
            <span className="text-xs text-ink-muted">
              {regions.data.length} régions
            </span>
          </header>
          <div style={{ height: Math.max(220, regions.data.length * 28 + 40) }}>
            <ResponsiveContainer width="100%" height="100%">
              <BarChart
                data={regions.data}
                layout="vertical"
                margin={{ top: 8, right: 64, left: 8, bottom: 8 }}
              >
                <XAxis type="number" tick={{ fontSize: 11 }} stroke="#94a3b8" />
                <YAxis type="category" dataKey="regionName"
                       width={140}
                       tick={{ fontSize: 11 }} stroke="#94a3b8" />
                <Tooltip
                  contentStyle={{ borderRadius: 6, fontSize: 12, border: '1px solid #e2e8f0' }}
                  formatter={(v: number) => [formatInt(v), 'Patients']}
                />
                <Bar dataKey="count" fill="#009d8e" radius={[0, 3, 3, 0]}>
                  <LabelList dataKey="count" position="right"
                             style={{ fill: '#475569', fontSize: 11, fontWeight: 500 }} />
                </Bar>
              </BarChart>
            </ResponsiveContainer>
          </div>
        </section>
      )}
    </div>
  );
}

const ICON_TINT: Record<BadgeTone, string> = {
  ok:      'bg-emerald-50 text-emerald-600',
  warning: 'bg-amber-50   text-amber-600',
  danger:  'bg-rose-50    text-rose-600',
  info:    'bg-sigdep-50  text-sigdep-600',
  neutral: 'bg-slate-100  text-slate-500',
};

function AlertRow({ icon: Icon, iconTone, label, badge, tone }: Readonly<{
  icon: LucideIcon;
  iconTone: BadgeTone;
  label: string;
  badge: string;
  tone: BadgeTone;
}>) {
  return (
    <li className="flex items-center justify-between gap-3 py-2.5 text-sm">
      <div className="flex items-center gap-2.5 min-w-0">
        <span className={`h-6 w-6 rounded-md flex items-center justify-center shrink-0 ${ICON_TINT[iconTone]}`}>
          <Icon className="h-3.5 w-3.5" />
        </span>
        <span className="text-ink truncate">{label}</span>
      </div>
      <StatusBadge tone={tone}>{badge}</StatusBadge>
    </li>
  );
}
