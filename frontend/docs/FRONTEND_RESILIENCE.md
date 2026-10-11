# Frontend UX and resilience — 2026-09-23

## Architecture

- `api/client.ts`: cookie/CSRF transport, cancellable 30-second timeout covering headers and body, schema validation. No response-body logging.
- `api/errorView.ts`: safe Korean error messages shared by mutation notifications and recoverable query screens. Does not expose server internals.
- `api/analysisRequest.ts`: one in-memory request identity per repository/action, shared in-flight promise, UUID retained only after unknown network/5xx outcome. No automatic mutation retries.
- `lib/useDraftAutosave.ts`: server-only, one PATCH at a time, coalesces latest input, explicit retry after failure. `flush(): Promise<boolean>` must succeed before navigation.
- `lib/useUnsavedChanges.ts` + `components/SaveStatus.tsx`: writing routes share pending/saving/saved/error and browser/SPA navigation protection. Unsaved text remains only in React memory until acknowledged by the server.
- `components/ui/QueryFailure.tsx`: initial fetch failures do not become empty results. Editors retain cached content during background refetch failure.
- `features/auth/useLogin.ts`: isolates demo login from the presentation. Demo CTA explicitly says 샘플로 체험하기.
- `mock/scenarios.ts`: repeatable transport faults and contract-valid boundary fixtures, independently testable from the router.

## Save behavior

New manual cards first collect title, optional period and repository. The first explicit save creates a DRAFT and then PATCHes that returned ID. Afterward only STAR text autosaves with the existing 2500ms policy. Metadata locks with an explanation because the current PATCH contract has no metadata fields. A metadata-update API is a backend follow-up, not an invented frontend endpoint.

Every edit save merges acknowledged fields/version into the exact card cache. This prevents browser Back and re-edit from restoring stale text. Writes are serialized. A failed save blocks close/confirm, preserves input, and offers retry. No localStorage draft fallback. Browser close/reload can warn but cannot guarantee saving during network loss; users must remain on the page until saved.

Interview text is submitted verbatim on the existing answer endpoint. Unsubmitted answers prompt before leaving; there is no separate server API for unsubmitted interview drafts. Do not call answer submission an autosave of each keystroke.

## Job requests and PR #50

- Same key + same repository: return existing Job.
- Different keys + same active repository: return existing Job.
- Same key + different repository: 409 + IDEMPOTENCY_KEY_MISMATCH (confirmed in develop, 2026-09-26). No automatic request with a new key. Other INVALID_REQUEST errors use the generic input-error message.
- Unknown response: explicit retry keeps UUID. A 409 does not generate another request or silently rotate a key.
- Job lookup 404, 5xx, timeout, or contract failure stays on a recovery screen; none is treated as successful analysis.
- Active list uses the earliest server interval while work exists and a named idle discovery interval to find jobs from other tabs. No browser-persisted job registry.
- retryable must be true before offering analysis re-execution; read retry and expensive job creation stay separate.

## Deterministic demo scenarios

Only mock mode reads `demoScenario`. It stores only the selected QA mode in sessionStorage. Real API mode ignores it. Select via URL, for example `/cards?demoScenario=sparse`. The selection remains in the tab during SPA navigation. Return to `/login?demoScenario=normal` to reset the mode; a fresh tab has independent state.

| Value | Behavior | Useful route |
| --- | --- | --- |
| normal | 160ms normal responses | /login |
| slow | 3500ms responses, abortable | /cards |
| empty | Empty repository/card arrays | /cards |
| sparse | Long titles/repo names, null avatar/language, valid schemas | /cards |
| read-error | Repeated 503 reads except identity/jobs | /cards |
| save-error | First draft PATCH fails before writing; explicit retry succeeds | /cards/card_01?mode=edit |
| lost-response | First analyze request accepted/persisted, then transport rejects; explicit same-key retry returns same Job | /repos?select=r_auth&disclose=1 |
| conflict | Analyze returns 409 dedicated code, creates no Job | /repos?select=r_auth&disclose=1 |

Existing fixtures cover `r_fail` collection failure, `r_ratelimit` partial success/cooldown, `r_board` no candidates and PR-less `r_algo`. These scenarios are not random and do not change production backend policies.

Demo database persistence remains a simulator only: it is tab-local sessionStorage and is not evidence of cross-device server persistence. Real mode uses server draft/job APIs; actual Spring+AI integration remains a separate verification requirement.

## UX

- Existing mobile interview-first layout retained; directional instructions replaced with location-independent copy.
- STAR labels enlarged to 12px; secondary colors use readable semantic tokens; mobile inputs 16px and primary controls at least 44px.
- Returning-user home intro compressed so ongoing work is closer. Extra toolbar borders/shadows removed.
- Modal traps Tab/Shift+Tab, restores trigger focus and has unique title IDs. Rerenders do not reset typing focus.
- Tablet icon navigation has accessible names and tooltips.

## Verification commands

From frontend: `npm ci`, `npm test`, `npm run typecheck`, `npm run build`, `npm run e2e`, `npm run e2e:static`.

Focused regressions: `save-safety.test.tsx`, `save-navigation.test.tsx`, `analysisRequest.test.ts`, `scenarios.test.ts`, `client.test.ts`, `e2e/resilience.spec.ts`.

## Remaining integration boundaries

Verification on 2026-09-23: typecheck and production build passed; 53 unit tests passed; 31 development-server E2E tests passed before final review fixes and the complete 31-test production-static suite passed after final fixes. Independent save and whole-branch reviews approved after fixing new-card/edit cache loss and retrying the wrong interview field. 390px mobile and 1440px desktop screenshots inspected; interview input begins at y=612px in an 844px viewport.

Manual draft creation has no agreed Idempotency-Key behavior; lost create responses cannot yet be safely deduplicated by the frontend. Metadata update API, cross-device editing conflict policy, real auth/API deployment and account deletion policy remain server/team work. No backend/AI code, database migration or secrets are changed here.
