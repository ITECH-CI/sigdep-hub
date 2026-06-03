import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  ReactNode,
} from 'react';
import { Navigate, useLocation } from 'react-router-dom';

/**
 * Authentification v2.0 — Spring Security pur (remplace Keycloak/OIDC).
 *
 * Le login échange email+mot de passe contre un access JWT (HS256, TTL court)
 * et un refresh token opaque (TTL long), tous deux stockés dans le
 * localStorage. L'access token est décodé côté client pour l'affichage
 * (nom, rôle, portée géo) ; sa validité réelle est vérifiée par le backend
 * à chaque requête.
 */

const ACCESS_KEY = 'sigdep.access_token';
const REFRESH_KEY = 'sigdep.refresh_token';

export type AuthUser = {
  id: number | null;
  email: string;
  displayName: string;
  role: string;
  userLevel: string;
  regionId: number | null;
  districtId: number | null;
  siteId: number | null;
  /** Mot de passe temporaire : le front force le changement avant tout accès. */
  mustChangePassword: boolean;
};

type JwtClaims = {
  sub?: string;
  uid?: number;
  name?: string;
  role?: string;
  level?: string;
  regionId?: number;
  districtId?: number;
  siteId?: number;
  mustChangePassword?: boolean;
  exp?: number;
};

// --- Token storage helpers (also used by the API client) -------------------

export function getAccessToken(): string | undefined {
  return window.localStorage.getItem(ACCESS_KEY) ?? undefined;
}

export function getRefreshToken(): string | undefined {
  return window.localStorage.getItem(REFRESH_KEY) ?? undefined;
}

export function storeTokens(accessToken: string, refreshToken: string): void {
  window.localStorage.setItem(ACCESS_KEY, accessToken);
  window.localStorage.setItem(REFRESH_KEY, refreshToken);
}

export function clearTokens(): void {
  window.localStorage.removeItem(ACCESS_KEY);
  window.localStorage.removeItem(REFRESH_KEY);
}

function decodeClaims(token: string | undefined): JwtClaims | null {
  if (!token) return null;
  const parts = token.split('.');
  if (parts.length < 2) return null;
  try {
    const b64 = parts[1].replace(/-/g, '+').replace(/_/g, '/');
    const pad = b64.length % 4 === 0 ? b64 : b64 + '='.repeat(4 - (b64.length % 4));
    return JSON.parse(atob(pad)) as JwtClaims;
  } catch {
    return null;
  }
}

function userFromClaims(claims: JwtClaims | null): AuthUser | null {
  if (!claims || !claims.sub) return null;
  return {
    id: claims.uid ?? null,
    email: claims.sub,
    displayName: claims.name ?? claims.sub,
    role: claims.role ?? '',
    userLevel: claims.level ?? '',
    regionId: claims.regionId ?? null,
    districtId: claims.districtId ?? null,
    siteId: claims.siteId ?? null,
    mustChangePassword: claims.mustChangePassword === true,
  };
}

/** True if the JWT is absent or past its `exp` (with a small clock-skew margin). */
function isExpired(token: string | undefined): boolean {
  const claims = decodeClaims(token);
  return !claims?.exp || claims.exp * 1000 <= Date.now() + 5_000;
}

// --- Context ---------------------------------------------------------------

type AuthContextValue = {
  user: AuthUser | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  login: (email: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
};

const AuthContext = createContext<AuthContextValue | null>(null);

export function SigdepAuthProvider({ children }: { children: ReactNode }) {
  // Initial state derived synchronously from a non-expired stored token, so a
  // page reload keeps the session without a flash of the login screen.
  const [user, setUser] = useState<AuthUser | null>(() => {
    const token = getAccessToken();
    return isExpired(token) ? null : userFromClaims(decodeClaims(token));
  });

  const login = useCallback(async (email: string, password: string) => {
    const r = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password }),
    });
    if (!r.ok) {
      throw new Error(r.status === 401 ? 'Identifiants invalides' : `Erreur ${r.status}`);
    }
    const tokens = (await r.json()) as { accessToken: string; refreshToken: string };
    storeTokens(tokens.accessToken, tokens.refreshToken);
    setUser(userFromClaims(decodeClaims(tokens.accessToken)));
  }, []);

  const logout = useCallback(async () => {
    const refreshToken = getRefreshToken();
    const accessToken = getAccessToken();
    // Best-effort révocation côté serveur ; on nettoie localement quoi qu'il arrive.
    try {
      await fetch('/api/auth/logout', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
        },
        body: JSON.stringify({ refreshToken }),
      });
    } catch {
      /* ignore */
    }
    clearTokens();
    setUser(null);
  }, []);

  // Si une requête API force un logout (refresh échoué), on réagit ici.
  useEffect(() => {
    const onForcedLogout = () => {
      clearTokens();
      setUser(null);
    };
    window.addEventListener('sigdep:logout', onForcedLogout);
    return () => window.removeEventListener('sigdep:logout', onForcedLogout);
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({
      user,
      isAuthenticated: user !== null,
      isLoading: false,
      login,
      logout,
    }),
    [user, login, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth doit être utilisé dans <SigdepAuthProvider>');
  return ctx;
}

export function RequireAuth({ children }: { children: ReactNode }) {
  const { isAuthenticated, user } = useAuth();
  const location = useLocation();

  if (!isAuthenticated) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }
  // Mot de passe temporaire : on bloque tout accès tant qu'il n'est pas changé.
  if (user?.mustChangePassword && location.pathname !== '/changer-mot-de-passe') {
    return <Navigate to="/changer-mot-de-passe" replace />;
  }
  return <>{children}</>;
}
