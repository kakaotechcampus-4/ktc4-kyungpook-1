# Gitory Quiet UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace repetitive nested cards with a spacious grouped-list UI, add a reliable demo profile avatar, and align all STAR status indicators.

**Architecture:** Keep API schemas and route behavior unchanged. Introduce a reusable `UserAvatar` component and enrich the existing `StarDots` component, then give home/card collections an explicit `experience-list` boundary. Load the visual refinement after existing styles so selection, danger, and responsive states remain intact.

**Tech Stack:** React 19, TypeScript, CSS, Vitest, Testing Library, Playwright, Vite

## Global Constraints

- Preserve existing routes, API contracts, demo login, state transitions, and save/export behavior.
- Use section spacing of 32px, grouped spacing of 20–24px, and metadata spacing of 8–12px.
- Keep borders for inputs, selection, danger, and modal boundaries; remove repeated decorative borders and shadows.
- Support 320, 390, 768, 1024, 1440, and 1920px without horizontal overflow.
- Never commit `.env`, API keys, OAuth tokens, `.vercel`, or generated environment files.
- Update both open PRs and redeploy the verified production artifact.

---

### Task 1: Shared profile avatar

**Files:**
- Create: `public/demo-avatar.svg`
- Create: `src/components/ui/UserAvatar.tsx`
- Create: `src/test/UserAvatar.test.tsx`
- Modify: `src/components/layout/AppShell.tsx`
- Modify: `src/features/settings/MyPage.tsx`
- Modify: `src/features/settings/GithubSettingsPage.tsx`
- Modify: `src/mock/fixtures.ts`

**Interfaces:**
- Produces: `UserAvatar({ src, login, size?: 28 | 36 | 48 | 64, className? })` with image-error fallback.
- Consumes: `me.avatarUrl` and `me.login` from existing queries.

- [ ] Write a test that renders `/demo-avatar.svg`, then fires an image error and expects an initial fallback with the same accessible name.
- [ ] Run `npm test -- src/test/UserAvatar.test.tsx` and confirm it fails because `UserAvatar` does not exist.
- [ ] Add the local SVG asset and implement `UserAvatar` with internal failed-image state, a circular image, and a text fallback.
- [ ] Replace the three duplicated avatar spans and set the demo fixture `avatarUrl` to `/demo-avatar.svg`.
- [ ] Run the avatar test and typecheck; expect both to pass.

### Task 2: Aligned STAR progress

**Files:**
- Modify: `src/components/ui/index.tsx`
- Modify: `src/styles/design2.css`
- Create: `src/test/StarDots.test.tsx`

**Interfaces:**
- Produces: `StarDots({ filled, low?, showLabels? })`, retaining the existing `aria-label` contract.
- Uses fixed field order `S/T/A/R` and labels `상황/과제/행동/결과`.

- [ ] Write tests asserting the four letters and labels keep S/T/A/R order and state classes for filled, low-confidence, and empty fields.
- [ ] Run the STAR test and confirm it fails because `showLabels` and field labels are absent.
- [ ] Add semantic label spans and fixed four-column markup without changing card data.
- [ ] Replace tiny square styling with an equal-width rail; use background, border, and text only for state differences.
- [ ] Run STAR tests, existing label tests, and typecheck.

### Task 3: Spacious grouped experience lists

**Files:**
- Modify: `src/features/home/HomePage.tsx`
- Modify: `src/features/cards/CardsListPage.tsx`
- Modify: `src/styles/design2.css`
- Modify: `src/styles/responsive.css`
- Modify: `e2e/responsive.spec.ts`

**Interfaces:**
- Produces: `.experience-list` and `.experience-item` boundaries used by both home and card-list routes.
- Consumes: existing `CardGridItem` and filter behavior.

- [ ] Add E2E assertions that each page has one grouped list, experience rows have no box shadow, and STAR columns share equal widths.
- [ ] Run the focused responsive E2E and confirm failure because grouped-list classes are absent.
- [ ] Add list classes to both routes and render labeled STAR progress in each experience row.
- [ ] Apply 32px section gaps, 20–24px row padding, thin dividers, subtle row hover/focus, and a two-column desktop/one-column mobile layout.
- [ ] Convert status pills in the card-list filter to underline-style text tabs while retaining accessible pressed state.
- [ ] Run responsive and existing core-flow E2E tests.

### Task 4: Remove redundant nested boxes

**Files:**
- Modify: `src/styles/design2.css`
- Modify: `src/styles/responsive.css`
- Modify: `docs/TROUBLESHOOTING.md`
- Modify: `README.md`

**Interfaces:**
- Produces: consistent `.card--paper`, `.repo-ctx`, `.stage`, `.kv`, `.cand`, and landing demo-card descendant treatments.

- [ ] Add browser assertions for retained selection/danger borders and reduced decorative shadows on repository, candidate, settings, and landing screens.
- [ ] Remove nested decorative shadows and borders only where the parent surface already supplies grouping.
- [ ] Preserve input, selected, caution, failure, dialog, and sticky-footer boundaries.
- [ ] Document the one-surface-per-section rule, spacing scale, avatar fallback, and STAR alignment in README and troubleshooting docs.
- [ ] Run `npm test`, `npm run typecheck`, `npm run build`, `npm run e2e`, and `npm run e2e:static`.
- [ ] Inspect 390px and 1440px home, cards, settings, and card detail screenshots.

### Task 5: Synchronize, review, and deploy

**Files:**
- Mirror the verified root frontend files into `kakaotechcampus-4/ktc4-kyungpook-1/frontend/`.
- Mirror product docs into the team repository `docs/`.

**Interfaces:**
- Updates: personal PR #1 and team PR #6.
- Deploys: Vercel project `gitory-prototypes` production alias.

- [ ] Commit and push the personal branch without `.env` or generated Vercel files.
- [ ] Apply the same reviewed source, tests, asset, and docs to the team worktree and run typecheck plus static E2E there.
- [ ] Commit and push the team branch, then verify both PRs are open and mergeable.
- [ ] Build with pinned Vercel CLI and deploy the prebuilt production artifact.
- [ ] Inspect the production deployment and verify the live desktop and mobile UI in a browser.

## Self-review
The plan covers the avatar success/failure paths, STAR order and accessibility, grouped lists, spacing, preserved state boundaries, responsive verification, both repositories, and production deployment. All produced components and classes have explicit names and consumers, and no API or security boundary is left ambiguous.
