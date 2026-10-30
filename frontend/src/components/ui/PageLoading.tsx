import { useEffect, useState } from 'react';

const VISIBLE_AFTER_MS = 1500;

/**
 * 첫 화면(로그인 확인)과 화면 코드를 기다리는 자리(#520 G-06). 스피너만 있으면 응답이 느릴 때 멈춘 건지 도는 건지 알 수 없다.
 * 스크린리더용 문구는 처음부터 두고, 눈에 보이는 문구는 1.5초가 지나도 안 끝날 때만 붙인다 — 빠른 응답에서는 글자가 깜빡이지 않는다.
 * 로그인 확인 전에도 쓰이므로 사용자 이름 같은 개인 정보는 넣지 않는다. 매초 바뀌는 글과 별도 aria-live 는 두지 않는다.
 */
export function PageLoading() {
  const [slow, setSlow] = useState(false);
  useEffect(() => {
    const t = setTimeout(() => setSlow(true), VISIBLE_AFTER_MS);
    return () => clearTimeout(t);
  }, []);
  return (
    <div role="status" style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 10 }}>
      <span className="spinner" aria-hidden />
      <span className="sr-only">불러오는 중이에요</span>
      {/* 자리를 미리 잡아 두어 문구가 붙어도 스피너가 움직이지 않는다. 스크린리더는 위 문구를 읽으므로 이쪽은 가린다. */}
      <span className="page-loading__text t-14 c-2" style={{ minHeight: 20 }} aria-hidden>{slow ? '불러오는 중이에요' : ''}</span>
    </div>
  );
}
