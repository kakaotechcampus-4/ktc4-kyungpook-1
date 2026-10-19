import '@/styles/matching.css';
import { Link } from "react-router-dom";
import { ApiError } from "@/api/client";
import { QueryFailure } from "@/components/ui/QueryFailure";
import { Button, PageTitle } from "@/components/ui";
import type { MatchTarget } from "@/api/matching";

export function MatchUnavailable() {
  return (
    <main className="main">
      <PageTitle>공고 매칭을 준비 중이에요</PageTitle>
      <p className="c-2">
        연동이 준비되면 확정한 경험과 채용공고를 비교할 수 있어요.
      </p>
      <Link to="/cards" className="btn btn--outline">
        경험 카드 보기
      </Link>
    </main>
  );
}
export function Fit({ value }: { value: MatchTarget["fit"] }) {
  return value === null ? (
    <span className="match-fit-empty">카드를 확정하면 등급이 나와요</span>
  ) : (
    <span
      className={`match-fit match-fit--${value}`}
      aria-label={`근거 등급 ${value}`}
    >
      {value}
      <span>근거 등급</span>
    </span>
  );
}
export function MatchFailure({
  error,
  retry,
  pending,
}: {
  error: unknown;
  retry: () => unknown;
  pending: boolean;
}) {
  if (error instanceof ApiError && error.code === "AI_UNAVAILABLE")
    return (
      <section className="query-failure" role="alert">
        <h2>경험 근거를 찾지 못했어요</h2>
        <p>분석 도우미가 잠시 응답하지 않아요. 잠시 뒤 다시 시도해 주세요.</p>
        <Button
          variant="outline"
          loading={pending}
          onClick={() => void retry()}
        >
          다시 불러오기
        </Button>
      </section>
    );
  return <QueryFailure error={error} retry={retry} pending={pending} />;
}
