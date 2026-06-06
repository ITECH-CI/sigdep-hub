import { FormEvent, useState } from 'react';
import { Link } from 'react-router-dom';
import { forgotPassword } from '../api/auth';
import { Footer } from '../components/Footer';

export function ForgotPassword() {
  const [email, setEmail] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [done, setDone] = useState(false);

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    try {
      await forgotPassword(email.trim());
    } catch {
      // On n'expose jamais l'erreur : message générique anti-énumération.
    } finally {
      setSubmitting(false);
      setDone(true);
    }
  };

  return (
    <AuthShell title="Mot de passe oublié">
      {done ? (
        <div className="space-y-4">
          <p className="text-sm text-ink-muted leading-relaxed">
            Si un compte est associé à <span className="font-medium">{email}</span>,
            un email contenant un lien de réinitialisation vient d'être envoyé.
            Vérifiez votre boîte de réception (et vos spams).
          </p>
          <p className="text-sm text-ink-muted">
            Le lien expire dans 1 heure.
          </p>
          <Link to="/login" className="inline-block text-sm text-sigdep-700 hover:underline">
            ← Retour à la connexion
          </Link>
        </div>
      ) : (
        <form onSubmit={onSubmit} className="space-y-4">
          <p className="text-sm text-ink-muted leading-relaxed">
            Saisissez l'adresse e-mail de votre compte. Nous vous enverrons un
            lien pour réinitialiser votre mot de passe.
          </p>
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
          <button
            type="submit"
            disabled={submitting}
            className="w-full rounded-md bg-sigdep-500 px-4 py-2.5 text-sm font-medium text-white
                       hover:bg-sigdep-600 transition disabled:opacity-60"
          >
            {submitting ? 'Envoi…' : 'Envoyer le lien'}
          </button>
          <Link to="/login" className="block text-center text-sm text-sigdep-700 hover:underline">
            ← Retour à la connexion
          </Link>
        </form>
      )}
    </AuthShell>
  );
}

/** Coquille visuelle partagée par les pages publiques d'authentification. */
export function AuthShell({ title, children }:
    Readonly<{ title: string; children: React.ReactNode }>) {
  return (
    <div className="min-h-screen flex flex-col bg-gradient-to-br from-sigdep-50 via-white to-slate-100">
      <header className="border-b border-slate-200 bg-white/80 backdrop-blur">
        <div className="mx-auto max-w-6xl flex items-center gap-3 px-6 py-3">
          <img src="/logos/sigdep3_crop.png" alt="" className="h-10 w-10" />
          <img src="/logos/sigdep_logo_text_small.png" alt="SIGDEP-3" className="h-9 w-auto" />
        </div>
      </header>
      <main className="flex-1 flex items-center justify-center px-6 py-12">
        <div className="w-full max-w-sm">
          <div className="card p-7 shadow-sm">
            <h1 className="text-lg font-semibold tracking-tight mb-5">{title}</h1>
            {children}
          </div>
        </div>
      </main>
      <Footer />
    </div>
  );
}
