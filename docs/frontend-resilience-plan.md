# Frontend resilience implementation plan — 2026-09-23

Approved scope: finish mobile/writing UX, consistent accessible design, realistic deterministic mock scenarios, and API error handling including PR #50. Refactor shared behavior where reused; preserve API ownership and existing UI direction. No backend/AI implementation or secret commits. Base: develop e9c6404.

## Global constraints
- Server drafts only. Never persist user writing or Job IDs in browser storage.
- Failed saves must keep content and block successful-close/confirm navigation. Display pending/saving/saved/error truthfully.
- Serialize saves so older responses cannot overwrite later input. Explicit retry after failure; no expensive mutation auto-retry.
- Analysis action creates UUID v4. Unknown network outcome retains that action key; another repository uses a different key. 409 is never retried with a fresh key automatically.
- Existing INVALID_REQUEST + 409 and proposed IDEMPOTENCY_KEY_MISMATCH are supported, without claiming backend agreement on the new code.
- Runtime response intervals and states remain server-owned. Empty results and partial success are not network errors.
- Preserve quiet grouped lists; improve legibility, focus and mobile actions without unrelated rewrites.
- Mock faults opt-in, deterministic, scoped to demo only. Normal demonstration stays usable.
- Read current source, docs and tests before edits; named file staging only; do not modify secrets, backend or AI code.

## Task 1: save safety (delegated implementer)
Files: frontend/src/lib/useDraftAutosave.ts, new frontend/src/lib/useUnsavedChanges.ts if needed, frontend/src/features/cards/NewCardPage.tsx, CardModes.tsx, new SaveStatus component and scoped tests.
Test failures for concurrent saves, failed flush preventing close, first save uses returned card ID, no-op saves, unsaved browser/in-app navigation. Implement shared save state and navigation protection via React Router blocker plus beforeunload. Keep original text verbatim. Manual metadata: use existing API only; do not silently permit unsaved metadata changes after creation. Save result success boolean/exception must gate navigation. No localStorage drafts.

## Task 2: API resilience and fixtures (main agent)
Files: API errors/client/hooks, analysis UI, mock/browser/router and scenario module, contract tests.
Centralize error presentation and analysis request identity. Add timeout/abort-aware transport, reusable recoverable query UI where loaders otherwise stall. Add deterministic scenario configuration for empty/sparse/long content, slow requests, transient reads, failed saves, lost start response and conflicts. Use same contracts and avoid arbitrary malformed data except explicit contract-error scenario.

## Task 3: interaction polish (main after API implementation)
Files: Modal, tokens/responsive styles, auth boundary, interview/home/components and tests.
Focus trap/restoration, visible labels and keyboard focus, adequate mobile controls. Verify current interview order before changes. Clarify demo vs real login through app-level auth hook. Resolve empty/error states and repeated CTA hierarchy.

## Task 4: review and delivery
Run baseline npm ci + test/typecheck; focused regression tests per fix, full unit/build/dev/static E2E once final. Inspect desktop/mobile screenshots, keyboard navigation, negative scenarios. Independent review of saves and final changes; fix confirmed findings. Document API contract changes, QA scenarios and known backend limitations in docs, update READMEs and plan ledger. New team PR into develop following .github/pull_request_template.md; synchronize reviewed frontend into personal branch without overwriting unseen changes. Update personal PR. Build/deploy Vercel production from verified frontend; live smoke. Attach both PRs. Do not merge.

## Progress
- Latest develop inspected; isolated branch codex/frontend-resilience created.
