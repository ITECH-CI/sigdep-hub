import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  CreateUserRequest, UpdateUserRequest, UserDetail, UserRow,
  createUser, fetchDistricts, fetchRegions, fetchSitesOf, fetchUser,
  fetchUserRoles, fetchUsers, resetUserPassword, sendUserResetLink, setUserEnabled, updateUser,
} from '../api/client';
import { Search, ShieldCheck, UserPlus } from 'lucide-react';
import { formatInt } from '../components/Kpi';
import { PageHeader } from '../components/PageHeader';
import { PasswordInput } from '../components/PasswordInput';
import { Combobox } from '../components/Combobox';
import { TableSkeleton } from '../components/Skeleton';

function formatTimestamp(ms: number | null): string {
  if (!ms) return '—';
  return new Date(ms).toLocaleDateString('fr-FR',
    { day: '2-digit', month: 'short', year: 'numeric' });
}

type ModalKind =
  | { kind: 'none' }
  | { kind: 'create' }
  | { kind: 'edit'; userId: number }
  | { kind: 'password'; userId: number; email: string }
  | { kind: 'disable'; user: UserRow };

/**
 * Chaque rôle zone-bound impose un niveau de portée géographique. Les autres
 * rôles sont nationaux (pas de scope). En v2.0 un compte porte un seul rôle.
 */
const SCOPED_ROLES: Record<string, 'region' | 'district' | 'site'> = {
  REGIONAL_COORD: 'region',
  DISTRICT_COORD: 'district',
  SITE_USER:      'site',
};

type ScopeIndex = {
  regions: Map<number, string>;
  districts: Map<number, string>;
};

function scopeLabel(
  u: { regionId: number | null; districtId: number | null; siteId: number | null },
  idx: ScopeIndex,
): string {
  if (u.siteId)     return `Site #${u.siteId}`;
  if (u.districtId) return `District: ${idx.districts.get(u.districtId) ?? `#${u.districtId}`}`;
  if (u.regionId)   return `Région: ${idx.regions.get(u.regionId) ?? `#${u.regionId}`}`;
  return '—';
}

