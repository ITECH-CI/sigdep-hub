import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  CreateUserRequest, UpdateUserRequest, UserDetail, UserRow,
  createUser, fetchDistricts, fetchRegions, fetchSitesOf, fetchUser,
  fetchUserRoles, fetchUsers, resetUserPassword, setUserEnabled, updateUser,
} from '../api/client';
import { Search, ShieldCheck, UserPlus } from 'lucide-react';
import { formatInt } from '../components/Kpi';
import { PageHeader } from '../components/PageHeader';
import { TableSkeleton } from '../components/Skeleton';

function formatTimestamp(ms: number | null): string {
  if (!ms) return '—';
  return new Date(ms).toLocaleDateString('fr-FR',
    { day: '2-digit', month: 'short', year: 'numeric' });
}

type ModalKind =
  | { kind: 'none' }
  | { kind: 'create' }
  | { kind: 'edit'; userId: string }
  | { kind: 'password'; userId: string; username: string }
  | { kind: 'disable'; user: UserRow };

/**
 * Each scoped role maps to exactly one geo level. Picking one disables the
 * others — see RoleAndScopePicker. The hub never combines two scoped roles
 * on the same account; the unscoped roles (admin / national / etc.) can be
 * combined freely.
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

  // Resolve region/district names for the Zone column. We fetch all districts
  // (no regionId filter) once — there are ~100 nationally so this is cheap.
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
        subtitle={users.data ? `${formatInt(users.data.total)} comptes Keycloak` : 'Chargement…'}
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
              <th className="px-4 py-2 font-medium">Identifiant</th>
              <th className="px-4 py-2 font-medium">Nom complet</th>
              <th className="px-4 py-2 font-medium">Email</th>
              <th className="px-4 py-2 font-medium">Zone</th>
              <th className="px-4 py-2 font-medium">Statut</th>
              <th className="px-4 py-2 font-medium">Créé le</th>
              <th className="px-4 py-2 font-medium text-right">Actions</th>
            </tr>
          </thead>
          {users.isLoading ? (
            <TableSkeleton rows={8} cols={7} />
          ) : (
          <tbody className="divide-y divide-slate-100">
            {users.isError ? (
              <tr><td colSpan={7} className="px-4 py-6 text-center text-rose-600">
                Erreur de chargement — vérifie que le client <code>sigdep-console-admin</code> est bien configuré.
              </td></tr>
            ) : users.data?.content.length === 0 ? (
              <tr><td colSpan={7} className="px-4 py-6 text-center text-ink-muted">Aucun utilisateur</td></tr>
            ) : users.data?.content.map(u => (
              <tr key={u.id} className="hover:bg-slate-50">
                <td className="px-4 py-2 font-mono text-xs">{u.username}</td>
                <td className="px-4 py-2">
                  {[u.firstName, u.lastName].filter(Boolean).join(' ') || '—'}
                </td>
                <td className="px-4 py-2 text-ink-muted">{u.email ?? '—'}</td>
                <td className="px-4 py-2 text-ink-muted text-xs">{scopeLabel(u, idx)}</td>
                <td className="px-4 py-2">
                  {u.enabled
                    ? <span className="text-xs px-2 py-0.5 rounded bg-emerald-50 text-emerald-700">Actif</span>
                    : <span className="text-xs px-2 py-0.5 rounded bg-slate-100 text-slate-500">Désactivé</span>}
                </td>
                <td className="px-4 py-2 text-ink-muted">{formatTimestamp(u.createdAt)}</td>
                <td className="px-4 py-2 text-right whitespace-nowrap">
                  <button
                    onClick={() => setModal({ kind: 'edit', userId: u.id })}
                    className="text-sigdep-700 hover:underline text-xs mr-3">
                    Éditer
                  </button>
                  <button
                    onClick={() => setModal({ kind: 'password', userId: u.id, username: u.username })}
                    className="text-sigdep-700 hover:underline text-xs mr-3">
                    Mot de passe
                  </button>
                  {u.enabled ? (
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
        <PasswordModal userId={modal.userId} username={modal.username}
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

function Field({ label, children }: Readonly<{ label: string; children: React.ReactNode }>) {
  return (
    <label className="block">
      <span className="block text-xs font-medium text-ink-muted mb-1">{label}</span>
      {children}
    </label>
  );
}

const inputClass =
  'w-full rounded-md border border-slate-300 px-3 py-2 text-sm focus:outline-none focus:border-sigdep-500 focus:ring-1 focus:ring-sigdep-500';

/**
 * Roles checklist + the cascading region/district/site select that pops up
 * once a scoped role is checked. Enforces "at most one scoped role" by
 * disabling the other two whenever one is active.
 */
