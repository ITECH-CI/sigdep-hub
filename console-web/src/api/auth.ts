/**
 * Appels aux endpoints publics de gestion du mot de passe (sans JWT) :
 * mot de passe oublié, validation de lien, réinitialisation / définition.
 * Le proxy Vite route /api/** vers console-api.
 */

/** Demande un lien de réinitialisation. Répond toujours OK (anti-énumération). */
export async function forgotPassword(email: string): Promise<void> {
  const r = await fetch('/api/auth/password/forgot', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email }),
  });
  if (!r.ok) throw new Error(`Erreur ${r.status}`);
}

/** Vérifie qu'un token de réinitialisation est encore valide. */
export async function validateResetToken(token: string): Promise<boolean> {
  const r = await fetch(`/api/auth/password/validate?token=${encodeURIComponent(token)}`);
  if (!r.ok) return false;
  const data = (await r.json()) as { valid: boolean };
  return data.valid;
}

/**
 * Applique le nouveau mot de passe via un token. Lève une erreur si le token
 * est invalide/expiré (410) ou si le mot de passe est refusé (400).
 */
export async function resetPassword(token: string, password: string): Promise<void> {
  const r = await fetch('/api/auth/password/reset', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ token, password }),
  });
  if (r.ok) return;
  if (r.status === 410) throw new Error('Ce lien est invalide ou expiré. Refaites une demande.');
  if (r.status === 400) throw new Error('Mot de passe refusé (8 caractères minimum).');
  throw new Error(`Erreur ${r.status}`);
}