export function Utilisateurs() {
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const [modal, setModal] = useState<ModalKind>({ kind: 'none' });
  const size = 50;
  const qc = useQueryClient();

  const users = useQuery({
    queryKey: ['users', query, page],
    queryFn: () => fetchUsers({ q: query, page, size }),
  });

  // Résout les noms région/district pour la colonne Zone. ~100 districts au
  // niveau national, donc le fetch global est peu coûteux.
  const regionsQ = useQuery({ queryKey: ['regions'], queryFn: () => fetchRegions() });
  const districtsQ = useQuery({ queryKey: ['districts', null], queryFn: () => fetchDistricts() });

  const idx: ScopeIndex = {
    regions: new Map((regionsQ.data ?? []).map(r => [r.id, r.name])),
    districts: new Map((districtsQ.data ?? []).map(d => [d.id, d.name])),
  };

  const totalPages = users.data
    ? Math.max(1, Math.ceil(users.data.total / users.data.size)) : 1;

  function refresh() { qc.invalidateQueries({ queryKey: ['users'] }); }

  return (
    <div className="px-6 py-6">
      <PageHeader
        icon={ShieldCheck}
        tone="admin"
        title="Utilisateurs"
        subtitle={users.data ? `${formatInt(users.data.total)} comptes` : 'Chargement…'}
        right={<>
          <div className="relative">
            <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 h-4 w-4 text-ink-subtle pointer-events-none" />
            <input
              type="search"
              value={query}
              onChange={e => { setQuery(e.target.value); setPage(0); }}
              placeholder="Rechercher (nom, email)…"
              className="w-72 rounded-md border border-slate-300 pl-8 pr-3 py-2 text-sm
                         focus:outline-none focus:border-accent-500 focus:ring-1 focus:ring-accent-500"
            />
          </div>
          <button
            onClick={() => setModal({ kind: 'create' })}
            className="inline-flex items-center gap-1.5 rounded-md bg-accent-600 hover:bg-accent-700
                       text-white px-3 py-2 text-sm transition">
            <UserPlus className="h-4 w-4" />
            Nouvel utilisateur
          </button>
        </>} />

      <div className="card overflow-hidden">
        <table className="w-full text-sm">
          <thead className="thead-sigdep text-left">
            <tr className="text-left">
              <th className="px-4 py-2 font-medium">Email</th>
              <th className="px-4 py-2 font-medium">Nom complet</th>
              <th className="px-4 py-2 font-medium">Rôle</th>
              <th className="px-4 py-2 font-medium">Zone</th>
              <th className="px-4 py-2 font-medium">Statut</th>
              <th className="px-4 py-2 font-medium">Dernière connexion</th>
              <th className="px-4 py-2 font-medium text-right">Actions</th>
            </tr>
          </thead>
          {users.isLoading ? (
            <TableSkeleton rows={8} cols={7} />
          ) : (
          <tbody className="divide-y divide-slate-100">
            {users.isError ? (
              <tr><td colSpan={7} className="px-4 py-6 text-center text-rose-600">
                Erreur de chargement des utilisateurs.
              </td></tr>
            ) : users.data?.content.length === 0 ? (
              <tr><td colSpan={7} className="px-4 py-6 text-center text-ink-muted">Aucun utilisateur</td></tr>
            ) : users.data?.content.map(u => (
              <tr key={u.id} className="hover:bg-slate-50">
                <td className="px-4 py-2 font-mono text-xs">{u.email}</td>
                <td className="px-4 py-2">{u.displayName || '—'}</td>
                <td className="px-4 py-2 font-mono text-xs">{u.role}</td>
                <td className="px-4 py-2 text-ink-muted text-xs">{scopeLabel(u, idx)}</td>
                <td className="px-4 py-2">
                  {u.active
                    ? <span className="text-xs px-2 py-0.5 rounded bg-emerald-50 text-emerald-700">Actif</span>
                    : <span className="text-xs px-2 py-0.5 rounded bg-slate-100 text-slate-500">Désactivé</span>}
                </td>
                <td className="px-4 py-2 text-ink-muted">{formatTimestamp(u.lastLoginAt)}</td>
                <td className="px-4 py-2 text-right whitespace-nowrap">
                  <button
                    onClick={() => setModal({ kind: 'edit', userId: u.id })}
                    className="text-sigdep-700 hover:underline text-xs mr-3">
                    Éditer
                  </button>
                  <button
                    onClick={() => setModal({ kind: 'password', userId: u.id, email: u.email })}
                    className="text-sigdep-700 hover:underline text-xs mr-3">
                    Mot de passe
                  </button>
                  {u.active ? (
                    <button
                      onClick={() => setModal({ kind: 'disable', user: u })}
                      className="text-rose-600 hover:underline text-xs">
                      Désactiver
                    </button>
                  ) : (
                    <button
                      onClick={async () => {
                        await setUserEnabled(u.id, true);
                        refresh();
                      }}
                      className="text-emerald-700 hover:underline text-xs">
                      Réactiver
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
          )}
        </table>
      </div>

      {users.data && users.data.total > size && (
        <div className="mt-4 flex items-center justify-between text-sm">
          <p className="text-ink-muted">Page {users.data.page + 1} / {totalPages}</p>
          <div className="flex gap-2">
            <button onClick={() => setPage(p => Math.max(0, p - 1))}
                    disabled={users.data.page === 0}
                    className="px-3 py-1 rounded border border-slate-300 disabled:opacity-50 hover:bg-slate-50">
              Précédent
            </button>
            <button onClick={() => setPage(p => p + 1)}
                    disabled={users.data.page + 1 >= totalPages}
                    className="px-3 py-1 rounded border border-slate-300 disabled:opacity-50 hover:bg-slate-50">
              Suivant
            </button>
          </div>
        </div>
      )}

      {modal.kind === 'create' && (
        <CreateModal onClose={() => setModal({ kind: 'none' })} onDone={() => { refresh(); setModal({ kind: 'none' }); }} />
      )}
      {modal.kind === 'edit' && (
        <EditModal userId={modal.userId}
                   onClose={() => setModal({ kind: 'none' })}
                   onDone={() => { refresh(); setModal({ kind: 'none' }); }} />
      )}
      {modal.kind === 'password' && (
        <PasswordModal userId={modal.userId} email={modal.email}
                       onClose={() => setModal({ kind: 'none' })} />
      )}
      {modal.kind === 'disable' && (
        <DisableModal user={modal.user}
                      onClose={() => setModal({ kind: 'none' })}
                      onDone={() => { refresh(); setModal({ kind: 'none' }); }} />
      )}
    </div>
  );
}

// ---------- modals ---------------------------------------------------------

function ModalShell({ title, children, onClose, footer }:
    Readonly<{ title: string; children: React.ReactNode; onClose: () => void; footer?: React.ReactNode }>) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4"
         onClick={onClose}>
      <div className="bg-white rounded-lg shadow-xl w-full max-w-lg max-h-[90vh] flex flex-col"
           onClick={e => e.stopPropagation()}>
        <div className="px-5 py-3 border-b border-slate-200 flex items-center justify-between">
          <h3 className="text-base font-semibold text-sigdep-800">{title}</h3>
          <button onClick={onClose} className="text-ink-muted hover:text-ink text-lg leading-none">&times;</button>
        </div>
        <div className="px-5 py-4 space-y-3 text-sm overflow-y-auto">{children}</div>
        {footer && <div className="px-5 py-3 border-t border-slate-200 flex justify-end gap-2">{footer}</div>}
      </div>
    </div>
  );
}

// NB : on n'enveloppe PAS les champs dans un <label>. Un <select> imbriqué
// dans un <label> peut voir son événement « change » avalé (le clic sur une
// option propage au label parent), d'où des sélecteurs qui « ne gardent pas »
// leur valeur. On utilise donc un <div> + un <span> de libellé.
function Field({ label, children }: Readonly<{ label: string; children: React.ReactNode }>) {
  return (
    <div className="block">
      <span className="block text-xs font-medium text-ink-muted mb-1">{label}</span>
      {children}
    </div>
  );
}

const inputClass =
  'w-full rounded-md border border-slate-300 px-3 py-2 text-sm focus:outline-none focus:border-sigdep-500 focus:ring-1 focus:ring-sigdep-500';

/** epoch-millis → 'YYYY-MM-DD' pour un <input type="date">, ou '' si null. */
function epochToDateInput(epoch: number | null | undefined): string {
  if (!epoch) return '';
  return new Date(epoch).toISOString().slice(0, 10);
}

/** 'YYYY-MM-DD' (fin de journée locale) → epoch-millis, ou null si vide. */
function dateInputToEpoch(value: string): number | null {
  if (!value) return null;
  // Expire en fin de journée pour que la date saisie reste valide tout du long.
  return new Date(`${value}T23:59:59`).getTime();
}

type ScopeState = {
  regionId: number | null;
  districtId: number | null;
  siteId: number | null;
};

/**
 * Sélecteur rôle unique + portée géographique en cascade qui apparaît quand le
 * rôle choisi est zone-bound (REGIONAL_COORD / DISTRICT_COORD / SITE_USER).
 */
function RoleAndScopePicker({
  roles, role, onRoleChange, scope, onScopeChange,
}: Readonly<{
  roles: string[];
  role: string;
  onRoleChange: (role: string) => void;
  scope: ScopeState;
  onScopeChange: (next: ScopeState) => void;
}>) {
  const activeScoped = SCOPED_ROLES[role] ?? null;

  const regions = useQuery({ queryKey: ['regions'], queryFn: () => fetchRegions() });
  const districts = useQuery({
    queryKey: ['districts', scope.regionId],
    queryFn: () => fetchDistricts(scope.regionId ?? undefined),
    enabled: scope.regionId != null,
  });
  const sites = useQuery({
    queryKey: ['sitesOf', scope.regionId, scope.districtId],
    queryFn: () => fetchSitesOf(scope.regionId ?? undefined, scope.districtId ?? undefined),
    enabled: scope.regionId != null || scope.districtId != null,
  });

  return (
    <>
      <Field label="Rôle">
        <select className={inputClass} value={role}
                onChange={e => onRoleChange(e.target.value)}>
          <option value="">— Choisir un rôle —</option>
          {roles.map(r => <option key={r} value={r}>{r}</option>)}
        </select>
      </Field>

      {activeScoped && (
        <div className="rounded-md border border-sigdep-200 bg-sigdep-50/40 p-3 space-y-2">
          <p className="text-xs font-medium text-sigdep-800">
            Zone d'intervention {activeScoped === 'region' ? '(région)' : activeScoped === 'district' ? '(district)' : '(site)'}
          </p>

          <Field label="Région">
            <Combobox
              options={(regions.data ?? []).map(r => ({ value: r.id, label: r.name }))}
              value={scope.regionId}
              onChange={v => onScopeChange({ regionId: v, districtId: null, siteId: null })}
            />
          </Field>

          {(activeScoped === 'district' || activeScoped === 'site') && (
            <Field label="District">
              <Combobox
                options={(districts.data ?? []).map(d => ({ value: d.id, label: d.name }))}
                value={scope.districtId}
                disabled={scope.regionId == null}
                placeholder={scope.regionId == null ? "Choisis d'abord une région" : '— Choisir —'}
                onChange={v => onScopeChange({ regionId: scope.regionId, districtId: v, siteId: null })}
              />
            </Field>
          )}

          {activeScoped === 'site' && (
            <Field label="Site">
              <Combobox
                options={(sites.data ?? []).map(s => ({ value: s.id, label: `${s.code} — ${s.name}` }))}
                value={scope.siteId}
                disabled={scope.districtId == null}
                placeholder={scope.districtId == null ? "Choisis d'abord un district" : '— Choisir —'}
                onChange={v => onScopeChange({ regionId: scope.regionId, districtId: scope.districtId, siteId: v })}
              />
            </Field>
          )}
        </div>
      )}
    </>
  );
}

function scopeIsValid(role: string, scope: ScopeState): boolean {
  const activeScoped = SCOPED_ROLES[role] ?? null;
  if (activeScoped === 'region')   return scope.regionId != null;
  if (activeScoped === 'district') return scope.regionId != null && scope.districtId != null;
  if (activeScoped === 'site')     return scope.regionId != null && scope.districtId != null && scope.siteId != null;
  return role !== '';
}

function CreateModal({ onClose, onDone }: Readonly<{ onClose: () => void; onDone: () => void }>) {
  const [form, setForm] = useState<CreateUserRequest>({
    email: '', displayName: '', role: '',
    active: true, password: '', passwordTemporary: true,
    regionId: null, districtId: null, siteId: null,
  });
  const [confirmPassword, setConfirmPassword] = useState('');
  // 'link' = l'utilisateur reçoit un email pour définir son mot de passe ;
  // 'manual' = l'admin définit un mot de passe initial.
  const [mode, setMode] = useState<'link' | 'manual'>('link');
  const roles = useQuery({ queryKey: ['user-roles'], queryFn: fetchUserRoles });
  // En mode lien, on n'envoie PAS de mot de passe → le backend envoie l'email.
  const m = useMutation({
    mutationFn: () => createUser(mode === 'link'
      ? { ...form, password: undefined, passwordTemporary: undefined }
      : form),
    onSuccess: onDone,
  });

  const scope: ScopeState = {
    regionId: form.regionId ?? null,
    districtId: form.districtId ?? null,
    siteId: form.siteId ?? null,
  };
  const passwordOk = mode === 'link' || (!!form.password && form.password === confirmPassword);
  const scopeOk = scopeIsValid(form.role, scope);
  const canSubmit = !!form.email && !!form.role && passwordOk && scopeOk && !m.isPending;

  return (
    <ModalShell title="Nouvel utilisateur" onClose={onClose}
      footer={<>
        <button onClick={onClose} className="px-3 py-1.5 text-sm border border-slate-300 rounded">Annuler</button>
        <button
          onClick={() => m.mutate()}
          disabled={!canSubmit}
          className="px-3 py-1.5 text-sm rounded bg-sigdep-600 text-white hover:bg-sigdep-700 disabled:opacity-50">
          {m.isPending ? 'Création…' : 'Créer'}
        </button>
      </>}>
      <Field label="Adresse e-mail (identifiant de connexion)">
        <input className={inputClass} type="email" value={form.email}
               onChange={e => setForm({ ...form, email: e.target.value })} />
      </Field>
      <Field label="Nom complet">
        <input className={inputClass} value={form.displayName ?? ''}
               onChange={e => setForm({ ...form, displayName: e.target.value })} />
      </Field>
      <div className="rounded-md border border-slate-200 p-3 space-y-2">
        <p className="text-xs font-medium text-ink-muted">Mot de passe</p>
        <label className="flex items-start gap-2 text-xs cursor-pointer">
          <input type="radio" name="pwd-mode" checked={mode === 'link'}
                 onChange={() => setMode('link')} className="mt-0.5" />
          <span>
            <span className="font-medium">Envoyer un lien par email</span> — l'utilisateur
            définit lui-même son mot de passe (recommandé). Nécessite un email valide.
          </span>
        </label>
        <label className="flex items-start gap-2 text-xs cursor-pointer">
          <input type="radio" name="pwd-mode" checked={mode === 'manual'}
                 onChange={() => setMode('manual')} className="mt-0.5" />
          <span className="font-medium">Définir un mot de passe initial maintenant</span>
        </label>

        {mode === 'manual' && (
          <div className="space-y-2 pt-1">
            <Field label="Mot de passe initial">
              <PasswordInput autoComplete="new-password" value={form.password ?? ''}
                             onChange={v => setForm({ ...form, password: v })} />
            </Field>
            <Field label="Confirmer le mot de passe">
              <PasswordInput autoComplete="new-password" value={confirmPassword}
                             onChange={setConfirmPassword} />
            </Field>
            {form.password && !passwordOk && (
              <p className="text-rose-600 text-xs">Les mots de passe ne correspondent pas.</p>
            )}
            <label className="flex items-center gap-2 text-xs">
              <input type="checkbox" checked={form.passwordTemporary ?? true}
                     onChange={e => setForm({ ...form, passwordTemporary: e.target.checked })} />
              Mot de passe temporaire (l'utilisateur devra le changer)
            </label>
          </div>
        )}
      </div>
      <RoleAndScopePicker
        roles={roles.data ?? []}
        role={form.role}
        onRoleChange={role => setForm(f => ({ ...f, role, regionId: null, districtId: null, siteId: null }))}
        scope={scope}
        onScopeChange={s => setForm(f => ({ ...f, regionId: s.regionId, districtId: s.districtId, siteId: s.siteId }))}
      />
      {form.role !== '' && !scopeOk && (
        <p className="text-rose-600 text-xs">Sélectionne la zone correspondant au rôle géographique.</p>
      )}

      <label className="flex items-center gap-2 text-xs">
        <input type="checkbox" checked={form.active ?? true}
               onChange={e => setForm({ ...form, active: e.target.checked })} />
        Compte actif
      </label>
      <Field label="Expiration du mot de passe (optionnel)">
        <input className={inputClass} type="date"
               value={epochToDateInput(form.passwordExpiresAt)}
               onChange={e => setForm({ ...form, passwordExpiresAt: dateInputToEpoch(e.target.value) })} />
        <p className="text-[11px] text-ink-subtle mt-1">
          Après cette date, l'utilisateur ne pourra plus se connecter ; seul un
          administrateur pourra prolonger ou réinitialiser le mot de passe.
        </p>
      </Field>
      {m.isError && <p className="text-rose-600 text-xs">{(m.error as Error).message}</p>}
    </ModalShell>
  );
}

function EditModal({ userId, onClose, onDone }:
    Readonly<{ userId: number; onClose: () => void; onDone: () => void }>) {
  const detail = useQuery({ queryKey: ['user', userId], queryFn: () => fetchUser(userId) });
  const roles = useQuery({ queryKey: ['user-roles'], queryFn: fetchUserRoles });
  const [form, setForm] = useState<UpdateUserRequest | null>(null);
  const m = useMutation({
    mutationFn: () => updateUser(userId, form ?? {}),
    onSuccess: onDone,
  });

  // Initialise le formulaire une fois le détail chargé.
  if (detail.data && form === null) {
    const d = detail.data as UserDetail;
    setForm({
      displayName: d.displayName ?? '',
      role: d.role,
      active: d.active,
      passwordExpiresAt: d.passwordExpiresAt ?? null,
      regionId: d.regionId ?? null,
      districtId: d.districtId ?? null,
      siteId: d.siteId ?? null,
    });
  }

  const scope: ScopeState = {
    regionId: form?.regionId ?? null,
    districtId: form?.districtId ?? null,
    siteId: form?.siteId ?? null,
  };
  const scopeOk = form == null || scopeIsValid(form.role ?? '', scope);
  const canSubmit = form != null && !!form.role && scopeOk && !m.isPending;

  return (
    <ModalShell title={`Édition — ${detail.data?.email ?? '…'}`} onClose={onClose}
      footer={<>
        <button onClick={onClose} className="px-3 py-1.5 text-sm border border-slate-300 rounded">Annuler</button>
        <button
          onClick={() => m.mutate()}
          disabled={!canSubmit}
          className="px-3 py-1.5 text-sm rounded bg-sigdep-600 text-white hover:bg-sigdep-700 disabled:opacity-50">
          {m.isPending ? 'Enregistrement…' : 'Enregistrer'}
        </button>
      </>}>
      {detail.isLoading || !form ? (
        <p className="text-ink-muted">Chargement…</p>
      ) : (
        <>
          <Field label="Nom complet">
            <input className={inputClass} value={form.displayName ?? ''}
                   onChange={e => setForm({ ...form, displayName: e.target.value })} />
          </Field>
          <label className="flex items-center gap-2 text-xs">
            <input type="checkbox" checked={form.active ?? true}
                   onChange={e => setForm({ ...form, active: e.target.checked })} />
            Compte actif
          </label>
          <RoleAndScopePicker
            roles={roles.data ?? []}
            role={form.role ?? ''}
            onRoleChange={role => setForm(f => f && ({ ...f, role, regionId: null, districtId: null, siteId: null }))}
            scope={scope}
            onScopeChange={s => setForm(f => f && ({ ...f, regionId: s.regionId, districtId: s.districtId, siteId: s.siteId }))}
          />
          {!scopeOk && (
            <p className="text-rose-600 text-xs">Sélectionne la zone correspondant au rôle géographique.</p>
          )}
          <Field label="Expiration du mot de passe (optionnel)">
            <input className={inputClass} type="date"
                   value={epochToDateInput(form.passwordExpiresAt)}
                   onChange={e => setForm({ ...form, passwordExpiresAt: dateInputToEpoch(e.target.value) })} />
            <p className="text-[11px] text-ink-subtle mt-1">
              Vide = pas d'expiration. Une date passée bloque la connexion
              jusqu'à intervention d'un administrateur.
            </p>
          </Field>
        </>
      )}
      {m.isError && <p className="text-rose-600 text-xs">{(m.error as Error).message}</p>}
    </ModalShell>
  );
}

function PasswordModal({ userId, email, onClose }:
    Readonly<{ userId: number; email: string; onClose: () => void }>) {
  // 'link' = envoyer un lien de réinitialisation (l'admin ne connaît rien) ;
  // 'manual' = définir un mot de passe maintenant.
  const [mode, setMode] = useState<'link' | 'manual'>('link');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [temporary, setTemporary] = useState(true);
  const [linkSent, setLinkSent] = useState(false);

  const sendLink = useMutation({
    mutationFn: () => sendUserResetLink(userId),
    onSuccess: () => setLinkSent(true),
  });
  const manual = useMutation({
    mutationFn: () => resetUserPassword(userId, password, temporary),
    onSuccess: onClose,
  });

  const passwordOk = !!password && password === confirm;
  const pending = sendLink.isPending || manual.isPending;
  const err = (sendLink.error ?? manual.error) as Error | null;

  return (
    <ModalShell title={`Mot de passe — ${email}`} onClose={onClose}
      footer={linkSent ? (
        <button onClick={onClose} className="px-3 py-1.5 text-sm rounded bg-sigdep-600 text-white hover:bg-sigdep-700">
          Fermer
        </button>
      ) : (<>
        <button onClick={onClose} className="px-3 py-1.5 text-sm border border-slate-300 rounded">Annuler</button>
        {mode === 'link' ? (
          <button onClick={() => sendLink.mutate()} disabled={pending}
                  className="px-3 py-1.5 text-sm rounded bg-sigdep-600 text-white hover:bg-sigdep-700 disabled:opacity-50">
            {sendLink.isPending ? 'Envoi…' : 'Envoyer le lien'}
          </button>
        ) : (
          <button onClick={() => manual.mutate()} disabled={!passwordOk || pending}
                  className="px-3 py-1.5 text-sm rounded bg-sigdep-600 text-white hover:bg-sigdep-700 disabled:opacity-50">
            {manual.isPending ? 'Application…' : 'Réinitialiser'}
          </button>
        )}
      </>)}>
      {linkSent ? (
        <p className="text-sm text-emerald-700">
          Un lien de réinitialisation a été envoyé à <span className="font-medium">{email}</span>.
        </p>
      ) : (
        <>
          <label className="flex items-start gap-2 text-xs cursor-pointer">
            <input type="radio" name="reset-mode" checked={mode === 'link'}
                   onChange={() => setMode('link')} className="mt-0.5" />
            <span>
              <span className="font-medium">Envoyer un lien de réinitialisation</span> — l'utilisateur
              redéfinit lui-même son mot de passe (recommandé). Débloque aussi un compte expiré.
            </span>
          </label>
          <label className="flex items-start gap-2 text-xs cursor-pointer">
            <input type="radio" name="reset-mode" checked={mode === 'manual'}
                   onChange={() => setMode('manual')} className="mt-0.5" />
            <span className="font-medium">Définir un mot de passe maintenant</span>
          </label>

          {mode === 'manual' && (
            <div className="space-y-2 pt-1">
              <Field label="Nouveau mot de passe">
                <PasswordInput autoComplete="new-password" value={password} onChange={setPassword} />
              </Field>
              <Field label="Confirmer le mot de passe">
                <PasswordInput autoComplete="new-password" value={confirm} onChange={setConfirm} />
              </Field>
              {password && !passwordOk && (
                <p className="text-rose-600 text-xs">Les mots de passe ne correspondent pas.</p>
              )}
              <label className="flex items-center gap-2 text-xs">
                <input type="checkbox" checked={temporary}
                       onChange={e => setTemporary(e.target.checked)} />
                Temporaire (l'utilisateur devra le changer à la prochaine connexion)
              </label>
            </div>
          )}
        </>
      )}
      {err && <p className="text-rose-600 text-xs">{err.message}</p>}
    </ModalShell>
  );
}

function DisableModal({ user, onClose, onDone }:
    Readonly<{ user: UserRow; onClose: () => void; onDone: () => void }>) {
  const m = useMutation({
    mutationFn: () => setUserEnabled(user.id, false),
    onSuccess: onDone,
  });
  return (
    <ModalShell title="Désactiver l'utilisateur" onClose={onClose}
      footer={<>
        <button onClick={onClose} className="px-3 py-1.5 text-sm border border-slate-300 rounded">Annuler</button>
        <button
          onClick={() => m.mutate()}
          disabled={m.isPending}
          className="px-3 py-1.5 text-sm rounded bg-rose-600 text-white hover:bg-rose-700 disabled:opacity-50">
          {m.isPending ? 'Désactivation…' : 'Désactiver'}
        </button>
      </>}>
      <p>
        Confirmer la désactivation du compte <span className="font-mono">{user.email}</span> ?
      </p>
      <p className="text-xs text-ink-muted">
        Le compte ne pourra plus se connecter mais ses données restent en place.
        Tu peux réactiver le compte à tout moment depuis la liste.
      </p>
      {m.isError && <p className="text-rose-600 text-xs">{(m.error as Error).message}</p>}
    </ModalShell>
  );
}
