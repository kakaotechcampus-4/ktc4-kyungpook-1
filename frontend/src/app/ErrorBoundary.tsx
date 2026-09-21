import { isRouteErrorResponse, useRouteError, Link } from 'react-router-dom';
import { ApiError, ContractError } from '@/api/client';
import { Button, Note } from '@/components/ui';

/**
 * 진짜 에러만 여기로 온다. 후보 0개·부분 결과·작업 실패는 200 이라 여기 오지 않는다 — 설계대로.
 * ContractError 는 개발자에게 즉시 보여야 하는 것이라 원문을 노출한다.
 */
export function ErrorBoundary() {
  const err = useRouteError();
  let title = '문제가 생겼습니다', detail = '';
  if (isRouteErrorResponse(err)) { title = `${err.status} ${err.statusText}`; }
  else if (err instanceof ContractError) { title = 'API 계약이 맞지 않습니다'; detail = JSON.stringify(err.issues, null, 2); }
  else if (err instanceof ApiError) { title = err.message; detail = `${err.code} · HTTP ${err.status}`; }
  else if (err instanceof Error) { detail = err.message; }
  return (
    <div style={{ maxWidth: 640, margin: '96px auto', padding: 24, display: 'flex', flexDirection: 'column', gap: 16 }}>
      <h1 className="t-24 w-800">{title}</h1>
      <Note strong="이 화면은 진짜 에러일 때만 뜹니다" tone="danger">후보가 없거나 작업이 실패한 건 에러가 아니라 판정이어서 여기로 오지 않습니다.</Note>
      {detail && <pre style={{ fontSize: 12, background: 'var(--bg-subtle)', padding: 12, borderRadius: 8, overflow: 'auto' }}>{detail}</pre>}
      <div className="row" style={{ gap: 8 }}>
        <Button onClick={() => location.reload()}>다시 시도</Button>
        <Link to="/"><Button variant="outline">홈으로</Button></Link>
      </div>
    </div>
  );
}
