import { isRouteErrorResponse, useRouteError, Link } from 'react-router-dom';
import { ApiError, ContractError } from '@/api/client';
import { Button } from '@/components/ui';

/**
 * 진짜 에러만 여기로 온다. 후보 0개·부분 결과·작업 실패는 200 이라 여기 오지 않는다 — 설계대로.
 * 사용자에게는 사과와 할 일만 보인다. 원인(API 계약 불일치, HTTP 코드)은 문의할 때 붙여 넣을 수 있게 접어 둔다.
 */
export function ErrorBoundary() {
  const err = useRouteError();
  const notFound = isRouteErrorResponse(err) && err.status === 404;
  let detail = '';
  if (isRouteErrorResponse(err)) detail = `${err.status} ${err.statusText}`;
  else if (err instanceof ContractError) detail = `API 계약 불일치\n${JSON.stringify(err.issues, null, 2)}`;
  else if (err instanceof ApiError) detail = `${err.code}, HTTP ${err.status}, ${err.message}`;
  else if (err instanceof Error) detail = err.message;
  return (
    <main className="error-screen" role="alert">
      <h1 className="error-screen__title">{notFound ? '페이지를 찾을 수 없어요' : '이 화면을 표시하지 못했어요'}</h1>
      <p className="error-screen__desc">{notFound ? '주소가 바뀌었거나 삭제된 화면이에요.' : '입력하던 내용은 자동 저장된 곳까지 남아 있어요. 다시 시도해도 같으면 홈에서 다시 시작해 주세요.'}</p>
      <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
        {!notFound && <Button onClick={() => location.reload()}>다시 시도</Button>}
        <Link to="/" className={`btn ${notFound ? 'btn--primary' : 'btn--outline'}`}>홈으로</Link>
      </div>
      {detail && (
        <details className="error-screen__detail">
          <summary>문의할 때 보낼 정보</summary>
          <pre>{detail}</pre>
        </details>
      )}
    </main>
  );
}
