import { useEffect, useState } from "react";
import { useAuth } from "react-oidc-context";
import { NavLink, Outlet } from "react-router-dom";
import {
  Activity, BarChart3, BookOpenCheck, Building2, ChevronLeft, ChevronRight,
  LayoutDashboard, LogOut, Menu, Microscope, Pill, RefreshCcw,
  ShieldCheck, Stethoscope, Syringe, Users, type LucideIcon,
} from "lucide-react";
import { getAccessToken } from "../auth";
import { GlobalLoader } from "./GlobalLoader";

type NavItem = {
  to: string;
  label: string;
  icon: LucideIcon;
  requireRoles?: string[];
};
type NavGroup = { label?: string; items: NavItem[]; tone?: "admin" };

/**
 * `requireRoles` filters the link by the realm roles in the JWT. Items
 * without it are shown to everyone with a session. Each item ships its
 * own icon for the sidebar.
 */
const NAV: NavGroup[] = [
  {
    items: [
      { to: "/app/vue-ensemble", label: "Vue d’ensemble",     icon: LayoutDashboard },
      { to: "/app/pepfar",       label: "Indicateurs PEPFAR", icon: BarChart3 },
    ],
  },
  {
    label: "Explorer",
    items: [
      { to: "/app/patients", label: "Patients", icon: Users },
      { to: "/app/sites",    label: "Sites",    icon: Building2 },
    ],
  },
  {
    label: "Données",
    items: [
      { to: "/app/clinique",  label: "Suivi clinique",   icon: Stethoscope },
      { to: "/app/pharmacie", label: "Pharmacie / ARV",  icon: Pill },
      { to: "/app/depistage", label: "Dépistage",        icon: Activity },
      { to: "/app/ptme",      label: "PTME",             icon: Syringe },
      { to: "/app/tpt",       label: "TPT",              icon: BookOpenCheck },
      { to: "/app/biologie",  label: "Biologie",         icon: Microscope },
    ],
  },
  {
    label: "Admin",
    tone: "admin",
    items: [
      { to: "/app/sync", label: "Synchronisation", icon: RefreshCcw,
        requireRoles: ["SUPER_ADMIN", "IT_ADMIN", "NATIONAL_VIEWER", "AUDITOR"] },
      { to: "/app/users", label: "Utilisateurs", icon: ShieldCheck,
        requireRoles: ["SUPER_ADMIN", "IT_ADMIN"] },
    ],
  },
];

/**
 * Realm roles live in the *access* token's `realm_access.roles` claim — the
 * ID token (= `user.profile`) doesn't carry them by default in Keycloak. We
 * therefore decode the access token here rather than read the OIDC profile.
 */
function realmRolesFromAccessToken(): Set<string> {
  const token = getAccessToken();
  if (!token) return new Set();
  const parts = token.split(".");
  if (parts.length < 2) return new Set();
  try {
    const b64 = parts[1].replace(/-/g, "+").replace(/_/g, "/");
    const pad = b64.length % 4 === 0 ? b64 : b64 + "=".repeat(4 - (b64.length % 4));
    const payload = JSON.parse(atob(pad)) as { realm_access?: { roles?: unknown } };
    const roles = payload.realm_access?.roles;
    if (!Array.isArray(roles)) return new Set();
    return new Set(roles.filter((r): r is string => typeof r === "string"));
  } catch {
    return new Set();
  }
}

function initials(name: string | undefined): string {
  if (!name) return "·";
  const parts = name.trim().split(/\s+/);
  return (parts[0]?.[0] ?? "") + (parts[parts.length - 1]?.[0] ?? "");
}

const COLLAPSE_KEY = "sigdep:sidebarCollapsed";

