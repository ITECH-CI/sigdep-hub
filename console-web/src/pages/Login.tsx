import { FormEvent, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth';
import { PasswordInput } from '../components/PasswordInput';
import { PartnerLogos } from '../components/PartnerLogos';

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
    <div className="min-h-screen flex flex-col bg-gradient-to-br from-sigdep-50 via-white to-slate-100">
      {/* Header */}
      <header className="border-b border-slate-200 bg-white/80 backdrop-blur">
        <div className="mx-auto max-w-6xl flex items-center gap-3 px-6 py-3">
          <img src="/logos/sigdep3_crop.png" alt="" className="h-10 w-10" />
          <img
            src="/logos/sigdep_logo_text_small.png"
            alt="SIGDEP-3"
            className="h-9 w-auto"
          />
          <span className="text-sm text-ink-muted hidden sm:inline border-l border-slate-200 pl-3 ml-1">
            PNLS · Côte d'Ivoire
          </span>
        </div>
      </header>

      {/* Hero + carte de connexion */}
      <main className="flex-1 flex items-center justify-center px-6 py-12">
        <div className="w-full max-w-5xl grid md:grid-cols-2 gap-10 items-center">
          {/* Colonne gauche : pitch */}
          <div className="hidden md:block">
            <h1 className="text-3xl font-semibold tracking-tight text-ink leading-snug">
              Serveur consolidé de suivi des patients vivant avec le VIH
            </h1>
            <p className="mt-4 text-ink-muted leading-relaxed">
              Plateforme nationale SIGDEP-3 — indicateurs, file active, suivi
              clinique, PTME, dépistage et synchronisation des sites.
            </p>
            <div className="mt-8">
              <PartnerLogos size="sm" />
            </div>
          </div>

          {/* Colonne droite : formulaire */}
          <div className="mx-auto w-full max-w-sm">
            <div className="card p-7 shadow-sm">
              <div className="md:hidden flex justify-center mb-5">
                <img src="/logos/sigdep_logo_text_small.png" alt="SIGDEP-3" className="h-9 w-auto" />
              </div>
              <h2 className="text-lg font-semibold tracking-tight">Connexion</h2>
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
                  <PasswordInput
                    id="password"
                    autoComplete="current-password"
                    required
                    value={password}
                    onChange={setPassword}
                  />
                  <div className="mt-1.5 text-right">
                    <Link
                      to="/mot-de-passe-oublie"
                      className="text-xs text-sigdep-700 hover:underline"
                    >
                      Mot de passe oublié ?
                    </Link>
                  </div>
                </div>

                {error && (
                  <p className="text-sm text-rose-600" role="alert">
                    {error}
                  </p>
                )}

                <button
                  type="submit"
                  disabled={submitting}
                  className="w-full rounded-md bg-sigdep-500 px-4 py-2.5 text-sm font-medium text-white
                             hover:bg-sigdep-600 transition disabled:opacity-60"
                >
                  {submitting ? 'Connexion…' : 'Se connecter'}
                </button>
              </form>
            </div>

            {/* Logos partenaires sous la carte en mobile */}
            <div className="md:hidden mt-8 flex justify-center">
              <PartnerLogos size="sm" />
            </div>
          </div>
        </div>
      </main>

      {/* Footer */}
      <footer className="border-t border-slate-200 bg-white/80">
        <div className="mx-auto max-w-6xl px-6 py-4">
          <p className="text-xs text-ink-subtle text-center">
            Ministère de la Santé, de l'Hygiène Publique et de la Couverture
            Maladie Universelle · PNLS · 2026
          </p>
          <p
            className="mt-1 text-center text-xs text-slate-400"
            title={`Version ${__APP_VERSION__}${
              __APP_COMMIT__ ? ` · build ${__APP_COMMIT__}` : ''
            }`}
          >
            {[
              `v${__APP_VERSION__}`,
              __APP_COMMIT__ && __APP_COMMIT__ !== 'local' ? __APP_COMMIT__ : null,
              __APP_BUILD_DATE__ || null,
            ]
              .filter(Boolean)
              .join(' · ')}
          </p>
        </div>
      </footer>
    </div>
  );
}
