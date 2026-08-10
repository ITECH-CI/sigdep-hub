import { useEffect, useState } from 'react';

export type PeriodRange = {
  /** Borne de début incluse, ISO yyyy-MM-dd. */
  from: string;
  /** Borne de fin incluse, ISO yyyy-MM-dd. */
  to: string;
};

export type PeriodPreset = {
  /** Nombre de mois glissants (borne de fin = aujourd'hui). */
  months: number;
  label: string;
};

/** Date du jour en ISO yyyy-MM-dd (heure locale, sans décalage UTC). */
function todayIso(): string {
  const d = new Date();
  const off = d.getTimezoneOffset() * 60_000;
  return new Date(d.getTime() - off).toISOString().slice(0, 10);
}

/** Intervalle correspondant à N mois glissants finissant aujourd'hui. */
export function monthsToRange(months: number): PeriodRange {
  const to = new Date();
  const from = new Date(to);
  from.setMonth(from.getMonth() - months);
  const iso = (d: Date) =>
    new Date(d.getTime() - d.getTimezoneOffset() * 60_000)
      .toISOString()
      .slice(0, 10);
  return { from: iso(from), to: iso(to) };
}

/** Range par défaut : 12 mois glissants (aligné sur le défaut backend). */
export function defaultPeriod(): PeriodRange {
  return monthsToRange(12);
}

/**
 * Filtre de période : des presets « N derniers mois » qui préremplissent
 * deux champs date, ET deux sélecteurs date début / date fin TOUJOURS visibles
 * et éditables. Choisir un preset pose { from: aujourd'hui − N mois, to:
 * aujourd'hui } ; modifier une date bascule en mode personnalisé (aucun preset
 * n'apparaît alors actif).
 *
 * Style « contrôlé » comme GeoFilter : la page porte l'état via value/onChange.
 */
export function PeriodFilter({
  value,
  onChange,
  presets,
}: Readonly<{
  value: PeriodRange;
  onChange: (next: PeriodRange) => void;
  presets: PeriodPreset[];
}>) {
  // Les champs date modifient un brouillon LOCAL ; la requête (onChange) ne part
  // qu'au clic « Appliquer » (ou touche Entrée), pour ne pas relancer une
  // requête à chaque caractère saisi. Les presets, eux, s'appliquent tout de
  // suite (ce sont des raccourcis, pas une saisie à valider).
  const [draft, setDraft] = useState<PeriodRange>(value);

  // Resynchronise le brouillon quand la période effective change ailleurs
  // (choix d'un preset, reset…), pour que les champs reflètent l'état courant.
  useEffect(() => {
    setDraft(value);
  }, [value.from, value.to]);

  const today = todayIso();
  const activeMonths = presets.find((p) => {
    const r = monthsToRange(p.months);
    return r.from === value.from && r.to === value.to && value.to === today;
  })?.months;

  // Y a-t-il une modification de date non encore appliquée ?
  const dirty = draft.from !== value.from || draft.to !== value.to;
  const validRange = !!draft.from && !!draft.to && draft.from <= draft.to;

  const apply = () => {
    if (dirty && validRange) onChange(draft);
  };

  return (
    <>
      <select
        value={activeMonths ?? ""}
        onChange={(e) => {
          const m = Number(e.target.value);
          if (m) onChange(monthsToRange(m)); // preset : applique immédiatement
        }}
        className="rounded-md border border-slate-300 px-3 py-2 text-sm bg-white"
        aria-label="Période prédéfinie"
      >
        {activeMonths == null && (
          <option value="" disabled>
            Personnalisé
          </option>
        )}
        {presets.map((p) => (
          <option key={p.months} value={p.months}>
            {p.label}
          </option>
        ))}
      </select>

      <input
        type="date"
        value={draft.from}
        max={draft.to || undefined}
        onChange={(e) => setDraft((d) => ({ ...d, from: e.target.value }))}
        onKeyDown={(e) => e.key === "Enter" && apply()}
        className="rounded-md border border-slate-300 px-3 py-2 text-sm bg-white"
        aria-label="Date de début"
      />
      <span className="text-slate-400 text-sm self-center">→</span>
      <input
        type="date"
        value={draft.to}
        min={draft.from || undefined}
        onChange={(e) => setDraft((d) => ({ ...d, to: e.target.value }))}
        onKeyDown={(e) => e.key === "Enter" && apply()}
        className="rounded-md border border-slate-300 px-3 py-2 text-sm bg-white"
        aria-label="Date de fin"
      />
      <button
        type="button"
        onClick={apply}
        disabled={!dirty || !validRange}
        className="rounded-md border border-slate-300 px-3 py-2 text-sm bg-white
                   hover:bg-slate-50 disabled:opacity-40 disabled:cursor-default transition"
        aria-label="Appliquer la période"
      >
        Appliquer
      </button>
    </>
  );
}
