/**
 * 지표 이벤트 (스펙 §추가 지표 · api.logger).
 * 퍼널: 레포 선택 → 후보 제시 → 후보 확정 → 초안 생성 → 되묻기 → 카드 확정 → 공고 매칭.
 * 실패해도 화면에 영향 없어야 하므로 fire-and-forget. 개인정보는 넣지 않는다 (id 만).
 */
export type EventName =
  | 'repo_selected' | 'analysis_started' | 'candidates_shown' | 'candidate_excluded' | 'candidate_restored'
  | 'cluster_commit_excluded' | 'candidate_added_manually' | 'candidates_confirmed'
  | 'draft_generated' | 'interview_asked' | 'interview_answered' | 'card_edited' | 'card_masked'
  | 'card_confirmed' | 'card_reopened' | 'version_restored' | 'manual_card_created' | 'field_regenerated'
  | 'recall_answered' | 'github_disconnected' | 'card_exported'
  | 'match_opened' | 'match_card_opened' | 'match_source_opened'
  // 인적사항(개인정보) — props 는 개수·참거짓만. 학교·회사·점수 같은 값은 절대 싣지 않는다 (docs/2026-10-29-PROFILE.md)
  | 'profile_prompt_accepted' | 'profile_prompt_dismissed' | 'profile_saved'
  // 자소서(#517) — props 는 id·개수만. 직무·문항 글은 싣지 않는다
  | 'cover_letter_created' | 'cover_letter_cards_changed' | 'cover_letter_asks_started';

const BASE = import.meta.env.VITE_API_BASE ?? '/api';

export function track(name: EventName, props: Record<string, string | number | boolean | null> = {}) {
  const body = JSON.stringify({ name, props, at: new Date().toISOString(), path: location.pathname });
  try {
    if (navigator.sendBeacon) { navigator.sendBeacon(`${BASE}/events`, new Blob([body], { type: 'application/json' })); return; }
    void fetch(`${BASE}/events`, { method: 'POST', body, headers: { 'Content-Type': 'application/json' }, credentials: 'include', keepalive: true }).catch(() => {});
  } catch { /* 지표는 제품을 막지 않는다 */ }
}
