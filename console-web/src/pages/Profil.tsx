import { FormEvent, useState } from 'react';
import { UserCog } from 'lucide-react';
import { useAuth } from '../auth';
import { changeMyPassword, updateMyProfile } from '../api/client';
import { PageHeader } from '../components/PageHeader';
import { PasswordInput } from '../components/PasswordInput';

const ROLE_LABELS: Record<string, string> = {
  SUPER_ADMIN: 'Super administrateur',
  IT_ADMIN: 'Administrateur technique',
  NATIONAL_VIEWER: 'Vue nationale',
  REGIONAL_COORD: 'Coordinateur régional',
  DISTRICT_COORD: 'Coordinateur de district',
  SITE_USER: 'Utilisateur de site',
  ANALYST: 'Analyste',
  AUDITOR: 'Auditeur',
};

export function Profil() {
  const { user, logout } = useAuth();

  return (
    <div className="px-6 py-6 max-w-2xl">
      <PageHeader icon={UserCog} title="Mon profil" subtitle={user?.email ?? ''} />
      <div className="space-y-6">
        <IdentitySection />
        <ReadOnlySection />
        <PasswordSection onChanged={logout} />
      </div>
    </div>
  );

  // ---- Identité (nom modifiable) ----
  function IdentitySection() {
    const [name, setName] = useState(user?.displayName ?? '');
    const [msg, setMsg] = useState<string | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [saving, setSaving] = useState(false);

    const onSubmit = async (e: FormEvent) => {
      e.preventDefault();
      setMsg(null); setError(null); setSaving(true);
      try {
        await updateMyProfile(name.trim());
        setMsg('Nom mis à jour. Il sera visible partout à votre prochaine connexion.');
      } catch (err) {
        setError(err instanceof Error ? err.message : 'Échec de la mise à jour');
      } finally {
        setSaving(false);
      }
    };

    return (
      <form onSubmit={onSubmit} className="card p-5 space-y-3">
        <h2 className="text-sm font-semibold">Identité</h2>
        <div>
          <label htmlFor="name" className="block text-xs font-medium text-ink-muted mb-1">Nom complet</label>
          <input id="name" value={name} onChange={(e) => setName(e.target.value)}
                 className="w-full rounded-md border border-slate-300 px-3 py-2 text-sm
                            focus:border-sigdep-500 focus:outline-none focus:ring-1 focus:ring-sigdep-500" />
        </div>
        {msg && <p className="text-xs text-emerald-700">{msg}</p>}
        {error && <p className="text-xs text-rose-600">{error}</p>}
        <button type="submit" disabled={saving || !name.trim() || name.trim() === user?.displayName}
                className="rounded-md bg-sigdep-600 px-4 py-2 text-sm font-medium text-white
                           hover:bg-sigdep-700 transition disabled:opacity-50">
          {saving ? 'Enregistrement…' : 'Enregistrer'}
        </button>
      </form>
    );
  }

  // ---- Infos en lecture seule ----
  function ReadOnlySection() {
    const scope =
      user?.siteId ? `Site #${user.siteId}` :
      user?.districtId ? `District #${user.districtId}` :
      user?.regionId ? `Région #${user.regionId}` : 'National';
    return (
      <div className="card p-5 space-y-2 text-sm">
        <h2 className="text-sm font-semibold mb-1">Compte</h2>
        <Row label="Adresse e-mail" value={user?.email ?? '—'} />
        <Row label="Rôle" value={user ? (ROLE_LABELS[user.role] ?? user.role) : '—'} />
        <Row label="Périmètre" value={scope} />
        <p className="text-[11px] text-ink-subtle pt-1">
          Le rôle et le périmètre sont gérés par un administrateur.
        </p>
      </div>
    );
  }

  function Row({ label, value }: Readonly<{ label: string; value: string }>) {
    return (
      <div className="flex justify-between gap-4 border-b border-slate-100 py-1.5 last:border-0">
        <span className="text-ink-muted">{label}</span>
        <span className="font-medium text-right">{value}</span>
      </div>
    );
  }

  // ---- Mot de passe ----
  function PasswordSection({ onChanged }: Readonly<{ onChanged: () => void }>) {
    const [current, setCurrent] = useState('');
    const [pwd, setPwd] = useState('');
    const [confirm, setConfirm] = useState('');
    const [error, setError] = useState<string | null>(null);
    const [saving, setSaving] = useState(false);

    const tooShort = pwd.length > 0 && pwd.length < 8;
    const mismatch = confirm.length > 0 && pwd !== confirm;
    const canSubmit = !!current && pwd.length >= 8 && pwd === confirm && !saving;

    const onSubmit = async (e: FormEvent) => {
      e.preventDefault();
      setError(null); setSaving(true);
      try {
        await changeMyPassword(current, pwd);
        // Le backend révoque les sessions → on déconnecte proprement.
        await onChanged();
      } catch (err) {
        setError(err instanceof Error ? err.message : 'Échec du changement');
        setSaving(false);
      }
    };

    return (
      <form onSubmit={onSubmit} className="card p-5 space-y-3">
        <h2 className="text-sm font-semibold">Changer mon mot de passe</h2>
        <p className="text-xs text-ink-muted">
          Après changement, vous serez déconnecté et devrez vous reconnecter.
        </p>
        <div>
          <label htmlFor="cur" className="block text-xs font-medium text-ink-muted mb-1">Mot de passe actuel</label>
          <PasswordInput id="cur" autoComplete="current-password" value={current} onChange={setCurrent} />
        </div>
        <div>
          <label htmlFor="np" className="block text-xs font-medium text-ink-muted mb-1">Nouveau mot de passe</label>
          <PasswordInput id="np" autoComplete="new-password" value={pwd} onChange={setPwd} />
          {tooShort && <p className="text-xs text-rose-600 mt-1">8 caractères minimum.</p>}
        </div>
        <div>
          <label htmlFor="cf" className="block text-xs font-medium text-ink-muted mb-1">Confirmer</label>
          <PasswordInput id="cf" autoComplete="new-password" value={confirm} onChange={setConfirm} />
          {mismatch && <p className="text-xs text-rose-600 mt-1">Les mots de passe ne correspondent pas.</p>}
        </div>
        {error && <p className="text-xs text-rose-600">{error}</p>}
        <button type="submit" disabled={!canSubmit}
                className="rounded-md bg-sigdep-600 px-4 py-2 text-sm font-medium text-white
                           hover:bg-sigdep-700 transition disabled:opacity-50">
          {saving ? 'Changement…' : 'Changer le mot de passe'}
        </button>
      </form>
    );
  }
}
