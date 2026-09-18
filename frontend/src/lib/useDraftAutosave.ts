import { useCallback, useEffect, useRef, useState } from 'react';
import type { DraftFields } from '@/api/schemas';
import { CONFIG } from './config';

/**
 * 입력이 멈추면 서버에 임시 저장한다. 브라우저에는 아무것도 남기지 않는다.
 *
 * 직접 작성 화면과 카드 수정 화면이 같은 규칙을 써야 해서 여기 한 곳에 둔다 —
 * 디바운스 시간이나 "안 바뀌었으면 안 보낸다" 같은 규칙이 두 군데서 갈라지면
 * 한쪽만 고쳐 놓고 나머지 한쪽에서 글이 날아가는 식으로 터진다.
 */
export function useDraftAutosave(
  fields: DraftFields,
  save: (f: DraftFields) => Promise<unknown>,
  opts: { enabled?: boolean } = {},
) {
  const enabled = opts.enabled ?? true;
  const [savedAt, setSavedAt] = useState<number | null>(null);
  const [failed, setFailed] = useState(false);
  const json = JSON.stringify(fields);
  const lastSent = useRef(json);          // 처음 들어온 값은 이미 서버에 있는 값이다
  const latest = useRef(fields);
  latest.current = fields;
  const saveRef = useRef(save);
  saveRef.current = save;
  const timer = useRef<number | null>(null);

  /** 지금 당장 보낸다. 바뀐 게 없으면 아무것도 하지 않는다. */
  const flush = useCallback(async () => {
    if (timer.current) window.clearTimeout(timer.current);
    const body = latest.current;
    const now = JSON.stringify(body);
    if (now === lastSent.current) return;
    try {
      await saveRef.current(body);
      lastSent.current = now;
      setSavedAt(Date.now());
      setFailed(false);
    } catch {
      setFailed(true); // 저장 실패를 조용히 넘기지 않는다 — 사용자가 쓴 글이 걸린 문제다
    }
  }, []);

  useEffect(() => {
    if (!enabled) return;
    if (timer.current) window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => void flush(), CONFIG.DRAFT_AUTOSAVE_MS);
    return () => { if (timer.current) window.clearTimeout(timer.current); };
  }, [json, enabled, flush]);

  return { savedAt, failed, flush };
}

/** STAR 네 칸(화면 표기) → 서버 필드명. 빈 문자열은 null 로 — "안 쓴 칸"과 "빈 문자열"을 구분하지 않는다. */
export const toDraftFields = (d: Record<'S' | 'T' | 'A' | 'R', string>): DraftFields => ({
  situation: d.S.trim() || null,
  task: d.T.trim() || null,
  action: d.A.trim() || null,
  result: d.R.trim() || null,
});
