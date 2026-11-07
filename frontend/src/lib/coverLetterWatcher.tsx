import { useEffect, useRef } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { isCoverLetterRunning, useRunningCoverLetters, type CoverLetterStage } from '@/api/coverLetters';
import { letterTitle } from './homeModel';
import { showBrowserNotice } from './browserNotice';
import { toast } from './toast';

/**
 * 자소서를 쓰던 중에 다른 화면으로 가 있어도 끝난 것을 알린다.
 * 처음 불러온 목록에서 이미 끝나 있던 자소서는 지난 일이라 알리지 않고, AI 단계였던 것이 DRAFTED·FAILED 로 바뀔 때만 알린다.
 */
export function CoverLetterWatcher() {
  const nav = useNavigate();
  const { pathname } = useLocation();
  const letters = useRunningCoverLetters();
  const seen = useRef(new Map<string, CoverLetterStage>());
  const here = useRef(pathname);
  here.current = pathname;

  useEffect(() => {
    const list = letters.data;
    if (!list) return;
    for (const letter of list) {
      const before = seen.current.get(letter.id);
      seen.current.set(letter.id, letter.stage);
      if (!before || !isCoverLetterRunning(before)) continue;
      if (letter.stage !== 'DRAFTED' && letter.stage !== 'FAILED') continue;
      const href = `/cover-letters/${encodeURIComponent(letter.id)}`;
      const failed = letter.stage === 'FAILED';
      const text = `${letterTitle(letter)} — ${failed ? '자소서를 끝내지 못했어요' : '자소서 초안을 다 썼어요'}`;
      showBrowserNotice(text, `cover-letter-${letter.id}`, () => nav(href));
      if (here.current === href) continue;
      toast(text, { tone: failed ? 'danger' : 'success', action: { label: '보기', onClick: () => nav(href) } });
    }
  }, [letters.data, letters.dataUpdatedAt, nav]);

  return null;
}
