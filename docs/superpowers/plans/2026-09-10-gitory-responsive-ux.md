# Gitory Responsive UX Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every existing frontend route use the available desktop width and remain readable and operable from 320px mobile screens.

**Architecture:** Keep the feature and API layers unchanged. Add one final responsive stylesheet as the authoritative layout layer, make the application shell expose clear desktop and mobile navigation regions, and simplify the landing information hierarchy. Add Playwright layout assertions that exercise real rendered routes at breakpoint widths.

**Tech Stack:** React 19, TypeScript, React Router, CSS, Playwright, Vitest, Vite

## Global Constraints

- Preserve existing routes, demo login, API contracts, state transitions, saving, export, and error states.
- Support 320–767px mobile, 768–1199px tablet, and 1200px+ desktop layouts.
- Do not add or commit `.env`, API keys, OAuth tokens, or other secrets.
- Record design, responsive decisions, verification, and troubleshooting under `docs/`, then update README files.

---

### Task 1: Responsive regression contract

**Files:**
- Create: `frontend/e2e/responsive.spec.ts`

**Interfaces:**
- Consumes: `loginAsDemo(page)` from `frontend/e2e/helpers.ts`.
- Produces: viewport-driven tests that assert `document.documentElement.scrollWidth <= window.innerWidth`, visible named navigation, single-column mobile landing, and expanded desktop main content.

- [ ] Write Playwright tests for 320px landing, 390px authenticated routes, and 1440px/1920px desktop layouts.
- [ ] Run `npx playwright test e2e/responsive.spec.ts` and confirm failures reproduce the current overflow, hidden navigation labels, or constrained content.
- [ ] Commit the failing regression contract with `test(frontend): cover responsive layouts` after production implementation makes it pass.

### Task 2: Application shell and shared layout

**Files:**
- Modify: `frontend/src/components/layout/AppShell.tsx`
- Create: `frontend/src/styles/responsive.css`
- Modify: `frontend/src/main.tsx`

**Interfaces:**
- Consumes: existing `MENU`, router links, design tokens, and shared layout class names.
- Produces: `.mobile-brand`, `.mobile-nav`, and final breakpoint rules loaded after all legacy styles.

- [ ] Use the failing tests to add a compact mobile brand header and named bottom navigation while keeping the desktop sidebar.
- [ ] Add final cascade rules for fluid main width, safe area spacing, grids, toolbars, sticky footers, forms, tables, modals, and long content.
- [ ] Run the responsive test file until the shell and shared route checks pass.

### Task 3: Landing hierarchy

**Files:**
- Modify: `frontend/src/features/auth/LandingPage.tsx`
- Modify: `frontend/src/styles/responsive.css`

**Interfaces:**
- Consumes: existing consent query parameter and `PermissionGrid` content.
- Produces: `.landing__eyebrow`, `.landing__actions`, and accessible disclosure of permission details.

- [ ] Update the failing landing assertions to cover aligned containers, visible CTA, and compact mobile permission details.
- [ ] Simplify copy and move detailed read/skip lists into a native `details` disclosure while retaining the consent modal.
- [ ] Run responsive and core-flow E2E tests until both pass.

### Task 4: Full-route visual and functional verification

**Files:**
- Modify: `frontend/e2e/responsive.spec.ts`

**Interfaces:**
- Consumes: all application routes and seeded demo data.
- Produces: a route matrix checking overflow and key content at mobile, tablet, and desktop widths.

- [ ] Cover home, repositories, candidates, cards, interview, new card, and settings routes.
- [ ] Run `npm test`, `npm run typecheck`, `npm run build`, `npm run e2e`, and `npm run e2e:static`.
- [ ] Inspect representative 390px and 1440px screenshots and fix any remaining overlap or inaccessible actions.

### Task 5: Documentation and delivery

**Files:**
- Create: `docs/TROUBLESHOOTING.md`
- Modify: `README.md`
- Modify: `frontend/README.md`

**Interfaces:**
- Produces: documented breakpoint behavior, CSS cascade cause, commands, verification results, and known API-contract limitation.

- [ ] Document the layout architecture and the late-loaded CSS cascade regression.
- [ ] Update repository and frontend README files with responsive behavior and verification commands.
- [ ] Review `git diff` and `git status` for secrets and unrelated files.
- [ ] Commit source, tests, and documentation; push `codex/responsive-ux`; open a PR into `feature/frontend-v3` with the verified results.

## Self-review
Every spec requirement maps to a task. There are no placeholders, route/API behavior remains outside the change boundary, and each produced class or test helper is defined in the task that introduces it.