export function AppLayout() {
  const auth = useAuth();
  const profile = auth.user?.profile;
  const displayName =
    profile?.name ??
    [profile?.given_name, profile?.family_name].filter(Boolean).join(" ") ??
    profile?.preferred_username ??
    "—";
  const roles = realmRolesFromAccessToken();

  // Persist the collapse preference; auto-collapse on narrow viewports.
  const [collapsed, setCollapsed] = useState<boolean>(() => {
    if (typeof window === "undefined") return false;
    const saved = window.localStorage.getItem(COLLAPSE_KEY);
    if (saved !== null) return saved === "1";
    return window.matchMedia("(max-width: 900px)").matches;
  });
  useEffect(() => {
    window.localStorage.setItem(COLLAPSE_KEY, collapsed ? "1" : "0");
  }, [collapsed]);

  return (
    <div className="min-h-screen flex bg-slate-50">
      <GlobalLoader />
      {/* Sidebar */}
      <aside
        className={`shrink-0 border-r border-slate-200 bg-white flex flex-col
                    transition-[width] duration-200 ease-out
                    ${collapsed ? "w-16" : "w-60"}`}
      >
        <div className={`h-14 border-b border-slate-200 flex items-center gap-2
                         ${collapsed ? "justify-center px-2" : "px-4"}`}>
          <img src="/logos/sigdep3_crop.png" alt="" className="h-8 w-8" />
          {!collapsed && (
            <img
              src="/logos/sigdep_logo_text_small.png"
              alt="SIGDEP-3"
              className="h-7 w-auto"
            />
          )}
        </div>

        <nav className="flex-1 overflow-y-auto py-3 text-sm">
          {NAV.map((group, gi) => {
            const visible = group.items.filter(
              (it) => !it.requireRoles || it.requireRoles.some((r) => roles.has(r)),
            );
            if (visible.length === 0) return null;
            return (
              <div key={gi} className={gi > 0 ? "mt-4" : ""}>
                {group.label && !collapsed && (
                  <p className={`px-4 mb-1 text-[11px] font-semibold uppercase tracking-wider
                                ${group.tone === "admin" ? "text-accent-700" : "text-ink-subtle"}`}>
                    {group.label}
                  </p>
                )}
                {group.label && collapsed && (
                  <div className="mx-3 my-2 border-t border-slate-200" />
                )}
                <div className={collapsed ? "px-2" : "px-2"}>
                  {visible.map((item) => (
                    <SidebarLink key={item.to} item={item} collapsed={collapsed} tone={group.tone} />
                  ))}
                </div>
              </div>
            );
          })}
        </nav>

        {/* Collapse toggle pinned at the bottom of the sidebar. */}
        <button
          onClick={() => setCollapsed((c) => !c)}
          aria-label={collapsed ? "Déplier la barre latérale" : "Replier la barre latérale"}
          className="border-t border-slate-200 h-10 flex items-center justify-center
                     text-ink-muted hover:bg-slate-50 hover:text-ink transition"
        >
          {collapsed ? <ChevronRight className="h-4 w-4" /> : <ChevronLeft className="h-4 w-4" />}
        </button>
      </aside>

      {/* Main column */}
      <div className="flex-1 flex flex-col min-w-0">
        <header className="h-14 border-b border-slate-200 bg-white flex items-center justify-between px-6 gap-3">
          <button
            onClick={() => setCollapsed((c) => !c)}
            aria-label="Basculer la barre latérale"
            className="lg:hidden p-1.5 rounded-md hover:bg-slate-100 text-ink-muted"
          >
            <Menu className="h-5 w-5" />
          </button>
          <div className="flex-1" />
          <div className="flex items-center gap-3">
            <span className="text-sm text-ink-muted hidden sm:inline">{displayName}</span>
            <div className="h-8 w-8 rounded-full bg-sigdep-100 text-sigdep-700 flex items-center
                            justify-center text-xs font-semibold uppercase">
              {initials(displayName)}
            </div>
            <button
              onClick={() => auth.signoutRedirect()}
              title="Déconnexion"
              className="p-1.5 rounded-md hover:bg-slate-100 text-ink-muted hover:text-rose-600 transition"
            >
              <LogOut className="h-4 w-4" />
            </button>
          </div>
        </header>
        <main className="flex-1 overflow-y-auto">
          <Outlet />
        </main>
      </div>
    </div>
  );
}

function SidebarLink({ item, collapsed, tone }:
    Readonly<{ item: NavItem; collapsed: boolean; tone?: "admin" }>) {
  const Icon = item.icon;
  const accent = tone === "admin";
  return (
    <NavLink
      to={item.to}
      end={item.to === "/app/vue-ensemble"}
      title={collapsed ? item.label : undefined}
      className={({ isActive }) => {
        const base = "flex items-center gap-3 rounded-md mb-0.5 transition relative";
        const padding = collapsed ? "px-2 py-2 justify-center" : "px-3 py-2";
        if (isActive) {
          return `${base} ${padding} font-medium
                  ${accent ? "bg-accent-50 text-accent-700" : "bg-sigdep-50 text-sigdep-700"}`;
        }
        return `${base} ${padding} text-ink hover:bg-slate-100`;
      }}
    >
      {({ isActive }) => (
        <>
          {/* Active-state vertical bar — clearer signal than just the bg tint. */}
          {isActive && (
            <span
              aria-hidden="true"
              className={`absolute left-0 top-1 bottom-1 w-1 rounded-r
                          ${accent ? "bg-accent-500" : "bg-sigdep-500"}`}
            />
          )}
          <Icon className="h-4 w-4 shrink-0" />
          {!collapsed && <span className="truncate">{item.label}</span>}
        </>
      )}
    </NavLink>
  );
}
