import { useEffect, useMemo, useRef, useState } from 'react';
import { Check, ChevronDown, X } from 'lucide-react';

export type ComboOption = { value: number; label: string };

/**
 * Liste déroulante filtrable (combobox), sans dépendance externe. Conçue pour
 * les longues listes région / district / site : un champ de recherche filtre
 * les options par texte. Navigation clavier (↑/↓/Entrée/Échap), fermeture au
 * clic extérieur, option « tout effacer ».
 *
 * Contrôlée : `value` (id sélectionné ou null) + `onChange`.
 */
export function Combobox({
  options, value, onChange, placeholder = '— Choisir —',
  disabled = false, className,
}: Readonly<{
  options: ComboOption[];
  value: number | null;
  onChange: (value: number | null) => void;
  placeholder?: string;
  disabled?: boolean;
  className?: string;
}>) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [active, setActive] = useState(0);
  const rootRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  const selected = options.find((o) => o.value === value) ?? null;

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return options;
    return options.filter((o) => o.label.toLowerCase().includes(q));
  }, [options, query]);

  // Fermer au clic extérieur.
  useEffect(() => {
    if (!open) return;
    const onDocClick = (e: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) {
        setOpen(false);
        setQuery('');
      }
    };
    document.addEventListener('mousedown', onDocClick);
    return () => document.removeEventListener('mousedown', onDocClick);
  }, [open]);

  // Focus le champ de recherche à l'ouverture.
  useEffect(() => {
    if (open) { setActive(0); inputRef.current?.focus(); }
  }, [open]);

  const choose = (opt: ComboOption) => {
    onChange(opt.value);
    setOpen(false);
    setQuery('');
  };

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'ArrowDown') { e.preventDefault(); setActive((a) => Math.min(a + 1, filtered.length - 1)); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); setActive((a) => Math.max(a - 1, 0)); }
    else if (e.key === 'Enter') { e.preventDefault(); if (filtered[active]) choose(filtered[active]); }
    else if (e.key === 'Escape') { setOpen(false); setQuery(''); }
  };

  const base =
    'w-full rounded-md border border-slate-300 px-3 py-2 text-sm bg-white text-left ' +
    'focus:outline-none focus:border-sigdep-500 focus:ring-1 focus:ring-sigdep-500 ' +
    'disabled:bg-slate-100 disabled:text-ink-subtle flex items-center justify-between gap-2';

  return (
    <div ref={rootRef} className={`relative ${className ?? ''}`}>
      <button
        type="button"
        disabled={disabled}
        onClick={() => !disabled && setOpen((o) => !o)}
        className={base}
      >
        <span className={selected ? '' : 'text-ink-subtle'}>
          {selected ? selected.label : placeholder}
        </span>
        <span className="flex items-center gap-1 shrink-0">
          {selected && !disabled && (
            <X
              className="h-3.5 w-3.5 text-ink-subtle hover:text-ink-muted"
              onClick={(e) => { e.stopPropagation(); onChange(null); }}
            />
          )}
          <ChevronDown className="h-4 w-4 text-ink-subtle" />
        </span>
      </button>

      {open && (
        <div className="absolute z-50 mt-1 w-full rounded-md border border-slate-200 bg-white shadow-lg">
          <div className="p-2 border-b border-slate-100">
            <input
              ref={inputRef}
              value={query}
              onChange={(e) => { setQuery(e.target.value); setActive(0); }}
              onKeyDown={onKeyDown}
              placeholder="Rechercher…"
              className="w-full rounded border border-slate-300 px-2 py-1.5 text-sm
                         focus:outline-none focus:border-sigdep-500 focus:ring-1 focus:ring-sigdep-500"
            />
          </div>
          <ul className="max-h-56 overflow-y-auto py-1">
            {filtered.length === 0 ? (
              <li className="px-3 py-2 text-xs text-ink-subtle">Aucun résultat</li>
            ) : (
              filtered.map((o, i) => (
                <li key={o.value}>
                  <button
                    type="button"
                    onMouseEnter={() => setActive(i)}
                    onClick={() => choose(o)}
                    className={`w-full text-left px-3 py-1.5 text-sm flex items-center gap-2
                                ${i === active ? 'bg-sigdep-50' : ''}`}
                  >
                    <Check className={`h-3.5 w-3.5 shrink-0 ${o.value === value ? 'text-sigdep-600' : 'invisible'}`} />
                    <span>{o.label}</span>
                  </button>
                </li>
              ))
            )}
          </ul>
        </div>
      )}
    </div>
  );
}
