# Contributing

This document is short on purpose. Read it once, then trust the patterns
already in the codebase.

## Git workflow

- Trunk-based: small PRs against `master`, no long-lived feature
  branches.
- Branch names: `feat/<short-topic>`, `fix/<short-topic>`,
  `chore/<short-topic>`, `docs/<short-topic>`.
- Commit messages follow the existing
  [Conventional Commits](https://www.conventionalcommits.org/)-ish style
  you'll see in `git log`:
  - `feat(hub): scope-based authorization (region/district/site)`
  - `fix(hub): drop dev auth bypass, add CORS, soft-fail expired sessions`
  - `chore(infra): single-origin nginx reverse proxy`
- Keep commits focused. If a commit touches both an unrelated refactor
  and a bug fix, split them.

## Commit identity

Every commit must be authored under your real name and an email you
control. CI rejects commits with placeholder or bot identities on
release branches.

## Tooling and versions

- **JDK 17+** for the backend. Pin via `JAVA_HOME` in `.env` if your
  shell defaults to a newer JDK.
- **Maven 3.9+**.
- **Node 20+** for the frontend.
- **Docker** with Compose v2 for the infra.

## Backend (Spring Boot)

- Stay strict with the layering: controller → service → repository. No
  SQL in controllers, no `@Autowired` in entities.
- Database access is JDBC-template-first for read queries (close to the
  SQL, no JPA hidden N+1). JPA is fine for writes where the entity
  graph is shallow.
- Every controller method that returns business data goes through
  `AuthScope.effective(regionId, districtId, siteId)` before reaching
  the service layer. See `PatientController` for the canonical pattern.
- Avoid `@Cacheable` on scope-sensitive endpoints — caching multiplies
  the cache-key space by the number of users with distinct scopes.

## Frontend (React)

- All HTTP calls go through `console-web/src/api/client.ts`. Don't
  `fetch()` from a component.
- TanStack Query for server state, `useState` for local UI state. Don't
  reach for Redux/Zustand unless we have a documented reason.
- Icons come from `lucide-react`. Pick small, semantic names —
  `Stethoscope` for the clinic page, `ShieldCheck` for admin, etc.
- Status pills use the `<StatusBadge>` component with tones
  `ok / warning / danger / info / neutral`. Don't reinvent the colour
  vocabulary per-page.
- Tailwind palette: `sigdep` teal is primary for everything user-facing,
  `accent` indigo is reserved for admin pages and explicit "admin
  zone" cues.

## Tests

The test surface is intentionally light at this stage:

- Unit tests for non-trivial domain logic (`AuthScope`, `SortSpec`,
  `SyncBatchLogger.buildErrorSample`, …) live next to the class.
- No end-to-end tests yet. Smoke tests are done by hand against the
  full dev stack on `http://localhost:9000`.

If you're tempted to add Cypress / Playwright, talk to the team first —
we'd rather invest in a tighter dev environment than maintain flaky
browser tests against the auth flow.

## Documentation

If you change the runtime topology, the auth model, the deployment
shape, or any of the things people will run into during onboarding,
**update the doc in the same PR**. The relevant files:

- [README.md](README.md) — quickstart, ports table, first login.
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — modules, auth, data
  flow.
- [docs/OPERATIONS.md](docs/OPERATIONS.md) — SQL recipes, gestion des
  comptes, troubleshooting.
- [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) — production checklist.
- [docs/user-guide/](docs/user-guide/README.md) — guides déployeur /
  utilisateur (installation hub & agent, gestion des comptes).

Code comments: don't paraphrase the code. Write a comment when the
reader is going to ask "why?", not "what?". The few comments we keep
explain non-obvious constraints (the JWT secret length requirement,
nginx's `proxy_set_header` inheritance, the order of `?` binds in a SQL
fragment, …).

## Reviewing

PRs need one review from a maintainer. Reviewers focus on:

- Does the change keep the security model intact (`AuthScope` applied,
  no UI-only checks for scope, no `@PreAuthorize` regressions)?
- Does the data path still respect tenancy (no cross-site reads in a
  scoped query)?
- Is the SQL safe (no string concatenation of user input, no `?` order
  drift)?
- Does the UI tone match the page role (teal for ops, indigo for admin)?
- Is the doc still accurate?
