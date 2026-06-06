/**
 * Pied de page discret affichant la version et le numéro de build de l'app.
 * Les valeurs sont inlinées au build par Vite (`define`, cf. vite.config.ts) :
 *   __APP_VERSION__   — le tag de release (ex. "2.1.2"), "dev" en local.
 *   __APP_COMMIT__    — le SHA court du commit, "local" en local.
 *   __APP_BUILD_DATE__— la date de build (ISO court), vide en local.
 */
const VERSION = __APP_VERSION__;
const COMMIT = __APP_COMMIT__;
const BUILD_DATE = __APP_BUILD_DATE__;

export function Footer({ className = '' }: Readonly<{ className?: string }>) {
  // Construit "v2.1.2 · a3c4c4c · 2026-06-06" en omettant les parties vides.
  const parts = [
    `v${VERSION}`,
    COMMIT && COMMIT !== 'local' ? COMMIT : null,
    BUILD_DATE || null,
  ].filter(Boolean);

  return (
    <footer
      className={`px-4 py-3 text-center text-xs text-slate-400 ${className}`}
    >
      <span>SIGDEP-3 — PNLS Côte d’Ivoire</span>
      <span className="mx-2 text-slate-300">•</span>
      <span title={`Version ${VERSION}${COMMIT ? ` · build ${COMMIT}` : ''}`}>
        {parts.join(' · ')}
      </span>
    </footer>
  );
}
