import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { DateBlock, fetchPatient, fetchPatientEncounters } from '../api/client';
import { PageHeader } from '../components/PageHeader';

const KIND_META: Record<string, { label: string; color: string }> = {
  visit:      { label: 'Visite',         color: 'bg-sky-100 text-sky-700' },
  initiation: { label: 'Initiation ARV', color: 'bg-emerald-100 text-emerald-700' },
  closure:    { label: 'Clôture',        color: 'bg-rose-100 text-rose-700' },
  lab:        { label: 'Biologie',       color: 'bg-violet-100 text-violet-700' },
};

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString('fr-FR',
    { day: '2-digit', month: 'short', year: 'numeric' });
}

export function PatientDetail() {
  const { id } = useParams<{ id: string }>();
  const patientId = Number(id);

  const patient = useQuery({
    queryKey: ['patient', patientId],
    queryFn: () => fetchPatient(patientId),
    enabled: !Number.isNaN(patientId),
  });

  const encounters = useQuery({
    queryKey: ['patientEncounters', patientId],
    queryFn: () => fetchPatientEncounters(patientId),
    enabled: !Number.isNaN(patientId),
  });

  if (patient.isLoading) {
    return <div className="px-6 py-6 text-ink-muted text-sm">Chargement…</div>;
  }
  if (patient.isError || !patient.data) {
    return <div className="px-6 py-6 text-rose-600 text-sm">Patient introuvable</div>;
  }

  const p = patient.data;
  let sex: string | null = null;
  if (p.sex === 'M') sex = 'Homme';
  else if (p.sex === 'F') sex = 'Femme';

  return (
    <div className="px-6 py-6">
      <div className="mb-4">
        <Link to="/app/patients" className="text-sm text-sigdep-700 hover:underline">
          ← Retour à la liste
        </Link>
      </div>

      <PageHeader
        title={p.identifiers[0] ?? p.sourceUuid.slice(0, 8)}
        subtitle={`${p.siteName} (${p.siteCode})`} />

      {/* Identity card */}
      <div className="card p-4 mb-6">
        <h3 className="text-sm font-medium mb-3">Identité</h3>
        <dl className="grid gap-x-6 gap-y-2 grid-cols-2 lg:grid-cols-4 text-sm">
          <Field label="Sexe" value={sex} />
          <Field label="Date de naissance" value={p.birthDate ? formatDate(p.birthDate) : null} />
          <Field label="Profession" value={p.profession} />
          <Field label="Niveau d’éducation" value={p.educationLevel} />
          <Field label="Statut matrimonial" value={p.maritalStatus} />
          <Field label="Lieu de naissance" value={p.birthPlace} />
          <Field label="Religion" value={p.religion} />
          <Field label="Identifiants" value={p.identifiers.join(', ') || null} />
        </dl>
      </div>

      {/* Encounters timeline grouped by date */}
      <div>
        <h3 className="text-sm font-medium mb-3">Chronologie</h3>
        {encounters.isLoading ? (
          <p className="text-sm text-ink-muted">Chargement…</p>
        ) : !encounters.data || encounters.data.length === 0 ? (
          <p className="text-sm text-ink-muted">Aucun événement</p>
        ) : (
          <ol className="space-y-3">
            {encounters.data.map(day => (
              <DayCard key={day.date} date={day.date} blocks={day.blocks} />
            ))}
          </ol>
        )}
      </div>
    </div>
  );
}

function DayCard({ date, blocks }: { date: string; blocks: DateBlock[] }) {
  const totalObs = blocks.reduce((n, b) => n + b.observations.length, 0);
  const [open, setOpen] = useState(true);
  const kinds = blocks.map(b => b.kind);

  return (
    <li className="card overflow-hidden">
      <button
        onClick={() => setOpen(o => !o)}
        className="w-full px-4 py-3 flex items-center gap-3 hover:bg-slate-50 text-left">
        <span className="tabular-nums font-medium text-sm w-32 shrink-0">{formatDate(date)}</span>
        <div className="flex gap-1 flex-wrap">
          {kinds.map((k, i) => {
            const meta = KIND_META[k] ?? { label: k, color: 'bg-slate-100 text-slate-700' };
            return (
              <span key={i} className={`text-[11px] px-1.5 py-0.5 rounded ${meta.color}`}>
                {meta.label}
              </span>
            );
          })}
        </div>
        <span className="ml-auto text-xs text-ink-muted">
          {totalObs} obs.
        </span>
        <svg className={`h-4 w-4 text-ink-subtle transition ${open ? 'rotate-180' : ''}`}
             viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
          <path d="M6 9l6 6 6-6" />
        </svg>
      </button>

      {open && (
        <div className="border-t border-slate-200 divide-y divide-slate-100">
          {blocks.map((b, i) => (
            <BlockRow key={i} block={b} />
          ))}
        </div>
      )}
    </li>
  );
}

function BlockRow({ block }: { block: DateBlock }) {
  const meta = KIND_META[block.kind] ?? { label: block.kind, color: 'bg-slate-100 text-slate-700' };
  return (
    <div className="px-4 py-3">
      <div className="flex items-baseline gap-2 mb-2">
        <span className={`text-[11px] px-1.5 py-0.5 rounded ${meta.color}`}>{meta.label}</span>
        <span className="text-sm font-medium">{block.label}</span>
      </div>
      {block.observations.length === 0 ? (
        <p className="text-xs text-ink-muted">—</p>
      ) : (
        <dl className="grid gap-x-6 gap-y-1 grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 text-sm">
          {block.observations.map((o, i) => (
            <div key={i} className="flex justify-between gap-2 border-b border-slate-50 py-0.5">
              <dt className="text-ink-muted">{o.label}</dt>
              <dd className="font-medium text-right tabular-nums">{o.value}</dd>
            </div>
          ))}
        </dl>
      )}
    </div>
  );
}

function Field({ label, value }: { label: string; value: string | null | undefined }) {
  return (
    <div>
      <dt className="text-xs text-ink-muted">{label}</dt>
      <dd className="text-ink">{value || '—'}</dd>
    </div>
  );
}
