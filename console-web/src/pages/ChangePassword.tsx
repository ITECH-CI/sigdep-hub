import { FormEvent, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../auth';
import { changeMyPassword } from '../api/client';
import { PasswordInput } from '../components/PasswordInput';
import { AuthShell } from './ForgotPassword';

/**
 * Changement de mot de passe par l'utilisateur connecté. Sert au changement
 * FORCÉ après un login avec mot de passe temporaire (la garde RequireAuth
 * redirige ici tant que mustChangePassword est vrai). Demande l'ancien +
 * le nouveau ; le backend révoque ensuite les sessions, donc on déconnecte
 * et on renvoie vers la page de connexion.
 */
export function ChangePassword() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  const [current, setCurrent] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const forced = user?.mustChangePassword ?? false;
  const tooShort = password.length > 0 && password.length < 8;
  const mismatch = confirm.length > 0 && password !== confirm;
  const canSubmit = !!current && password.length >= 8 && password === confirm && !submitting;

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await changeMyPassword(current, password);
      // Les sessions sont révoquées côté serveur → on déconnecte proprement.
      await logout();
      navigate('/login', { replace: true });
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Échec du changement de mot de passe');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <AuthShell title="Changer votre mot de passe">
      <form onSubmit={onSubmit} className="space-y-4">
        <p className="text-sm text-ink-muted leading-relaxed">
          {forced
            ? 'Votre mot de passe est temporaire. Pour continuer, définissez un nouveau mot de passe (8 caractères minimum).'
            : 'Définissez un nouveau mot de passe (8 caractères minimum).'}
        </p>
        <div>
          <label htmlFor="cur" className="block text-sm font-medium mb-1">Mot de passe actuel</label>
          <PasswordInput id="cur" autoComplete="current-password" required value={current} onChange={setCurrent} />
        </div>
        <div>
          <label htmlFor="pwd" className="block text-sm font-medium mb-1">Nouveau mot de passe</label>
          <PasswordInput id="pwd" autoComplete="new-password" required value={password} onChange={setPassword} />
          {tooShort && <p className="text-xs text-rose-600 mt-1">8 caractères minimum.</p>}
        </div>
        <div>
          <label htmlFor="pwd2" className="block text-sm font-medium mb-1">Confirmer le mot de passe</label>
          <PasswordInput id="pwd2" autoComplete="new-password" required value={confirm} onChange={setConfirm} />
          {mismatch && <p className="text-xs text-rose-600 mt-1">Les mots de passe ne correspondent pas.</p>}
        </div>
        {error && <p className="text-sm text-rose-600" role="alert">{error}</p>}
        <button
          type="submit"
          disabled={!canSubmit}
          className="w-full rounded-md bg-sigdep-500 px-4 py-2.5 text-sm font-medium text-white
                     hover:bg-sigdep-600 transition disabled:opacity-60"
        >
          {submitting ? 'Enregistrement…' : 'Changer le mot de passe'}
        </button>
        {!forced && (
          <button
            type="button"
            onClick={() => navigate('/app')}
            className="block w-full text-center text-sm text-sigdep-700 hover:underline"
          >
            Annuler
          </button>
        )}
      </form>
    </AuthShell>
  );
}
