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
  // Un preset est « actif » si value == exactement son range (mois glissants
  // finissant aujourd'hui). Sinon on est en mode personnalisé.
  const today = todayIso();
  const activeMonths = presets.find((p) => {
    const r = monthsToRange(p.months);
    return r.from === value.from && r.to === value.to && value.to === today;
  })?.months;

  return (
    <>
      <select
        value={activeMonths ?? ""}
        onChange={(e) => {
          const m = Number(e.target.value);
          if (m) onChange(monthsToRange(m));
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
        value={value.from}
        max={value.to || undefined}
        onChange={(e) => onChange({ from: e.target.value, to: value.to })}
        className="rounded-md border border-slate-300 px-3 py-2 text-sm bg-white"
        aria-label="Date de début"
      />
      <span className="text-slate-400 text-sm self-center">→</span>
      <input
        type="date"
        value={value.to}
        min={value.from || undefined}
        onChange={(e) => onChange({ from: value.from, to: e.target.value })}
        className="rounded-md border border-slate-300 px-3 py-2 text-sm bg-white"
        aria-label="Date de fin"
      />
    </>
  );
}