function RoleAndScopePicker({
  roles, value, onChange,
  scopeRegion, scopeDistrict, scopeSite,
  onScopeChange,
}: Readonly<{
  roles: string[];
  value: string[];
  onChange: (next: string[]) => void;
  scopeRegion: number | null;
  scopeDistrict: number | null;
  scopeSite: number | null;
  onScopeChange: (region: number | null, district: number | null, site: number | null) => void;
}>) {
  const set = new Set(value);
  // Determine the active scoped role (if any).
  let activeScoped: 'region' | 'district' | 'site' | null = null;
  for (const r of value) {
    const s = SCOPED_ROLES[r];
    if (s) { activeScoped = s; break; }
  }

  const regions = useQuery({ queryKey: ['regions'], queryFn: () => fetchRegions() });
  const districts = useQuery({
    queryKey: ['districts', scopeRegion],
    queryFn: () => fetchDistricts(scopeRegion ?? undefined),
    enabled: scopeRegion != null,
  });
  const sites = useQuery({
    queryKey: ['sitesOf', scopeRegion, scopeDistrict],
    queryFn: () => fetchSitesOf(scopeRegion ?? undefined, scopeDistrict ?? undefined),
    enabled: scopeRegion != null || scopeDistrict != null,
  });

  function toggleRole(role: string) {
    const next = new Set(set);
    if (next.has(role)) {
      next.delete(role);
      // If we just removed the scoped role, clear the scope.
      if (SCOPED_ROLES[role]) onScopeChange(null, null, null);
    } else {
      // If activating a scoped role, drop any other scoped role first.
      if (SCOPED_ROLES[role]) {
        for (const other of Object.keys(SCOPED_ROLES)) {
          if (other !== role) next.delete(other);
        }
      }
      next.add(role);
    }
    onChange(Array.from(next).sort());
  }

  return (
    <>
      <div className="grid grid-cols-2 gap-1 max-h-44 overflow-auto border border-slate-200 rounded-md p-2">
        {roles.length === 0 && <span className="text-xs text-ink-muted col-span-2">Aucun rôle disponible</span>}
        {roles.map(r => {
          const checked = set.has(r);
          const scoped = SCOPED_ROLES[r];
          // Disable the other two scoped roles when one is already chosen.
          const disabled = scoped != null && activeScoped != null && activeScoped !== scoped && !checked;
          return (
            <label key={r}
                   className={`flex items-center gap-2 text-xs ${disabled ? 'opacity-40 cursor-not-allowed' : ''}`}>
              <input type="checkbox" checked={checked} disabled={disabled}
                     onChange={() => toggleRole(r)} />
              <span className="font-mono">{r}</span>
            </label>
          );
        })}
      </div>

      {activeScoped && (
        <div className="rounded-md border border-sigdep-200 bg-sigdep-50/40 p-3 space-y-2">
          <p className="text-xs font-medium text-sigdep-800">
            Zone d'intervention {activeScoped === 'region' ? '(région)' : activeScoped === 'district' ? '(district)' : '(site)'}
          </p>

          <Field label="Région">
            <select className={inputClass}
                    value={scopeRegion ?? ''}
                    onChange={e => {
                      const v = e.target.value ? Number(e.target.value) : null;
                      onScopeChange(v, null, null);
                    }}>
              <option value="">— Choisir —</option>
              {regions.data?.map(r => (
                <option key={r.id} value={r.id}>{r.name}</option>
              ))}
            </select>
          </Field>

          {(activeScoped === 'district' || activeScoped === 'site') && (
            <Field label="District">
              <select className={inputClass}
                      value={scopeDistrict ?? ''}
                      disabled={scopeRegion == null}
                      onChange={e => {
                        const v = e.target.value ? Number(e.target.value) : null;
                        onScopeChange(scopeRegion, v, null);
                      }}>
                <option value="">{scopeRegion == null ? 'Choisis d\'abord une région' : '— Choisir —'}</option>
                {districts.data?.map(d => (
                  <option key={d.id} value={d.id}>{d.name}</option>
                ))}
              </select>
            </Field>
          )}

          {activeScoped === 'site' && (
            <Field label="Site">
              <select className={inputClass}
                      value={scopeSite ?? ''}
                      disabled={scopeDistrict == null}
                      onChange={e => {
                        const v = e.target.value ? Number(e.target.value) : null;
                        onScopeChange(scopeRegion, scopeDistrict, v);
                      }}>
                <option value="">{scopeDistrict == null ? 'Choisis d\'abord un district' : '— Choisir —'}</option>
                {sites.data?.map(s => (
                  <option key={s.id} value={s.id}>{s.code} — {s.name}</option>
                ))}
              </select>
            </Field>
          )}
        </div>
      )}
    </>
  );
}

