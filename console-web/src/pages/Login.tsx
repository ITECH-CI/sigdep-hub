import { FormEvent, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth';

type LocationState = { from?: string } | null;

export function Login() {
  const { login, isAuthenticated } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const from = (location.state as LocationState)?.from ?? '/app';

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  // Déjà connecté → on saute l'écran de login.
  if (isAuthenticated) {
    navigate(from, { replace: true });
    return null;
  }

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await login(email.trim(), password);
      navigate(from, { replace: true });
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Connexion impossible');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="min-h-screen flex flex-col bg-gradient-to-b from-sigdep-50 to-white">
      <header className="border-b border-slate-200 bg-white">
        <div className="mx-auto max-w-6xl flex items-center gap-3 px-6 py-3">
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
      </header>

      <main className="flex-1 flex items-center justify-center px-6 py-12">
        <div className="card w-full max-w-sm p-6">
          <h1 className="text-lg font-semibold tracking-tight">Connexion</h1>
          <p className="mt-1 text-sm text-ink-muted">
            Accédez à la console SIGDEP-3.
          </p>

          <form onSubmit={onSubmit} className="mt-6 space-y-4">
            <div>
              <label htmlFor="email" className="block text-sm font-medium mb-1">
                Adresse e-mail
              </label>
              <input
                id="email"
                type="email"
                autoComplete="username"
                required
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                className="w-full rounded-md border border-slate-300 px-3 py-2 text-sm
                           focus:border-sigdep-500 focus:outline-none focus:ring-1 focus:ring-sigdep-500"
              />
            </div>

            <div>
              <label htmlFor="password" className="block text-sm font-medium mb-1">
                Mot de passe
              </label>
              <input
                id="password"
                type="password"
                autoComplete="current-password"
                required
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className="w-full rounded-md border border-slate-300 px-3 py-2 text-sm
                           focus:border-sigdep-500 focus:outline-none focus:ring-1 focus:ring-sigdep-500"
              />
            </div>

            {error && (
              <p className="text-sm text-rose-600" role="alert">
                {error}
              </p>
            )}

            <button
              type="submit"
              disabled={submitting}
              className="w-full rounded-md bg-sigdep-500 px-4 py-2 text-sm font-medium text-white
                         hover:bg-sigdep-600 transition disabled:opacity-60"
            >
              {submitting ? 'Connexion…' : 'Se connecter'}
            </button>
          </form>
        </div>
      </main>
    </div>
  );
}
