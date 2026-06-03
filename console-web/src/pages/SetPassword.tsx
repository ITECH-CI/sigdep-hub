import { FormEvent, useEffect, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { resetPassword, validateResetToken } from '../api/auth';
import { PasswordInput } from '../components/PasswordInput';
import { AuthShell } from './ForgotPassword';

type TokenState = 'checking' | 'valid' | 'invalid';

export function SetPassword() {
  const [params] = useSearchParams();
  const token = params.get('token') ?? '';
  const navigate = useNavigate();

  const [tokenState, setTokenState] = useState<TokenState>('checking');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [done, setDone] = useState(false);

  useEffect(() => {
    if (!token) { setTokenState('invalid'); return; }
    let cancelled = false;
    validateResetToken(token).then((ok) => {
      if (!cancelled) setTokenState(ok ? 'valid' : 'invalid');
    });
    return () => { cancelled = true; };
  }, [token]);

  const tooShort = password.length > 0 && password.length < 8;
  const mismatch = confirm.length > 0 && password !== confirm;
  const canSubmit = password.length >= 8 && password === confirm && !submitting;

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await resetPassword(token, password);
      setDone(true);
      setTimeout(() => navigate('/login', { replace: true }), 2500);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Échec de la réinitialisation');
    } finally {
      setSubmitting(false);
    }
  };

  if (tokenState === 'checking') {
    return <AuthShell title="Mot de passe"><p className="text-sm text-ink-muted">Vérification du lien…</p></AuthShell>;
  }

  if (tokenState === 'invalid') {
    return (
      <AuthShell title="Lien invalide">
        <p className="text-sm text-ink-muted leading-relaxed mb-4">
          Ce lien est invalide, déjà utilisé ou expiré. Demandez-en un nouveau
          depuis la page « Mot de passe oublié ».
        </p>
        <Link to="/mot-de-passe-oublie" className="text-sm text-sigdep-700 hover:underline">
          Demander un nouveau lien
        </Link>
      </AuthShell>
    );
  }

  if (done) {
    return (
      <AuthShell title="Mot de passe enregistré">
        <p className="text-sm text-ink-muted leading-relaxed">
          Votre mot de passe a été défini avec succès. Redirection vers la
          connexion…
        </p>
        <Link to="/login" className="inline-block mt-4 text-sm text-sigdep-700 hover:underline">
          Aller à la connexion
        </Link>
      </AuthShell>
    );
  }

  return (
    <AuthShell title="Définir votre mot de passe">
      <form onSubmit={onSubmit} className="space-y-4">
        <p className="text-sm text-ink-muted leading-relaxed">
          Choisissez un mot de passe d'au moins 8 caractères.
        </p>
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
          {submitting ? 'Enregistrement…' : 'Enregistrer le mot de passe'}
        </button>
      </form>
    </AuthShell>
  );
}
