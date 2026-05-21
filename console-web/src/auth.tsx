import { ReactNode } from 'react';
import { AuthProvider, useAuth } from 'react-oidc-context';
import { Navigate, useLocation } from 'react-router-dom';
import { WebStorageStateStore } from 'oidc-client-ts';

// Same-origin authority: the nginx reverse-proxy on :9000 forwards
// /realms/* to Keycloak so the browser only ever talks to a single
// origin. Set VITE_OIDC_AUTHORITY explicitly if you want to bypass the
// proxy (e.g. point at a staging Keycloak directly).
const AUTHORITY = import.meta.env.VITE_OIDC_AUTHORITY
  ?? `${window.location.origin}/realms/sigdep`;
const CLIENT_ID = import.meta.env.VITE_OIDC_CLIENT_ID ?? 'sigdep-console';
const REDIRECT_URI = window.location.origin + '/app';
const POST_LOGOUT_URI = window.location.origin + '/';

const oidcConfig = {
  authority: AUTHORITY,
  client_id: CLIENT_ID,
  redirect_uri: REDIRECT_URI,
  post_logout_redirect_uri: POST_LOGOUT_URI,
  response_type: 'code',
  scope: 'openid profile email',
  userStore: new WebStorageStateStore({ store: window.localStorage }),
  automaticSilentRenew: true,
  // Force the Keycloak login page in French regardless of the browser's
  // Accept-Language. Keycloak honours ui_locales (OIDC standard) before
  // falling back to the realm's defaultLocale ; users can still switch
  // via the language dropdown on the login page.
  extraQueryParams: { ui_locales: 'fr' },
  onSigninCallback: () => {
    // Strip ?code=&state= from the URL after the callback completes.
    window.history.replaceState({}, document.title, window.location.pathname);
  },
};

export function SigdepAuthProvider({ children }: { children: ReactNode }) {
  return <AuthProvider {...oidcConfig}>{children}</AuthProvider>;
}

export function RequireAuth({ children }: { children: ReactNode }) {
  const auth = useAuth();
  const location = useLocation();

  if (auth.isLoading) {
    return (
      <div className="min-h-screen flex items-center justify-center text-ink-muted">
        Chargement de la session…
      </div>
    );
  }

  // Any auth error (network blip, OIDC discovery 404, refresh-token gone) on
  // a protected route — bounce back to the landing. Showing a red error
  // message there would just trap the user; the landing offers a clear way
  // to sign in again.
  if (auth.error) {
    return <Navigate to="/" replace />;
  }

  if (!auth.isAuthenticated) {
    auth.signinRedirect({ state: { from: location.pathname } });
    return null;
  }

  return <>{children}</>;
}

export function getAccessToken(): string | undefined {
  // react-oidc-context stores the user under this key by default.
  const key = `oidc.user:${AUTHORITY}:${CLIENT_ID}`;
  const raw = window.localStorage.getItem(key);
  if (!raw) return undefined;
  try {
    return JSON.parse(raw).access_token;
  } catch {
    return undefined;
  }
}