function scopeIsValid(roles: string[],
                      regionId: number | null,
                      districtId: number | null,
                      siteId: number | null): boolean {
  let activeScoped: 'region' | 'district' | 'site' | null = null;
  for (const r of roles) {
    const s = SCOPED_ROLES[r];
    if (s) { activeScoped = s; break; }
  }
  if (activeScoped === 'region')   return regionId != null;
  if (activeScoped === 'district') return regionId != null && districtId != null;
  if (activeScoped === 'site')     return regionId != null && districtId != null && siteId != null;
  return true;
}

function CreateModal({ onClose, onDone }: Readonly<{ onClose: () => void; onDone: () => void }>) {
  const [form, setForm] = useState<CreateUserRequest>({
    username: '', email: '', firstName: '', lastName: '',
    enabled: true, emailVerified: false,
    password: '', passwordTemporary: true, realmRoles: [],
    regionId: null, districtId: null, siteId: null,
  });
  const [confirmPassword, setConfirmPassword] = useState('');
  const roles = useQuery({ queryKey: ['user-roles'], queryFn: fetchUserRoles });
  const m = useMutation({ mutationFn: () => createUser(form), onSuccess: onDone });

  const passwordOk = !form.password || form.password === confirmPassword;
  const scopeOk = scopeIsValid(form.realmRoles ?? [],
      form.regionId ?? null, form.districtId ?? null, form.siteId ?? null);
  const canSubmit = !!form.username && passwordOk && scopeOk && !m.isPending;

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
      <Field label="Identifiant (username)">
        <input className={inputClass} value={form.username}
               onChange={e => setForm({ ...form, username: e.target.value })} />
      </Field>
      <div className="grid grid-cols-2 gap-3">
        <Field label="Prénom">
          <input className={inputClass} value={form.firstName ?? ''}
                 onChange={e => setForm({ ...form, firstName: e.target.value })} />
        </Field>
        <Field label="Nom">
          <input className={inputClass} value={form.lastName ?? ''}
                 onChange={e => setForm({ ...form, lastName: e.target.value })} />
        </Field>
      </div>
      <Field label="Email">
        <input className={inputClass} type="email" value={form.email ?? ''}
               onChange={e => setForm({ ...form, email: e.target.value })} />
      </Field>
      <Field label="Mot de passe initial">
        <input className={inputClass} type="text" value={form.password ?? ''}
               onChange={e => setForm({ ...form, password: e.target.value })} />
      </Field>
      <Field label="Confirmer le mot de passe">
        <input className={inputClass} type="text" value={confirmPassword}
               onChange={e => setConfirmPassword(e.target.value)} />
      </Field>
      {form.password && !passwordOk && (
        <p className="text-rose-600 text-xs">Les mots de passe ne correspondent pas.</p>
      )}
      <label className="flex items-center gap-2 text-xs">
        <input type="checkbox" checked={form.passwordTemporary ?? true}
               onChange={e => setForm({ ...form, passwordTemporary: e.target.checked })} />
        Mot de passe temporaire (l'utilisateur devra le changer)
      </label>
      <Field label="Rôles realm">
        <RoleAndScopePicker
          roles={roles.data ?? []}
          value={form.realmRoles ?? []}
          onChange={next => setForm({ ...form, realmRoles: next })}
          scopeRegion={form.regionId ?? null}
          scopeDistrict={form.districtId ?? null}
          scopeSite={form.siteId ?? null}
          onScopeChange={(r, d, s) => setForm({ ...form, regionId: r, districtId: d, siteId: s })}
        />
      </Field>
      {!scopeOk && (
        <p className="text-rose-600 text-xs">Sélectionne la zone correspondant au rôle géographique.</p>
      )}
      {m.isError && <p className="text-rose-600 text-xs">{(m.error as Error).message}</p>}
    </ModalShell>
  );
}

