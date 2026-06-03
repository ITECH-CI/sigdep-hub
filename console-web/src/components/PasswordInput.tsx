import { useState } from 'react';
import { Eye, EyeOff } from 'lucide-react';

/**
 * Champ mot de passe avec bouton « œil » pour révéler / masquer la saisie.
 * Réutilisé sur la connexion, la définition/réinitialisation de mot de passe
 * et la gestion des utilisateurs.
 */
export function PasswordInput({
  id, value, onChange, autoComplete, placeholder, required, className,
}: Readonly<{
  id?: string;
  value: string;
  onChange: (v: string) => void;
  autoComplete?: string;
  placeholder?: string;
  required?: boolean;
  className?: string;
}>) {
  const [visible, setVisible] = useState(false);
  return (
    <div className="relative">
      <input
        id={id}
        type={visible ? 'text' : 'password'}
        autoComplete={autoComplete}
        placeholder={placeholder}
        required={required}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className={
          className ??
          'w-full rounded-md border border-slate-300 px-3 py-2 pr-10 text-sm ' +
            'focus:border-sigdep-500 focus:outline-none focus:ring-1 focus:ring-sigdep-500'
        }
      />
      <button
        type="button"
        onClick={() => setVisible((v) => !v)}
        tabIndex={-1}
        aria-label={visible ? 'Masquer le mot de passe' : 'Afficher le mot de passe'}
        className="absolute right-2 top-1/2 -translate-y-1/2 text-ink-subtle hover:text-ink-muted p-0.5"
      >
        {visible ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
      </button>
    </div>
  );
}