function EditModal({ userId, onClose, onDone }:
    Readonly<{ userId: string; onClose: () => void; onDone: () => void }>) {
  const detail = useQuery({ queryKey: ['user', userId], queryFn: () => fetchUser(userId) });
  const roles = useQuery({ queryKey: ['user-roles'], queryFn: fetchUserRoles });
  const [form, setForm] = useState<UpdateUserRequest | null>(null);
  const m = useMutation({
    mutationFn: () => updateUser(userId, form ?? {}),
    onSuccess: onDone,
  });

  // Initialise form once detail loads.
  if (detail.data && form === null) {
    const d = detail.data as UserDetail;
    setForm({
      email: d.email ?? '',
      firstName: d.firstName ?? '',
      lastName: d.lastName ?? '',
      enabled: d.enabled,
      emailVerified: d.emailVerified,
      realmRoles: d.realmRoles ?? [],
      regionId: d.regionId ?? null,
      districtId: d.districtId ?? null,
      siteId: d.siteId ?? null,
    });
  }

  const scopeOk = form == null || scopeIsValid(
      form.realmRoles ?? [],
      form.regionId ?? null, form.districtId ?? null, form.siteId ?? null);
  const canSubmit = form != null && scopeOk && !m.isPending;

  return (
    <ModalShell title={`Édition — ${detail.data?.username ?? '…'}`} onClose={onClose}
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
          <div className="grid grid-cols-2 gap-3">
            <Field label="Prénom">
              <input className={inputClass} value={form.firstName ?? ''}
                     onChange={e => setForm({ ...form, firstName: e.target.value })} />
            </Field>
            <Field label="Nom">
              <input className={inputClass} value={form.lastName ?? ''}
                     onChange={e => setForm({ ...form, lastName: e.target.value })} />
            </Field>
          </div>
          <Field label="Email">
            <input className={inputClass} type="email" value={form.email ?? ''}
                   onChange={e => setForm({ ...form, email: e.target.value })} />
          </Field>
          <label className="flex items-center gap-2 text-xs">
            <input type="checkbox" checked={form.emailVerified ?? false}
                   onChange={e => setForm({ ...form, emailVerified: e.target.checked })} />
            Email vérifié
          </label>
          <label className="flex items-center gap-2 text-xs">
            <input type="checkbox" checked={form.enabled ?? true}
                   onChange={e => setForm({ ...form, enabled: e.target.checked })} />
            Compte actif
          </label>
          <Field label="Rôles realm">
            <RoleAndScopePicker
              roles={roles.data ?? []}
              value={form.realmRoles ?? []}
              onChange={next => setForm({ ...form, realmRoles: next })}
              scopeRegion={form.regionId ?? null}
              scopeDistrict={form.districtId ?? null}
              scopeSite={form.siteId ?? null}
              onScopeChange={(r, d, s) => setForm({ ...form, regionId: r, districtId: d, siteId: s })}
            />
          </Field>
          {!scopeOk && (
            <p className="text-rose-600 text-xs">Sélectionne la zone correspondant au rôle géographique.</p>
          )}
        </>
      )}
      {m.isError && <p className="text-rose-600 text-xs">{(m.error as Error).message}</p>}
    </ModalShell>
  );
}

function PasswordModal({ userId, username, onClose }:
    Readonly<{ userId: string; username: string; onClose: () => void }>) {
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [temporary, setTemporary] = useState(true);
  const m = useMutation({
    mutationFn: () => resetUserPassword(userId, password, temporary),
    onSuccess: onClose,
  });
  const passwordOk = password && password === confirm;
  return (
    <ModalShell title={`Mot de passe — ${username}`} onClose={onClose}
      footer={<>
        <button onClick={onClose} className="px-3 py-1.5 text-sm border border-slate-300 rounded">Annuler</button>
        <button
          onClick={() => m.mutate()}
          disabled={!passwordOk || m.isPending}
          className="px-3 py-1.5 text-sm rounded bg-sigdep-600 text-white hover:bg-sigdep-700 disabled:opacity-50">
          {m.isPending ? 'Application…' : 'Réinitialiser'}
        </button>
      </>}>
      <Field label="Nouveau mot de passe">
        <input className={inputClass} type="text" value={password}
               onChange={e => setPassword(e.target.value)} />
      </Field>
      <Field label="Confirmer le mot de passe">
        <input className={inputClass} type="text" value={confirm}
               onChange={e => setConfirm(e.target.value)} />
      </Field>
      {password && !passwordOk && (
        <p className="text-rose-600 text-xs">Les mots de passe ne correspondent pas.</p>
      )}
      <label className="flex items-center gap-2 text-xs">
        <input type="checkbox" checked={temporary}
               onChange={e => setTemporary(e.target.checked)} />
        Temporaire (l'utilisateur devra le changer à la prochaine connexion)
      </label>
      {m.isError && <p className="text-rose-600 text-xs">{(m.error as Error).message}</p>}
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
        Confirmer la désactivation du compte <span className="font-mono">{user.username}</span> ?
      </p>
      <p className="text-xs text-ink-muted">
        Le compte ne pourra plus se connecter mais ses données restent en place.
        Tu peux réactiver le compte à tout moment depuis la liste.
      </p>
      {m.isError && <p className="text-rose-600 text-xs">{(m.error as Error).message}</p>}
    </ModalShell>
  );
}
